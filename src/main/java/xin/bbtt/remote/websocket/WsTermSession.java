/*
 *   Copyright (C) 2025 huangdihd
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xin.bbtt.remote.websocket;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.xnio.IoUtils;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.remote.JLine.RemoteCLI;
import xin.bbtt.remote.XinRemote;

import java.io.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class WsTermSession {
    private static final List<WebSocketChannel> chs = new CopyOnWriteArrayList<>();
    private static final LinkedBlockingQueue<byte[]> inQ = new LinkedBlockingQueue<>();
    private static volatile boolean running = true;
    private static final Thread inThread = new Thread(WsTermSession::loop, "ws-term");

    public void feed(byte[] bytes) {
        inQ.offer(bytes);
    }
    public static void close(WebSocketChannel ch) {
        IoUtils.safeClose(ch);
        chs.remove(ch);
        if (chs.isEmpty())
            running = false;
    }

    @Getter
    private static final InputStream in = new InputStream() {
        private ByteArrayInputStream current;
        @Override public int read() {
            for (;;) {
                if (current != null) {
                    int b = current.read();
                    System.out.println("read: " + b);
                    if (b != -1) return b;
                    current = null;
                }
                try {
                    byte[] next = running ? inQ.take() : null;
                    if (next == null) return -1;
                    current = new ByteArrayInputStream(next);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
    };

    @Getter
    private static final OutputStream out = new OutputStream() {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        @Override
        public void write(int b) {
            buf.write(b);
            flushMaybe(false);
        }
        @Override
        public void write(byte @NotNull [] b, int off, int len) {
            buf.write(b, off, len);
            flushMaybe(false);
        }
        @Override
        public void flush() {
            flushMaybe(true);
        }
        private synchronized void flushMaybe(boolean force) {
            byte[] b = buf.toByteArray();
            int len = b.length;
            if (len == 0) return;

            boolean hasNewline = false;
            for (byte value : b) {
                if (value == (byte) '\n') {
                    hasNewline = true;
                    break;
                }
            }
            if (!(force || len >= 64 || hasNewline)) return;

            int cut = getCut(len, b);

            if (cut <= 0) return;

            String text = new String(b, 0, cut, java.nio.charset.StandardCharsets.UTF_8);
            if (!text.isEmpty()) {
                for (WebSocketChannel ch : chs) {
                    if (ch.isOpen()) {
                        WebSockets.sendText(text, ch, null);
                    }
                }
            }

            buf.reset();
            if (cut < len) {
                buf.write(b, cut, len - cut);
            }
        }

    };

    private static int getCut(int len, byte[] b) {
        int cut = len;
        // 统计末尾连续的续字节(10xxxxxx)
        int i = len - 1, cont = 0;
        while (i >= 0 && (b[i] & 0xC0) == 0x80) { cont++; i--; }
        if (i >= 0) {
            int lead = b[i] & 0xFF;
            int need = (lead >= 0xF0) ? 3 : (lead >= 0xE0) ? 2 : (lead >= 0xC0) ? 1 : 0;
            // 如果续字节不够，连同这个起始字节一起留到下次
            if (cont < need) cut = i;
        } else {
            // 整个缓冲全是续字节（极端情况），这批先不发
            cut = 0;
        }
        return cut;
    }

    WsTermSession(WebSocketChannel ch) {
        WsTermSession.chs.add(ch);
        WsTermSession.running = true;
    }

    public void start() {
        if (inThread.isAlive()) return;
        inThread.setDaemon(true);
        inThread.start();
    }

    private static void loop() {
        try {
            while (running && !chs.isEmpty() && !Thread.currentThread().isInterrupted() && RemoteCLI.getRemoteLineReader() != null) {
                String input = null;
                try {
                    input = RemoteCLI.getRemoteLineReader().readLine("> ");
                }
                catch (UserInterruptException | EndOfFileException e) {
                    Bot.Instance.stop();
                }
                catch (Exception e) {
                    XinRemote.getLog().error(e.getMessage(), e);
                }
                if (input == null || input.isEmpty()) continue;
                Bot.Instance.getPluginManager().commands().callCommand(input);
            }
        }
        catch (Exception e) {
            XinRemote.getLog().error(e.getMessage(), e);
        }
        finally {
            for (WebSocketChannel ch : chs)
                close(ch);
        }
    }
}

