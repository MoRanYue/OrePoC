package io.github.moranyue.orepocserver.generation;

import io.github.moranyue.orepocserver.generation.OreCache.OrePosition;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Level;

/**
 * Scans chunks for ore blocks using the Bukkit API.
 * This gives 100% accurate results because it uses the server's
 * actual world generator with the correct seed.
 */
public class OreScanner {

    private static final Set<Material> ORE_MATERIALS = EnumSet.of(
        // Overworld ores
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        // Nether ores
        Material.NETHER_GOLD_ORE,
        Material.NETHER_QUARTZ_ORE,
        Material.ANCIENT_DEBRIS,
        // Raw ore blocks (large ore veins)
        Material.RAW_IRON_BLOCK,
        Material.RAW_COPPER_BLOCK,
        Material.RAW_GOLD_BLOCK,
        // Additional blocks that may be used by anti-xray or custom ore gen
        Material.MOSSY_COBBLESTONE,
        Material.AMETHYST_BLOCK,
        Material.GILDED_BLACKSTONE,
        Material.BUDDING_AMETHYST
    );

    private final JavaPlugin plugin;
    private final io.github.moranyue.orepocserver.generation.OreCache cache;

    public OreScanner(JavaPlugin plugin, io.github.moranyue.orepocserver.generation.OreCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    /**
     * Scan a chunk for all ore blocks.
     * If the chunk is not yet generated, triggers async generation and returns empty.
     * When async generation completes, the chunk is scanned and cached automatically.
     * @param world The world/dimension to scan (e.g. overworld, nether, end)
     */
    public List<OrePosition> scanChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            plugin.getLogger().warning("No world provided for scanning chunk " + chunkX + "," + chunkZ);
            return Collections.emptyList();
        }

        // If chunk is already generated, scan it synchronously
        if (world.isChunkGenerated(chunkX, chunkZ)) {
            return scanGeneratedChunk(world, chunkX, chunkZ);
        }

        // Chunk not yet generated — trigger async generation and return empty
        plugin.getLogger().info("Triggering async generation for chunk [" + world.getName() + "] " + chunkX + "," + chunkZ);
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
            plugin.getLogger().info("Async generation complete for chunk [" + world.getName() + "] " + chunkX + "," + chunkZ);
            List<OrePosition> ores = scanGeneratedChunk(world, chunkX, chunkZ);
            cache.put(chunkX, chunkZ, ores);
        }).exceptionally(e -> {
            plugin.getLogger().warning("Async generation failed for chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
            return null;
        });

        return Collections.emptyList();
    }

    /**
     * Scan a fully-generated chunk for ore blocks.
     * Must only be called for chunks where isChunkGenerated() returns true.
     */
    private List<OrePosition> scanGeneratedChunk(World world, int chunkX, int chunkZ) {
        List<OrePosition> result = new ArrayList<>();

        try {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (chunk == null) {
                plugin.getLogger().warning("Failed to load chunk " + chunkX + "," + chunkZ);
                return Collections.emptyList();
            }

            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        Block block = chunk.getBlock(x, y, z);
                        Material type = block.getType();
                        if (ORE_MATERIALS.contains(type)) {
                            int worldX = chunkX * 16 + x;
                            int worldZ = chunkZ * 16 + z;
                            result.add(new OrePosition(worldX, y, worldZ, type.getKey().toString()));
                        }
                    }
                }
            }

            plugin.getLogger().fine("Scanned chunk " + chunkX + "," + chunkZ +
                ": found " + result.size() + " ores");

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                "Error scanning chunk " + chunkX + "," + chunkZ, e);
        }

        return result;
    }
}
