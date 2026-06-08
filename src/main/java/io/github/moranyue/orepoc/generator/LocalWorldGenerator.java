package io.github.moranyue.orepoc.generator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration.TargetBlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class LocalWorldGenerator {

    public static final LocalWorldGenerator INSTANCE = new LocalWorldGenerator();
    private static final Logger LOGGER = LoggerFactory.getLogger("orepoc/generator");
    private static final int MAX_CACHE_SIZE = 64;
    private static final int RADIUS = 1; // 3x3 chunks (radius=1 => 3x3)

    public enum Mode {
        LOCAL,    // Use local generation (approximate)
        REMOTE,   // Use remote Paper server (accurate)
        NONE      // Disable ore replacement entirely
    }

    private Mode mode = Mode.NONE;
    private long seed = 0L;
    private boolean seedSet = false;
    private int playerChunkX = Integer.MIN_VALUE;
    private int playerChunkZ = Integer.MIN_VALUE;
    private int lastGenChunkX = Integer.MIN_VALUE;
    private int lastGenChunkZ = Integer.MIN_VALUE;

    private final Map<ChunkPos, Map<BlockPos, BlockState>> oreCache;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private List<OreConfigEntry> oreConfigs = Collections.emptyList();
    private ChunkGenerator dummyChunkGen = null;
    private final ExecutorService generatorPool = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()),
        r -> { Thread t = new Thread(r, "orepoc-generator"); t.setDaemon(true); return t; }
    );
    private volatile boolean generating = false;
    private volatile int generationId = 0; // Incremented on seed/mode change to invalidate stale generations

    // Track all positions where we've applied ore block overrides (for mode switching revert)
    private final Set<BlockPos> appliedPositions = new HashSet<>();
    private final ReadWriteLock appliedLock = new ReentrantReadWriteLock();

    // Store the original server block state before we override it, so we can restore on NONE mode
    private final Map<BlockPos, BlockState> originalServerStates = new HashMap<>();
    private final ReadWriteLock originalLock = new ReentrantReadWriteLock();

    private record OreConfigEntry(OreConfiguration config, int count, int minY, int maxY) {}

    private LocalWorldGenerator() {
        this.oreCache = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPos, Map<BlockPos, BlockState>> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }

    public void setSeed(long seed) {
        generationId++; // Invalidate any in-progress generation
        cacheLock.writeLock().lock();
        try {
            this.seed = seed; this.seedSet = true;
            oreCache.clear();
            lastGenChunkX = Integer.MIN_VALUE;
            lastGenChunkZ = Integer.MIN_VALUE;
            LOGGER.info("Seed set to {}", seed);
        } finally { cacheLock.writeLock().unlock(); }

        // Clear applied position tracking
        appliedLock.writeLock().lock();
        try { appliedPositions.clear(); } finally { appliedLock.writeLock().unlock(); }
        // Clear original state tracking
        originalLock.writeLock().lock();
        try { originalServerStates.clear(); } finally { originalLock.writeLock().unlock(); }

        // Only trigger local generation if mode is LOCAL
        if (mode == Mode.LOCAL) {
            triggerGenerationForCurrentPosition();
        }
        // For REMOTE mode: the mixin will fetch from remote server when chunks arrive
        // For NONE mode: do nothing (seed is stored but no ore display)
    }

    /**
     * Trigger ore generation for the 3×3 area around the player's current position.
     * Called from setSeed() and the command handler.
     */
    public void triggerGenerationForCurrentPosition() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int px = mc.player.blockPosition().getX() >> 4;
        int pz = mc.player.blockPosition().getZ() >> 4;
        this.playerChunkX = px;
        this.playerChunkZ = pz;
        triggerAreaGeneration(px, pz);
    }

    public void initOreConfigs(Level level) {
        List<OreConfigEntry> configs = new ArrayList<>();

        // ========== Overworld ores ==========
        configs.add(makeEntry(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 0, 128, 20, 17));
        configs.add(makeEntry(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, -32, 72, 10, 9));
        configs.add(makeEntry(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, -16, 64, 16, 10));
        configs.add(makeEntry(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, -64, 32, 4, 9));
        configs.add(makeEntry(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, -64, 16, 8, 8));
        configs.add(makeEntry(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, -64, 64, 2, 7));
        configs.add(makeEntry(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, -64, 16, 1, 8));
        configs.add(makeEntry(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, -16, 256, 1, 3));

        // ========== Nether ores ==========
        configs.add(makeEntry(Blocks.NETHER_GOLD_ORE, Blocks.NETHER_GOLD_ORE, 10, 117, 10, 9));
        configs.add(makeEntry(Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_QUARTZ_ORE, 10, 117, 16, 14));
        configs.add(makeEntry(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 8, 22, 2, 1));

        this.oreConfigs = configs;
        LOGGER.info("Initialized {} ore configs", configs.size());
    }

    private static OreConfigEntry makeEntry(Block stone, Block deep, int minY, int maxY, int count, int size) {
        List<TargetBlockState> targets = List.of(
            OreConfiguration.target(new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES), stone.defaultBlockState()),
            OreConfiguration.target(new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES), deep.defaultBlockState())
        );
        return new OreConfigEntry(new OreConfiguration(targets, size, 0.0F), count, minY, maxY);
    }

    public long getSeed() { return seed; }
    public boolean isSeedSet() { return seedSet; }
    public boolean isGenerating() { return generating; }
    public Mode getMode() { return mode; }

    public void setMode(Mode newMode) {
        if (this.mode == newMode) return;
        generationId++; // Invalidate any in-progress generation
        Mode oldMode = this.mode;
        this.mode = newMode;

        cacheLock.writeLock().lock();
        try { oreCache.clear(); } finally { cacheLock.writeLock().unlock(); }

        LOGGER.info("Mode switching: {} -> {}", oldMode, newMode);

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

        if (newMode == Mode.NONE) {
            // Restore original server block states (undo all our modifications)
            restoreOriginalServerStates();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "§7[OrePoC] §eMode: None — restored server blocks"));
            }
        } else if (seedSet) {
            // Switching to LOCAL or REMOTE — clear server ores in loaded chunks
            clearOresInLoadedChunks();
            // Also revert any tracked applied positions (belt-and-suspenders)
            revertAppliedOres();

            if (newMode == Mode.LOCAL) {
                triggerGenerationForCurrentPosition();
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "§7[OrePoC] §aMode: Local generation"));
                }
            } else if (newMode == Mode.REMOTE) {
                if (RemoteGenerator.INSTANCE.isAvailable()) {
                    triggerReapplyForLoadedChunks();
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                            "§7[OrePoC] §aMode: Remote server"));
                    }
                } else {
                    LOGGER.warn("Remote mode set but no server connected");
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                            "§7[OrePoC] §cMode: Remote — no server connected!"));
                    }
                }
            }
        } else {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "§7[OrePoC] §eMode changed, but no seed set. Use /orepoc set_seed <seed>"));
            }
        }
    }

    // ============================================================
    // Original server state tracking (for NONE mode restoration)
    // ============================================================

    /**
     * Save the original server block state before we override it.
     * Only saves once per position (first save wins).
     */
    public void saveOriginalState(BlockPos pos, BlockState serverState) {
        originalLock.writeLock().lock();
        try {
            originalServerStates.putIfAbsent(pos.immutable(), serverState);
        } finally { originalLock.writeLock().unlock(); }
    }

    /**
     * Restore all saved original server block states.
     * Called when switching to NONE mode.
     */
    private void restoreOriginalServerStates() {
        Map<BlockPos, BlockState> toRestore;
        originalLock.writeLock().lock();
        try {
            toRestore = new HashMap<>(originalServerStates);
            originalServerStates.clear();
        } finally { originalLock.writeLock().unlock(); }

        if (toRestore.isEmpty()) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null) return;

        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> entry : toRestore.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState originalState = entry.getValue();
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(pos);
            if (chunk == null) continue;

            BlockState current = chunk.getBlockState(pos);
            // Only restore if the position was modified (current differs from original)
            if (!current.equals(originalState)) {
                chunk.setBlockState(pos, originalState, 0);
                level.sendBlockUpdated(pos, current, originalState, 3);
                restored++;
            }
        }

        // Clear applied positions since we've restored originals
        appliedLock.writeLock().lock();
        try { appliedPositions.clear(); } finally { appliedLock.writeLock().unlock(); }

        LOGGER.info("Restored {} original server block states", restored);
    }

    public void clearCache() {
        cacheLock.writeLock().lock();
        try { oreCache.clear(); }
        finally { cacheLock.writeLock().unlock(); }
    }

    /**
     * Update the player's current chunk position from a received chunk.
     * Does NOT trigger generation (use triggerGenerationForCurrentPosition instead).
     * Called from the mixin when a chunk arrives.
     */
    public void updatePlayerChunk(int chunkX, int chunkZ) {
        this.playerChunkX = chunkX;
        this.playerChunkZ = chunkZ;
    }

    /**
     * Trigger background generation of the 3x3 area around the given center chunk.
     */
    private void triggerAreaGeneration(int centerX, int centerZ) {
        if (!seedSet) return;
        // Prevent duplicate concurrent generation for the same area
        if (generating && lastGenChunkX == centerX && lastGenChunkZ == centerZ) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        if (oreConfigs.isEmpty()) initOreConfigs(mc.level);

        lastGenChunkX = centerX;
        lastGenChunkZ = centerZ;

        // Show chat notification
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                "§7[OrePoC] §fGenerating 3×3 chunk ore data..."
            ));
        }

        generating = true;

        // Collect all 9 chunk positions within 3x3 area
        List<ChunkPos> areaChunks = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                areaChunks.add(new ChunkPos(centerX + dx, centerZ + dz));
            }
        }

        // Submit generation tasks in parallel
        CountDownLatch latch = new CountDownLatch(areaChunks.size());
        for (ChunkPos pos : areaChunks) {
            generatorPool.submit(() -> {
                try {
                    generateChunk(pos);
                } catch (Exception e) {
                    LOGGER.error("Failed to generate chunk {}: {}", pos, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion on background thread, then notify and apply to world
        final int capturedGenId = generationId; // Snapshot the generation ID at start
        generatorPool.submit(() -> {
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            generating = false;
            // If generation ID changed (seed/mode was changed), discard stale results
            if (generationId != capturedGenId) {
                LOGGER.info("Stale generation discarded (generationId changed)");
                return;
            }
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                applyGeneratedOresToWorld(centerX, centerZ);
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "§7[OrePoC] §a3×3 chunk ore data generation complete"
                    ));
                }
            });
        });
    }

    /**
     * Apply cached ore positions to the actual loaded world chunks (called on render thread).
     * This ensures blocks are replaced immediately after generation, not just on chunk reload.
     * Also clears fake ores (anti-xray) that the server sent but we didn't predict.
     */
    public void applyGeneratedOresToWorld(int centerX, int centerZ) {
        // Only apply ores if still in LOCAL mode
        if (mode != Mode.LOCAL) {
            LOGGER.info("Skipping apply (mode is {})", mode);
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null) return;

        int appliedCount = 0;
        int clearedCount = 0;
        int totalOres = 0;

        // Collect all positions we apply for tracking
        List<BlockPos> newApplications = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                ChunkPos chunkPos = new ChunkPos(cx, cz);

                cacheLock.readLock().lock();
                Map<BlockPos, BlockState> ores = oreCache.get(chunkPos);
                cacheLock.readLock().unlock();
                if (ores == null || ores.isEmpty()) {
                    LOGGER.info("No ores in cache for chunk {},{}", cx, cz);
                    continue;
                }
                totalOres += ores.size();

                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) {
                    LOGGER.info("Chunk {},{} is null (not loaded)", cx, cz);
                    continue;
                }

                // Build a set of predicted ore positions for fast lookup
                Set<BlockPos> predictedPositions = ores.keySet();

                // Step 1: Apply our predicted ores (replace server blocks with local ore)
                for (Map.Entry<BlockPos, BlockState> entry : ores.entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockState localOre = entry.getValue();

                    if ((pos.getX() >> 4) != cx || (pos.getZ() >> 4) != cz) continue;

                    BlockState serverState = chunk.getBlockState(pos);
                    // Skip if server shows air or fluid (anti-xray or natural)
                    if (serverState.isAir() || serverState.liquid()) continue;

                    // Save original before modifying
                    saveOriginalState(pos, serverState);

                    chunk.setBlockState(pos, localOre, 0);
                    level.sendBlockUpdated(pos, serverState, localOre, 3);
                    appliedCount++;
                    newApplications.add(pos);
                }

                // Step 2: Clear fake ores (server ore blocks not in our prediction)
                int minSectionY = level.getMinY() >> 4;
                int maxSectionY = (level.getMinY() + level.getHeight()) >> 4;
                for (int sectionY = minSectionY; sectionY < maxSectionY; sectionY++) {
                    int sectionIndex = sectionY - minSectionY;
                    net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
                    if (section == null || section.hasOnlyAir()) continue;

                    int baseY = sectionY << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 0; y < 16; y++) {
                                BlockPos pos = new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z);
                                // Skip if this position is in our prediction
                                if (predictedPositions.contains(pos)) continue;

                                BlockState state = section.getBlockState(x, y, z);
                                // Check if this is an ore block (potential fake ore from anti-xray)
                                if (isOreBlock(state)) {
                                    // Save original before modifying
                                    saveOriginalState(pos, state);

                                    section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                                    level.sendBlockUpdated(pos, state, Blocks.STONE.defaultBlockState(), 3);
                                    clearedCount++;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Track applied positions for future reversion
        markApplied(newApplications);

        LOGGER.info("Applied {} ores + cleared {} fake ores for area [{},{}]",
            appliedCount, clearedCount, centerX, centerZ);
    }

    /** Check if a block state is one of the known ore types (potential anti-xray fake ore). */
    private static boolean isOreBlock(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.COAL_ORES)
            || state.is(net.minecraft.tags.BlockTags.IRON_ORES)
            || state.is(net.minecraft.tags.BlockTags.COPPER_ORES)
            || state.is(net.minecraft.tags.BlockTags.GOLD_ORES)
            || state.is(net.minecraft.tags.BlockTags.REDSTONE_ORES)
            || state.is(net.minecraft.tags.BlockTags.LAPIS_ORES)
            || state.is(net.minecraft.tags.BlockTags.DIAMOND_ORES)
            || state.is(net.minecraft.tags.BlockTags.EMERALD_ORES)
            // Nether ores
            || state.is(Blocks.NETHER_GOLD_ORE)
            || state.is(Blocks.NETHER_QUARTZ_ORE)
            || state.is(Blocks.ANCIENT_DEBRIS)
            // Raw ore blocks (large ore veins)
            || state.is(Blocks.RAW_IRON_BLOCK)
            || state.is(Blocks.RAW_COPPER_BLOCK)
            || state.is(Blocks.RAW_GOLD_BLOCK)
            // Additional blocks that may be used by anti-xray or custom ore gen
            || state.is(Blocks.MOSSY_COBBLESTONE)
            || state.is(Blocks.AMETHYST_BLOCK)
            || state.is(Blocks.GILDED_BLACKSTONE);
    }

    public Map<BlockPos, BlockState> getChunkOres(int chunkX, int chunkZ) {
        return getChunkOres(chunkX, chunkZ, null);
    }

    public Map<BlockPos, BlockState> getChunkOres(int chunkX, int chunkZ, String dimension) {
        if (!seedSet || mode == Mode.NONE) return null;

        // If REMOTE mode, delegate to RemoteGenerator with dimension info
        if (mode == Mode.REMOTE) {
            if (RemoteGenerator.INSTANCE.isAvailable()) {
                // Auto-detect dimension from current level if not provided
                if (dimension == null) {
                    dimension = resolveCurrentDimension();
                }
                return RemoteGenerator.INSTANCE.getChunkOres(dimension, chunkX, chunkZ);
            } else {
                // Remote generator not available but mode is REMOTE
                LOGGER.warn("Remote mode but server unavailable for chunk [{},{}]", chunkX, chunkZ);
                return null;
            }
        }

        // Only serve chunks within the 3x3 player area
        if (playerChunkX != Integer.MIN_VALUE) {
            int dx = Math.abs(chunkX - playerChunkX);
            int dz = Math.abs(chunkZ - playerChunkZ);
            if (dx > RADIUS || dz > RADIUS) {
                return null; // Out of range, skip
            }
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        cacheLock.readLock().lock();
        Map<BlockPos, BlockState> r = oreCache.get(chunkPos);
        cacheLock.readLock().unlock();
        if (r == null) {
            // Blocking fallback: generate synchronously if not in cache
            // (shouldn't normally happen since we pre-generate)
            generateChunk(chunkPos);
            cacheLock.readLock().lock();
            r = oreCache.get(chunkPos);
            cacheLock.readLock().unlock();
        }
        return r;
    }

    /**
     * Resolve the current dimension's resource location string.
     */
    private static String resolveCurrentDimension() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.dimension().identifier().toString();
        }
        return "minecraft:overworld";
    }

    public BlockState getOreAt(BlockPos pos) {
        Map<BlockPos, BlockState> ores = getChunkOres(pos.getX() >> 4, pos.getZ() >> 4);
        return ores != null ? ores.get(pos) : null;
    }

    private void generateChunk(ChunkPos chunkPos) {
        cacheLock.writeLock().lock();
        try {
            if (oreCache.containsKey(chunkPos)) return;

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            Level level = mc.level;
            if (level == null) return;

            if (oreConfigs.isEmpty()) initOreConfigs(level);

            int minY = level.dimensionType().minY();
            int maxY = minY + level.dimensionType().height();
            OreGenLevel genLevel = new OreGenLevel(chunkPos.x(), chunkPos.z(), minY, maxY, seed, level);

            // Initialize dummy chunk generator (needed by Feature.place() even if not used by OreFeature)
            if (dummyChunkGen == null) {
                dummyChunkGen = new ChunkGenerator(null) {
                    @Override public com.mojang.serialization.MapCodec<? extends ChunkGenerator> codec() {
                        return new com.mojang.serialization.MapCodec<ChunkGenerator>() {
                            @Override public <T> com.mojang.serialization.DataResult<ChunkGenerator> decode(com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.MapLike<T> input) {
                                return com.mojang.serialization.DataResult.error(() -> "DummyChunkGenerator does not support decoding");
                            }
                            @Override public <T> com.mojang.serialization.RecordBuilder<T> encode(ChunkGenerator input, com.mojang.serialization.DynamicOps<T> ops, com.mojang.serialization.RecordBuilder<T> prefix) { return prefix; }
                            @Override public <T> java.util.stream.Stream<T> keys(com.mojang.serialization.DynamicOps<T> ops) { return java.util.stream.Stream.empty(); }
                            @Override public <T> com.mojang.serialization.KeyCompressor<T> compressor(com.mojang.serialization.DynamicOps<T> ops) { return null; }
                            @Override public String toString() { return "MapCodec[DummyChunkGenerator]"; }
                        };
                    }
                    @Override public ChunkGeneratorStructureState createState(HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> p, RandomState r, long s) { return null; }
                    @Override public void createStructures(RegistryAccess r, ChunkGeneratorStructureState s, StructureManager m, ChunkAccess c, StructureTemplateManager t, ResourceKey<Level> k) {}
                    @Override public void applyBiomeDecoration(WorldGenLevel l, ChunkAccess c, StructureManager s) {}
                    @Override public void applyCarvers(net.minecraft.server.level.WorldGenRegion r, long l, net.minecraft.world.level.levelgen.RandomState rs, net.minecraft.world.level.biome.BiomeManager bm, StructureManager sm, ChunkAccess ca) {}
                    @Override public void buildSurface(net.minecraft.server.level.WorldGenRegion r, StructureManager sm, net.minecraft.world.level.levelgen.RandomState rs, ChunkAccess ca) {}
                    @Override public void spawnOriginalMobs(net.minecraft.server.level.WorldGenRegion r) {}
                    @Override public int getSeaLevel() { return 63; }
                    @Override public int getMinY() { return -64; }
                    @Override public int getGenDepth() { return 384; }
                    @Override public int getBaseHeight(int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types t, net.minecraft.world.level.LevelHeightAccessor a, net.minecraft.world.level.levelgen.RandomState rs) { return 0; }
                    @Override public net.minecraft.world.level.NoiseColumn getBaseColumn(int x, int z, net.minecraft.world.level.LevelHeightAccessor a, net.minecraft.world.level.levelgen.RandomState rs) { return null; }
                    @Override public void addDebugScreenInfo(List<String> l, net.minecraft.world.level.levelgen.RandomState r, BlockPos p) {}
                    @Override public CompletableFuture<ChunkAccess> fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender b, net.minecraft.world.level.levelgen.RandomState rs, StructureManager sm, ChunkAccess ca) { return CompletableFuture.completedFuture(ca); }
                };
            }

            // Create oreRandom using XoroshiroRandomSource (same as vanilla overworld).
            // This exactly replicates RandomState.oreRandom() without needing registries.
            net.minecraft.world.level.levelgen.XoroshiroRandomSource baseRandom =
                new net.minecraft.world.level.levelgen.XoroshiroRandomSource(seed);
            net.minecraft.world.level.levelgen.PositionalRandomFactory oreRandom = baseRandom.forkPositional();

            for (OreConfigEntry entry : oreConfigs) {
                int cx = chunkPos.x(), cz = chunkPos.z();
                // Per-chunk random using vanilla-style positional random
                RandomSource chunkRandom = oreRandom.at(cx * 16 + 8, 0, cz * 16 + 8);
                // Use fixed count for ancient debris (no ±1 variation), else use vanilla variation
                int veinCount;
                if (entry.count <= 2 && entry.config.size <= 1) {
                    // Ancient debris / single-block ores: use exact count
                    veinCount = entry.count;
                } else {
                    veinCount = Math.max(1, entry.count + (chunkRandom.nextInt(3) - 1));
                }

                for (int i = 0; i < veinCount; i++) {
                    int ox = cx * 16 + chunkRandom.nextInt(16);
                    int oz = cz * 16 + chunkRandom.nextInt(16);
                    int oy = entry.minY + chunkRandom.nextInt(entry.maxY - entry.minY + 1);
                    // Per-vein random using vanilla-style positional random
                    RandomSource fr = oreRandom.at(ox, oy, oz);
                    placeOreFeature(genLevel, entry.config, fr, ox, oy, oz);
                }
            }

            // Read results from the LevelChunk sections (where Feature.ORE.place() wrote via BulkSectionAccess)
            Map<BlockPos, BlockState> result = new LinkedHashMap<>();
            LevelChunk lc = genLevel.getOrCreateChunk(chunkPos.x(), chunkPos.z());
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    for (int y = 0; y < maxY - minY; y++) {
                        BlockPos p = new BlockPos(chunkPos.x() * 16 + x, minY + y, chunkPos.z() * 16 + z);
                        BlockState s = lc.getBlockState(p);
                        if (!s.isAir() && !s.is(Blocks.STONE) && !s.is(Blocks.DEEPSLATE)) {
                            result.put(p, s);
                        }
                    }
            oreCache.put(chunkPos, result);
            LOGGER.info("Generated {} ore blocks for chunk {},{}", result.size(), chunkPos.x(), chunkPos.z());
        } finally { cacheLock.writeLock().unlock(); }
    }

    private static void placeOreFeature(OreGenLevel genLevel, OreConfiguration config, RandomSource random, int x, int y, int z) {
        Feature.ORE.place(config, genLevel, null, random, new BlockPos(x, y, z));
    }

    private static long mixSeed(long seed, int cx, int cz, int salt) {
        long h = seed;
        h = h * 6364136223846793005L + 1442695040888963407L;
        h += cx; h = h * 6364136223846793005L + 1442695040888963407L;
        h += cz; h = h * 6364136223846793005L + 1442695040888963407L;
        h += salt;
        return h * 6364136223846793005L + 1442695040888963407L;
    }

    // ============================================================
    // Mode-switching helpers
    // ============================================================

    /**
     * Record that we've applied ore block overrides at the given positions.
     * Used by revertAppliedOres() when switching to NONE or between modes.
     */
    public void markApplied(Collection<BlockPos> positions) {
        appliedLock.writeLock().lock();
        try { appliedPositions.addAll(positions); } finally { appliedLock.writeLock().unlock(); }
    }

    /**
     * Record a single applied position.
     */
    public void markApplied(BlockPos pos) {
        appliedLock.writeLock().lock();
        try { appliedPositions.add(pos); } finally { appliedLock.writeLock().unlock(); }
    }

    /**
     * Revert all previously applied ore blocks back to the base stone/deepslate.
     * Called when switching to NONE mode or between LOCAL/REMOTE.
     */
    private void revertAppliedOres() {
        Set<BlockPos> toRevert;
        appliedLock.writeLock().lock();
        try {
            toRevert = new HashSet<>(appliedPositions);
            appliedPositions.clear();
        } finally { appliedLock.writeLock().unlock(); }

        if (toRevert.isEmpty()) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null) return;

        int reverted = 0;
        for (BlockPos pos : toRevert) {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(pos);
            if (chunk == null) continue;

            BlockState current = chunk.getBlockState(pos);
            // Only revert if the current block is an ore (i.e., we set it here)
            if (isOreBlock(current)) {
                // Save original state before reverting
                saveOriginalState(pos, current);

                BlockState base = pos.getY() < 0
                    ? Blocks.DEEPSLATE.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
                chunk.setBlockState(pos, base, 0);
                level.sendBlockUpdated(pos, current, base, 3);
                reverted++;
            }
        }
        LOGGER.info("Reverted {} ore blocks", reverted);
    }

    /**
     * Scan ALL currently-loaded chunks (via ClientChunkCache internal storage reflection)
     * and replace any ore blocks with stone/deepslate.
     * Ensures even blocks NOT tracked via markApplied() get cleared.
     * Called on the render thread from setMode() for LOCAL/REMOTE modes only.
     */
    private void clearOresInLoadedChunks() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null) return;

        try {
            // Access ClientChunkCache.Storage via reflection to iterate ALL loaded chunks
            Object cache = level.getChunkSource();
            java.lang.reflect.Field storageField = cache.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(cache);

            // Get chunks array from Storage
            java.lang.reflect.Field chunksField = storage.getClass().getDeclaredField("chunks");
            chunksField.setAccessible(true);
            java.util.concurrent.atomic.AtomicReferenceArray<net.minecraft.world.level.chunk.LevelChunk> chunks =
                (java.util.concurrent.atomic.AtomicReferenceArray<net.minecraft.world.level.chunk.LevelChunk>) chunksField.get(storage);

            // Get view center and radius from Storage
            java.lang.reflect.Field radiusField = storage.getClass().getDeclaredField("chunkRadius");
            radiusField.setAccessible(true);
            int radius = radiusField.getInt(storage);

            java.lang.reflect.Field viewCenterXField = storage.getClass().getDeclaredField("viewCenterX");
            viewCenterXField.setAccessible(true);
            int viewCX = viewCenterXField.getInt(storage);

            java.lang.reflect.Field viewCenterZField = storage.getClass().getDeclaredField("viewCenterZ");
            viewCenterZField.setAccessible(true);
            int viewCZ = viewCenterZField.getInt(storage);

            int cleared = 0;
            int arraySize = radius * 2 + 1;
            for (int i = 0; i < chunks.length(); i++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = chunks.get(i);
                if (chunk == null) continue;
                if (chunk.isEmpty()) continue;

                // Calculate chunk coordinates from index (same formula as Storage.getIndex)
                int dz = i / arraySize - radius;
                int dx = i % arraySize - radius;
                int cx = viewCX + dx;
                int cz = viewCZ + dz;

            int minSectionY = level.getMinY() >> 4;
            int maxSectionY = (level.getMinY() + level.getHeight()) >> 4;
            for (int sectionY = minSectionY; sectionY < maxSectionY; sectionY++) {
                int sectionIndex = sectionY - minSectionY;
                net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section == null || section.hasOnlyAir()) continue;

                int baseY = sectionY << 4;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            BlockPos pos = new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z);
                            BlockState state = section.getBlockState(x, y, z);
                            if (isOreBlock(state)) {
                                // Save original before modifying
                                saveOriginalState(pos, state);

                                BlockState base = pos.getY() < 0
                                    ? Blocks.DEEPSLATE.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState();
                                section.setBlockState(x, y, z, base);
                                level.sendBlockUpdated(pos, state, base, 3);
                                cleared++;
                            }
                        }
                    }
                }
            }
        }
            LOGGER.info("Cleared {} ore blocks from all loaded chunks for mode switch", cleared);
        } catch (Exception e) {
            LOGGER.error("Failed to iterate loaded chunks via reflection: {}", e.getMessage());
        }
    }

    /**
     * Trigger re-application of ore blocks for all loaded chunks in the current 3×3 area.
     * Used when switching to REMOTE mode to immediately fetch and apply remote ores.
     * RemoteGenerator.getChunkOres() submits async HTTP requests and returns null initially.
     * This method triggers those requests, then waits in background and applies when data arrives.
     */
    private void triggerReapplyForLoadedChunks() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null || playerChunkX == Integer.MIN_VALUE) return;

        // Count how many chunks we need to fetch
        int fetchCount = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) continue;
                // Calling getChunkOres in REMOTE mode triggers async HTTP request and returns null
                Map<BlockPos, BlockState> ores = getChunkOres(cx, cz);
                if (ores == null) fetchCount++;
            }
        }

        if (fetchCount == 0) {
            LOGGER.info("All remote chunks already cached, nothing to fetch");
            return;
        }

        // Show feedback
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                "§7[OrePoC] §fFetching ore data from remote server..."));
        }

        // Wait for pending requests on background thread, then apply on render thread
        generatorPool.submit(() -> {
            RemoteGenerator.INSTANCE.waitForPending();
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                applyRemoteOresToWorld();
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                        "§7[OrePoC] §aRemote ore data loaded"));
                }
            });
        });
    }

    /**
     * Apply remote ore data that has been fetched into the RemoteGenerator cache.
     * Called on the render thread after pending HTTP requests complete.
     */
    private void applyRemoteOresToWorld() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null || playerChunkX == Integer.MIN_VALUE) return;

        List<BlockPos> newApplications = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) continue;

                // Now getChunkOres returns cached data (async requests completed)
                Map<BlockPos, BlockState> ores = getChunkOres(cx, cz);
                if (ores == null || ores.isEmpty()) continue;

                for (Map.Entry<BlockPos, BlockState> entry : ores.entrySet()) {
                    BlockPos pos = entry.getKey();
                    if ((pos.getX() >> 4) != cx || (pos.getZ() >> 4) != cz) continue;

                    BlockState serverState = chunk.getBlockState(pos);
                    if (serverState.isAir() || serverState.liquid()) continue;

                    // Save original state before applying remote ore
                    saveOriginalState(pos, serverState);

                    chunk.setBlockState(pos, entry.getValue(), 0);
                    level.sendBlockUpdated(pos, serverState, entry.getValue(), 3);
                    newApplications.add(pos);
                }
            }
        }

        markApplied(newApplications);
        LOGGER.info("Applied {} remote ore blocks", newApplications.size());
    }
}
