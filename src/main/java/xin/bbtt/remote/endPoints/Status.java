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

package xin.bbtt.remote.endPoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Xinbot;
import xin.bbtt.remote.responseObjects.StatusResponse;

public class Status implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        httpServerExchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(new StatusResponse(
                Xinbot.version,
                Bot.Instance.getConfig().getAccount().getName(),
                (Bot.Instance.getServer() != null
                        ? Bot.Instance.getServer().toString() : "Connecting..."
                )
        )
        );
        httpServerExchange.getResponseSender().send(json);
    }
}
