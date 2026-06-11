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
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import xin.bbtt.remote.XinRemote;

import java.util.List;

/**
 * WebSocket callback for the /world endpoint.
 * Each authenticated connection gets a {@link WsWorldSession}
 * that streams chunk, entity, and position data from the bot
 * to the browser viewer in real time.
 */
public class WsWorldCallback implements WebSocketConnectionCallback {

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        String token = exchange.getRequestParameters()
                .getOrDefault("token", List.of(""))
                .get(0);
        if (!token.equals(XinRemote.getInstance().getConfig().getToken())) {
            exchange.close();
            return;
        }

        WsWorldSession session = new WsWorldSession(channel);
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onClose(WebSocketChannel ch,
                                   StreamSourceFrameChannel frameChannel) {
                WsWorldSession.close(channel);
            }
        });
        channel.addCloseTask(s -> WsWorldSession.close(channel));
        channel.resumeReceives();
        session.start();
    }
}
