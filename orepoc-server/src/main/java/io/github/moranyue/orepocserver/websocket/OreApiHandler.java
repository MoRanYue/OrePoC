package io.github.moranyue.orepocserver.websocket;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.moranyue.orepocserver.OrePocServerPlugin;
import io.github.moranyue.orepocserver.generation.OreCache;
import io.github.moranyue.orepocserver.generation.OreCache.OrePosition;
import io.github.moranyue.orepocserver.generation.OreScanner;
import org.bukkit.Bukkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OreApiHandler implements HttpHandler {

    private static final Gson GSON = new GsonBuilder().create();
    private final OrePocServerPlugin plugin;
    private final OreCache cache;
    private final OreScanner scanner;

    public OreApiHandler(OrePocServerPlugin plugin, OreCache cache) {
        this.plugin = plugin;
        this.cache = cache;
        this.scanner = new OreScanner(plugin, cache);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonObject request;
        try {
            request = GSON.fromJson(body, JsonObject.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        String type = request.has("type") ? request.get("type").getAsString() : "";

        JsonObject response = new JsonObject();

        try {
            switch (type) {
                case "set_seed" -> {
                    long seed = request.get("seed").getAsLong();
                    cache.setSeed(seed);
                    response.addProperty("status", "ok");
                    plugin.getLogger().info("Seed set to " + seed);
                }
                case "request_chunk" -> {
                    int cx = request.get("chunkX").getAsInt();
                    int cz = request.get("chunkZ").getAsInt();
                    List<OrePosition> ores = getOrGenerate(cx, cz);
                    response.addProperty("type", "chunk_data");
                    response.addProperty("chunkX", cx);
                    response.addProperty("chunkZ", cz);
                    response.add("ores", oresToJsonArray(ores));
                }
                case "request_batch" -> {
                    JsonArray chunks = request.getAsJsonArray("chunks");
                    List<JsonObject> results = new ArrayList<>();
                    for (JsonElement elem : chunks) {
                        JsonObject coords = elem.getAsJsonObject();
                        int cx = coords.get("chunkX").getAsInt();
                        int cz = coords.get("chunkZ").getAsInt();
                        List<OrePosition> ores = getOrGenerate(cx, cz);
                        JsonObject chunkData = new JsonObject();
                        chunkData.addProperty("chunkX", cx);
                        chunkData.addProperty("chunkZ", cz);
                        chunkData.add("ores", oresToJsonArray(ores));
                        results.add(chunkData);
                    }
                    response.addProperty("type", "batch_complete");
                    response.addProperty("count", results.size());
                    response.add("chunks", GSON.toJsonTree(results));
                }
                default -> {
                    response.addProperty("error", "Unknown type: " + type);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing request: " + e.getMessage());
            response.addProperty("error", e.getMessage());
        }

        sendJson(exchange, 200, GSON.toJson(response));
    }

    private List<OrePosition> getOrGenerate(int cx, int cz) {
        List<OrePosition> cached = cache.get(cx, cz);
        if (cached != null) return cached;

        // Generate on main thread (required by Bukkit API)
        if (!Bukkit.isPrimaryThread()) {
            try {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    generateAndCache(cx, cz);
                    return null;
                }).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            generateAndCache(cx, cz);
        }

        return cache.get(cx, cz);
    }

    private void generateAndCache(int cx, int cz) {
        List<OrePosition> ores = scanner.scanChunk(cx, cz);
        cache.put(cx, cz, ores);
        plugin.getLogger().fine("Generated " + ores.size() + " ores for chunk " + cx + "," + cz);
    }

    /** Convert a list of OrePositions to a JsonArray with proper JsonObject elements. */
    private JsonArray oresToJsonArray(List<OrePosition> ores) {
        JsonArray array = new JsonArray();
        for (OrePosition ore : ores) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", ore.x());
            obj.addProperty("y", ore.y());
            obj.addProperty("z", ore.z());
            obj.addProperty("block", ore.block());
            array.add(obj);
        }
        return array;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
