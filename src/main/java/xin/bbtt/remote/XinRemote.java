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

package xin.bbtt.remote;

import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import lombok.Getter;
import net.lenni0451.commons.httpclient.constants.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.plugin.Plugin;
import xin.bbtt.remote.JLine.RemoteCLI;
import xin.bbtt.remote.JLine.RemoteConsoleAppender;
import xin.bbtt.remote.config.Config;
import xin.bbtt.remote.endPoints.Index;
import xin.bbtt.remote.endPoints.Players;
import xin.bbtt.remote.endPoints.Status;
import xin.bbtt.remote.endPoints.config.Account;
import xin.bbtt.remote.middleware.AuthMiddleware;
import xin.bbtt.remote.websocket.WsTermCallback;

import java.io.File;
import java.io.IOException;

@Getter
public class XinRemote implements Plugin {
    private Undertow server;
    @Getter
    private static final Logger log = LoggerFactory.getLogger(XinRemote.class);
    @Getter
    private Config config;
    @Getter
    private final ObjectMapper mapper = new ObjectMapper();
    @Getter
    private static XinRemote Instance;

    static HttpHandler withCors(HttpHandler next) {
        return exchange -> {
            // 永远允许所有域
            exchange.getResponseHeaders().put(HttpString.tryFromString(Headers.ACCESS_CONTROL_ALLOW_ORIGIN), "*");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Methods"), "GET,POST,PUT,DELETE,OPTIONS");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Allow-Headers"), "content-type,authorization");
            exchange.getResponseHeaders().put(new HttpString("Access-Control-Expose-Headers"), "content-type");

            if (exchange.getRequestMethod().equals(Methods.OPTIONS)) {
                exchange.setStatusCode(204);
                exchange.endExchange();
                return;
            }
            next.handleRequest(exchange);
        };
    }

    @Override
    public void onLoad() {
        Instance = this;

        File configFile = new File("remote_config.json");
        if (!configFile.exists()) {
            try {
                if (!configFile.createNewFile()) {
                    log.error("Failed to create config file");
                    System.exit(-1);
                }
                Config defaultConfig = new Config();
                defaultConfig.setHost("localhost");
                defaultConfig.setPort(8978);
                defaultConfig.setToken(Utils.generateRandomToken(32));
                mapper.writeValue(configFile, defaultConfig);
                log.info("Generated default config file: {}", configFile.getAbsolutePath());
                config = defaultConfig;
            } catch (IOException e) {
                log.error("Failed to create config file", e);
                System.exit(-1);
            }
        }
        if (configFile.isFile()) {
            try {
                config = getMapper().readValue(configFile, Config.class);
            } catch (IOException e) {
                log.error("Failed to read config file", e);
                System.exit(-1);
            }
        }

        RemoteCLI.init();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        RemoteConsoleAppender appender = new RemoteConsoleAppender();

        appender.setContext(context);

        ch.qos.logback.classic.encoder.PatternLayoutEncoder ple = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        ple.setContext(context);
        ple.setPattern("[%d{HH:mm:ss} %highlight(%5level)] [%cyan(%logger)]%marker: %msg%n");
        ple.setCharset(java.nio.charset.StandardCharsets.UTF_8);
        ple.start();

        appender.setEncoder(ple);

        appender.start();
        ch.qos.logback.classic.Logger rootLogger = context.getLogger("ROOT");
        rootLogger.addAppender(appender);

        RoutingHandler routes = new RoutingHandler();
        routes.get("/", new Index());
        routes.get("/status", new AuthMiddleware(new Status()));
        routes.get("/config/account", new AuthMiddleware(new Account()));
        routes.get("/config", new AuthMiddleware(new xin.bbtt.remote.endPoints.Config()));
        routes.get("/players", new AuthMiddleware(new Players()));
        PathHandler root = Handlers.path()
                .addPrefixPath("/", routes)
                .addPrefixPath("/term", Handlers.websocket(new WsTermCallback()));

        server = Undertow.builder()
                .addHttpListener(config.getPort(), config.getHost())
                .setHandler(withCors(root))
                .build();
        server.start();
        //noinspection HttpUrlsUsage
        log.info("Server started at http://{}:{}", config.getHost(), config.getPort());
    }

    @Override
    public void onUnload() {
        server.stop();
    }

    @Override
    public void onEnable() {

    }

    @Override
    public void onDisable() {

    }

}
