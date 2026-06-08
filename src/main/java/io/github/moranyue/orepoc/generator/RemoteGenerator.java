package io.github.moranyue.orepoc.generator;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Generator that fetches ore positions from a local Paper server
 * running the OrePocServer plugin. Falls back to LocalWorldGenerator
 * if the server is unreachable.
 */
public final class RemoteGenerator {

    public static final RemoteGenerator INSTANCE = new RemoteGenerator();
    private static final Logger LOGGER = LoggerFactory.getLogger("orepoc/remote");
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 32567;
    private String serverHost = DEFAULT_HOST;
    private int serverPort = DEFAULT_PORT;
    private static final int MAX_CACHE_SIZE = 512;

    private final HttpClient httpClient;
    private final Gson gson = new GsonBuilder().create();
    private final Map<ChunkKey, Map<BlockPos, BlockState>> cache;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    private boolean available = false;

    private record ChunkKey(String dimension, int x, int z) {
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkKey k)) return false;
            return x == k.x && z == k.z && dimension.equals(k.dimension);
        }
        @Override public int hashCode() { return dimension.hashCode() * 31 * 31 + x * 31 + z; }
    }

    private String getServerUrl() {
        return "http://" + serverHost + ":" + serverPort + "/orepoc";
    }

    private RemoteGenerator() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.cache = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkKey, Map<BlockPos, BlockState>> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
        checkConnection();
    }

    /** Check if the Paper server is reachable (idempotent ping, no side effects). */
    public boolean checkConnection() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(getServerUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"ping\"}"))
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            available = resp.statusCode() == 200;
            if (available) {
                LOGGER.info("Connected to OrePoC Server at {}:{}", serverHost, serverPort);
            }
        } catch (Exception e) {
            available = false;
        }
        return available;
    }

    /** Set a custom server address and reconnect. Returns true if connection succeeds. */
    public boolean setServer(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        cacheLock.writeLock().lock();
        try { cache.clear(); } finally { cacheLock.writeLock().unlock(); }
        return checkConnection();
    }

    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }

    public boolean isAvailable() { return available; }

    private final Set<ChunkKey> pendingRequests = ConcurrentHashMap.newKeySet();

    /**
     * Get ore predictions for a chunk in a specific dimension.
     * @param dimension Resource location string, e.g. "minecraft:overworld", "minecraft:the_nether"
     */
    public Map<BlockPos, BlockState> getChunkOres(String dimension, int chunkX, int chunkZ) {
        if (!available) return null;

        ChunkKey key = new ChunkKey(dimension, chunkX, chunkZ);

        // Check cache first
        cacheLock.readLock().lock();
        Map<BlockPos, BlockState> r = cache.get(key);
        cacheLock.readLock().unlock();
        if (r != null) return r;

        // Submit async fetch if not already pending
        if (pendingRequests.add(key)) {
            LOGGER.info("Fetching chunk [{}] {},{} from OrePoC server", dimension, chunkX, chunkZ);
            asyncExecutor.submit(() -> {
                try {
                    String json = sendRequest(
                        "{\"type\":\"request_chunk\",\"dimension\":\"" + dimension +
                        "\",\"chunkX\":" + chunkX + ",\"chunkZ\":" + chunkZ + "}");
                    Map<BlockPos, BlockState> result = parseResponse(json);
                    LOGGER.info("Received chunk [{}] {},{} from OrePoC server: {} ores",
                        dimension, chunkX, chunkZ, result.size());
                    // Only cache non-empty results. If the chunk hasn't been generated
                    // on the server yet, it returns empty; don't cache that so the
                    // next access will retry.
                    if (!result.isEmpty()) {
                        cacheLock.writeLock().lock();
                        try { cache.put(key, result); } finally { cacheLock.writeLock().unlock(); }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to get chunk [{}] {},{}: {}", dimension, chunkX, chunkZ, e.getMessage());
                } finally {
                    pendingRequests.remove(key);
                }
            });
        }
        return null; // Not cached yet, caller should retry later
    }

    /** Wait for all pending requests to complete. */
    public void waitForPending() {
        while (!pendingRequests.isEmpty()) {
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
    }

    private String sendRequest(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(getServerUrl()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Server returned " + resp.statusCode());
        }
        return resp.body();
    }

    private Map<BlockPos, BlockState> parseResponse(String json) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        if (obj.has("ores")) {
            JsonArray ores = obj.getAsJsonArray("ores");
            for (JsonElement elem : ores) {
                JsonObject ore = elem.getAsJsonObject();
                int x = ore.get("x").getAsInt();
                int y = ore.get("y").getAsInt();
                int z = ore.get("z").getAsInt();
                String blockStr = ore.get("block").getAsString();
                BlockState state = parseBlockState(blockStr);
                if (state != null && !state.isAir()) {
                    result.put(new BlockPos(x, y, z), state);
                }
            }
        }
        return result;
    }

    /** Parse a block resource location string into BlockState. */
    private BlockState parseBlockState(String blockStr) {
        return switch (blockStr) {
            // Overworld ores
            case "minecraft:coal_ore" -> Blocks.COAL_ORE.defaultBlockState();
            case "minecraft:deepslate_coal_ore" -> Blocks.DEEPSLATE_COAL_ORE.defaultBlockState();
            case "minecraft:iron_ore" -> Blocks.IRON_ORE.defaultBlockState();
            case "minecraft:deepslate_iron_ore" -> Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
            case "minecraft:copper_ore" -> Blocks.COPPER_ORE.defaultBlockState();
            case "minecraft:deepslate_copper_ore" -> Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
            case "minecraft:gold_ore" -> Blocks.GOLD_ORE.defaultBlockState();
            case "minecraft:deepslate_gold_ore" -> Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
            case "minecraft:redstone_ore" -> Blocks.REDSTONE_ORE.defaultBlockState();
            case "minecraft:deepslate_redstone_ore" -> Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
            case "minecraft:lapis_ore" -> Blocks.LAPIS_ORE.defaultBlockState();
            case "minecraft:deepslate_lapis_ore" -> Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState();
            case "minecraft:diamond_ore" -> Blocks.DIAMOND_ORE.defaultBlockState();
            case "minecraft:deepslate_diamond_ore" -> Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
            case "minecraft:emerald_ore" -> Blocks.EMERALD_ORE.defaultBlockState();
            case "minecraft:deepslate_emerald_ore" -> Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState();
            // Nether ores
            case "minecraft:nether_gold_ore" -> Blocks.NETHER_GOLD_ORE.defaultBlockState();
            case "minecraft:nether_quartz_ore" -> Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
            case "minecraft:ancient_debris" -> Blocks.ANCIENT_DEBRIS.defaultBlockState();
            // Raw ore blocks (large ore veins)
            case "minecraft:raw_iron_block" -> Blocks.RAW_IRON_BLOCK.defaultBlockState();
            case "minecraft:raw_copper_block" -> Blocks.RAW_COPPER_BLOCK.defaultBlockState();
            case "minecraft:raw_gold_block" -> Blocks.RAW_GOLD_BLOCK.defaultBlockState();
            case "minecraft:mossy_cobblestone" -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case "minecraft:amethyst_block" -> Blocks.AMETHYST_BLOCK.defaultBlockState();
            case "minecraft:gilded_blackstone" -> Blocks.GILDED_BLACKSTONE.defaultBlockState();
            default -> {
                LOGGER.warn("Unknown block type: {}", blockStr);
                yield null;
            }
        };
    }

    public void clearCache() {
        cacheLock.writeLock().lock();
        try { cache.clear(); } finally { cacheLock.writeLock().unlock(); }
    }
}
