package xin.bbtt.remote.middleware;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.StatusCodes;
import xin.bbtt.remote.XinRemote;

public class AuthMiddleware implements HttpHandler {
    private final HttpHandler next;

    public AuthMiddleware(HttpHandler next) {
        this.next = next;
    }
    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        HeaderValues authHeader = httpServerExchange.getRequestHeaders().get("Authorization");
        httpServerExchange.setStatusCode(StatusCodes.UNAUTHORIZED);
        if  (authHeader == null) {
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
