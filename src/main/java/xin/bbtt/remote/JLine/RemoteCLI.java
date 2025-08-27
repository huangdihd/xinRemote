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

import lombok.Getter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import xin.bbtt.mcbot.JLine.JLineCommandCompleter;
import xin.bbtt.remote.websocket.WsTermSession;

public class RemoteCLI{
    @Getter
    private static LineReader remoteLineReader;

    public static void init() {
        try {

        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(WsTermSession.getIn(), WsTermSession.getOut())
                .type("xterm-256color")
                .size(new Size(24, 80))
                .build();

        remoteLineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new JLineCommandCompleter())
                .parser(new DefaultParser())
                .build();

        RemoteConsoleAppender.setRemoteLineReader(remoteLineReader);

        } catch (Exception e) {
            System.err.println("Failed to initialize remote terminal: " + e.getMessage());
        }
    }
}
