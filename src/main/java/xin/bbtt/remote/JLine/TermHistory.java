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

package xin.bbtt.remote.JLine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * In-memory ring buffer of recent console log output, byte-capped.
 * Filled by {@link RemoteConsoleAppender} for every log event (even when no
 * terminal client is connected) and replayed to newly connected /term
 * WebSocket sessions so they can see what happened before they attached.
 */
public final class TermHistory {
    private static final int MIN_CAP_BYTES = 16 * 1024;

    private static final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private static int totalBytes = 0;
    private static int capBytes = 512 * 1024;

    private TermHistory() {}

    public static synchronized void setCapBytes(int cap) {
        capBytes = Math.max(MIN_CAP_BYTES, cap);
        trim();
    }

    public static synchronized void append(String text) {
        if (text == null || text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        chunks.addLast(bytes);
        totalBytes += bytes.length;
        trim();
    }

    public static synchronized String snapshot() {
        if (chunks.isEmpty()) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalBytes);
        for (byte[] chunk : chunks) out.write(chunk, 0, chunk.length);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void trim() {
        // Evict whole chunks (single log events) oldest-first while over cap.
        while (totalBytes > capBytes && !chunks.isEmpty()) {
            totalBytes -= chunks.removeFirst().length;
        }
    }
}
