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

import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import xin.bbtt.remote.XinRemote;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class WsTermCallback implements WebSocketConnectionCallback {
    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        String token = exchange.getRequestParameters().getOrDefault("token", List.of("")).get(0);
        if (!token.equals(XinRemote.getInstance().getConfig().getToken())) {
            exchange.close(); return;
        }

        WsTermSession session = new WsTermSession(channel);
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage msg) {
                session.feed(msg.getData().getBytes(StandardCharsets.UTF_8));
            }
            @Override protected void onClose(WebSocketChannel webSocketChannel, StreamSourceFrameChannel frameChannel) {
                session.close();
            }
        });
        channel.addCloseTask(c -> session.close());
        channel.resumeReceives();
        session.start();
    }
}
