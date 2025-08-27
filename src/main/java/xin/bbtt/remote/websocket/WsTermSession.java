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

import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.xnio.IoUtils;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.JLine.CLI;
import xin.bbtt.remote.JLine.RemoteCLI;
import xin.bbtt.remote.XinRemote;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

public class WsTermSession {
    private static WebSocketChannel ch = null;
    private static final LinkedBlockingQueue<byte[]> inQ = new LinkedBlockingQueue<>();
    private static volatile boolean running = true;

    public void feed(byte[] bytes) { if (running) inQ.offer(bytes); }
    public void close() {
        running = false; IoUtils.safeClose(ch);
        try {
            RemoteCLI.getRemoteLineReader().getTerminal().close();
        } catch (Exception ignored) {

        }
    }

    @Getter
    private static final InputStream in = new InputStream() {
        private ByteArrayInputStream current;
        @Override public int read() {
            for (;;) {
                if (current != null) {
                    int b = current.read();
                    if (b != -1) return b;
                    current = null;
                }
                try {
                    byte[] next = running ? inQ.take() : null;
                    if (next == null) return -1;
                    current = new ByteArrayInputStream(next);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            }
        }
    };

    @Getter
    private static final OutputStream out = new OutputStream() {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        @Override public void write(int b) { buf.write(b); flushMaybe(false); }
        @Override public void write(byte @NotNull [] b, int off, int len) { buf.write(b, off, len); flushMaybe(false); }
        @Override public void flush() { flushMaybe(true); }
        private void flushMaybe(boolean force) {
            if (force || buf.size() > 1024) {
                byte[] data = buf.toByteArray(); buf.reset();
                if (ch.isOpen())
                    WebSockets.sendText(ByteBuffer.wrap(data).asCharBuffer().toString(), ch, null);
            }
        }
    };

    WsTermSession(WebSocketChannel ch) {
        WsTermSession.ch = ch;
        ch.getReceiveSetter().set(new AbstractReceiveListener(){
            @Override
            protected void onFullTextMessage(WebSocketChannel c, BufferedTextMessage msg) {
                String s = msg.getData();
                feed(s.getBytes(StandardCharsets.UTF_8));
            }
        });

    }

    public void start() {
        Thread t = new Thread(this::loop, "ws-term");
        t.setDaemon(true); t.start();
    }

    private void loop() {
        try {
            while (running && ch.isOpen() && !Thread.currentThread().isInterrupted() && RemoteCLI.getRemoteLineReader() != null) {
                String input = null;
                try {
                    input = CLI.getLineReader().readLine("> ");
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
            close();
        }
    }
}

