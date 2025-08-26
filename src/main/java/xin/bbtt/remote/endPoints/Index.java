package xin.bbtt.remote.endPoints;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public class Index implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        httpServerExchange.getResponseSender().send("[XinRemote] Status: OK | reference: https://github.com/huangdihd/XinBot");
    }
}