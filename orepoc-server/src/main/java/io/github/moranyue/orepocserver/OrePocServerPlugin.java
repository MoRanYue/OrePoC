package io.github.moranyue.orepocserver;

import com.sun.net.httpserver.HttpServer;
import io.github.moranyue.orepocserver.generation.OreCache;
import io.github.moranyue.orepocserver.websocket.OreApiHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public final class OrePocServerPlugin extends JavaPlugin {

    private static final int DEFAULT_PORT = 32567;
    private HttpServer httpServer;
    private OreCache oreCache;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getConfig().getInt("port", DEFAULT_PORT);

        oreCache = new OreCache(getConfig().getInt("cache-size", 1024));

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/orepoc", new OreApiHandler(this, oreCache));
            httpServer.setExecutor(Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())));
            httpServer.start();
            getLogger().info("OrePoC HTTP API started on port " + port);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start HTTP server on port " + port, e);
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (oreCache != null) {
            oreCache.clear();
        }
    }

    public OreCache getOreCache() {
        return oreCache;
    }
}
