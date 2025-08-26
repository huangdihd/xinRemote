package xin.bbtt.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Undertow;
import io.undertow.server.RoutingHandler;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.plugin.Plugin;
import xin.bbtt.remote.config.Config;
import xin.bbtt.remote.endPoints.Index;
import xin.bbtt.remote.endPoints.Status;
import xin.bbtt.remote.endPoints.config.Account;
import xin.bbtt.remote.middleware.AuthMiddleware;

import java.io.File;
import java.io.IOException;

@Getter
public class XinRemote implements Plugin {
    private Undertow server;
    private static final Logger log = LoggerFactory.getLogger(XinRemote.class);
    @Getter
    private Config config;
    @Getter
    private final ObjectMapper mapper = new ObjectMapper();
    @Getter
    private static XinRemote Instance;

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

        RoutingHandler routes = new RoutingHandler();
        routes.get("/", new Index());
        routes.get("/status", new AuthMiddleware(new Status()));
        routes.get("/config/account", new AuthMiddleware(new Account()));
        routes.get("/config", new AuthMiddleware(new xin.bbtt.remote.endPoints.Config()));
        server = Undertow.builder()
                .addHttpListener(config.getPort(), config.getHost())
                .setHandler(routes)
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
