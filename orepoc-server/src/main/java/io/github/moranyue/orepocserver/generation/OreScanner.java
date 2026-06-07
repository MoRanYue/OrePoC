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
        Material.MOSSY_COBBLESTONE
    );

    private final JavaPlugin plugin;

    public OreScanner(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Scan a chunk for all ore blocks.
     * Only scans chunks that are already fully generated to avoid blocking the server thread.
     * Returns empty list if the chunk is not yet generated (client can retry later).
     */
    public List<OrePosition> scanChunk(int chunkX, int chunkZ) {
        World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            plugin.getLogger().warning("No world available!");
            return Collections.emptyList();
        }

        // Only scan chunks that are already fully generated — never force generation
        // to avoid blocking the main server thread on chunk generation tasks
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            plugin.getLogger().fine("Chunk " + chunkX + "," + chunkZ + " not yet generated, skipping");
            return Collections.emptyList();
        }

        List<OrePosition> result = new ArrayList<>();

        try {
            // Load the already-generated chunk (won't trigger generation)
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
