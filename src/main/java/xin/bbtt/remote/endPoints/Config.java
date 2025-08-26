package xin.bbtt.remote.endPoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import xin.bbtt.mcbot.Bot;

public class Config implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        httpServerExchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Bot.Instance.getConfig());
        httpServerExchange.getResponseSender().send(json);
    }
}
