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

package xin.bbtt.remote.middleware;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.StatusCodes;
import xin.bbtt.remote.XinRemote;

public record AuthMiddleware(HttpHandler next) implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        HeaderValues authHeader = httpServerExchange.getRequestHeaders().get("Authorization");
        httpServerExchange.setStatusCode(StatusCodes.UNAUTHORIZED);
        if (authHeader == null) {
            httpServerExchange.getResponseSender().send("Unauthorized");
            return;
        }
        String auth = authHeader.getFirst();
        if (auth == null || !auth.startsWith("Bearer ")) {
            httpServerExchange.getResponseSender().send("Unauthorized");
            return;
        }
        String token = auth.substring(7);
        if (!token.equals(XinRemote.getInstance().getConfig().getToken())) {
            httpServerExchange.getResponseSender().send("Unauthorized");
            return;
        }
        httpServerExchange.setStatusCode(StatusCodes.OK);
        next.handleRequest(httpServerExchange);
    }
}
