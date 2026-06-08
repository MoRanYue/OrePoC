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
                case "ping" -> {
                    response.addProperty("status", "ok");
                    response.addProperty("message", "pong");
                }
                case "request_chunk" -> {
                    int cx = request.get("chunkX").getAsInt();
                    int cz = request.get("chunkZ").getAsInt();
                    String dimension = request.has("dimension") ? request.get("dimension").getAsString() : "minecraft:overworld";
                    List<OrePosition> ores = getOrGenerate(dimension, cx, cz);
                    response.addProperty("type", "chunk_data");
                    response.addProperty("chunkX", cx);
                    response.addProperty("chunkZ", cz);
                    response.addProperty("dimension", dimension);
                    response.add("ores", oresToJsonArray(ores));
                }
                case "request_batch" -> {
                    JsonArray chunks = request.getAsJsonArray("chunks");
                    List<JsonObject> results = new ArrayList<>();
                    for (JsonElement elem : chunks) {
                        JsonObject coords = elem.getAsJsonObject();
                        int cx = coords.get("chunkX").getAsInt();
                        int cz = coords.get("chunkZ").getAsInt();
                        String dimension = coords.has("dimension") ? coords.get("dimension").getAsString() : "minecraft:overworld";
                        List<OrePosition> ores = getOrGenerate(dimension, cx, cz);
                        JsonObject chunkData = new JsonObject();
                        chunkData.addProperty("chunkX", cx);
                        chunkData.addProperty("chunkZ", cz);
                        chunkData.addProperty("dimension", dimension);
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

    private List<OrePosition> getOrGenerate(String dimension, int cx, int cz) {
        List<OrePosition> cached = cache.get(cx, cz);
        if (cached != null) return cached;

        // Use the correct world for the requested dimension
        org.bukkit.World world = getWorldForDimension(dimension);
        if (world == null) {
            plugin.getLogger().warning("No world available for dimension: " + dimension);
            return Collections.emptyList();
        }

        // Generate on main thread (required by Bukkit API)
        if (!Bukkit.isPrimaryThread()) {
            try {
                final org.bukkit.World genWorld = world;
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    generateAndCache(genWorld, cx, cz);
                    return null;
                }).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            generateAndCache(world, cx, cz);
        }

        // For ungenerated chunks, generateAndCache only triggers async generation
        // without caching; the cache will still be null here — return empty list.
        List<OrePosition> result = cache.get(cx, cz);
        return result != null ? result : Collections.emptyList();
    }

    /**
     * Resolve a Bukkit World for a Minecraft dimension resource location.
     */
    private static org.bukkit.World getWorldForDimension(String dimension) {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            String worldDim = w.getKey().toString(); // e.g. "minecraft:overworld"
            // Paper's getKey returns the world name, not dimension. Use environment match.
            // Match by environment type
            switch (dimension) {
                case "minecraft:overworld" -> {
                    if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) return w;
                }
                case "minecraft:the_nether" -> {
                    if (w.getEnvironment() == org.bukkit.World.Environment.NETHER) return w;
                }
                case "minecraft:the_end" -> {
                    if (w.getEnvironment() == org.bukkit.World.Environment.THE_END) return w;
                }
                default -> {
                    // Fallback: try matching by name
                    String dimName = dimension.replace("minecraft:", "");
                    if (w.getName().equalsIgnoreCase(dimName) || w.getName().endsWith("_" + dimName)) {
                        return w;
                    }
                }
            }
        }
        // Fallback to first world
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    private void generateAndCache(org.bukkit.World world, int cx, int cz) {
        // If the chunk is already generated, scan synchronously and cache immediately.
        // For ungenerated chunks, scanChunk() triggers async generation and returns empty;
        // the async callback handles caching on its own — don't cache the empty placeholder.
        if (world.isChunkGenerated(cx, cz)) {
            List<OrePosition> ores = scanner.scanChunk(world, cx, cz);
            cache.put(cx, cz, ores);
            plugin.getLogger().fine("Generated " + ores.size() + " ores for chunk " + cx + "," + cz);
        } else {
            scanner.scanChunk(world, cx, cz);
        }
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
