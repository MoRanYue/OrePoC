package io.github.moranyue.orepoc.generator;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class OreGenLevel implements WorldGenLevel {

    private final int chunkX, chunkZ, minY, maxY, height;
    private final long seed;
    private final Level level;
    private final BlockState[][][] blocks;
    private final Long2ObjectOpenHashMap<LevelChunk> nearbyChunks = new Long2ObjectOpenHashMap<>();
    private final PalettedContainerFactory factory;

    public OreGenLevel(int cx, int cz, int minY, int maxY, long seed, Level level) {
        this.chunkX = cx; this.chunkZ = cz;
        this.minY = minY; this.maxY = maxY; this.height = maxY - minY;
        this.seed = seed; this.level = level;
        this.blocks = new BlockState[16][16][height];
        this.factory = PalettedContainerFactory.create(level.registryAccess());

        // Fill block array with replaceable base blocks so TagMatchTest can match.
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < height; y++) {
                    int worldY = minY + y;
                    blocks[x][z][y] = worldY < 0
                        ? Blocks.DEEPSLATE.defaultBlockState()
                        : Blocks.STONE.defaultBlockState();
                }
    }

    /**
     * Get or create a LevelChunk for the given chunk coordinates.
     * BulkSectionAccess needs valid chunks with sections for any position
     * the ore vein might touch (up to 2 chunks away).
     */
    public LevelChunk getOrCreateChunk(int x, int z) {
        long key = ChunkPos.pack(x, z);
        LevelChunk existing = nearbyChunks.get(key);
        if (existing != null) return existing;

        ChunkPos pos = new ChunkPos(x, z);
        int sectionCount = (maxY - minY) >> 4;
        LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = new LevelChunkSection(factory);
            // Fill sections with stone/deepslate so TagMatchTest works
            int sectionBaseY = minY + (i << 4);
            for (int sx = 0; sx < 16; sx++)
                for (int sz = 0; sz < 16; sz++)
                    for (int sy = 0; sy < 16; sy++) {
                        int worldY = sectionBaseY + sy;
                        sections[i].setBlockState(sx, sy, sz,
                            worldY < 0
                                ? Blocks.DEEPSLATE.defaultBlockState()
                                : Blocks.STONE.defaultBlockState());
                    }
        }

        LevelChunk chunk = new LevelChunk(
            level, pos, UpgradeData.EMPTY,
            new LevelChunkTicks<>(), new LevelChunkTicks<>(),
            0L, sections, null, null
        );
        nearbyChunks.put(key, chunk);
        return chunk;
    }

    // WorldGenLevel
    @Override public long getSeed() { return seed; }
    @Override public boolean ensureCanWrite(BlockPos pos) { return true; }
    @Override public void setCurrentlyGenerating(Supplier<String> s) {}

    // ServerLevelAccessor
    @Override public ServerLevel getLevel() { return null; }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(level.getDifficulty(), level.getLevelData().getGameTime(), 0, 0.0F); }
    @Override public void addFreshEntityWithPassengers(Entity e) {}

    // LevelAccessor
    @Override public long nextSubTickCount() { return 0; }
    @Override public LevelData getLevelData() { return level.getLevelData(); }
    @Override public MinecraftServer getServer() { return null; }
    @Override public ChunkSource getChunkSource() { return level.getChunkSource(); }
    @Override public boolean hasChunk(int x, int z) { return Math.abs(x - chunkX) <= 2 && Math.abs(z - chunkZ) <= 2; }
    @Override public RandomSource getRandom() { return RandomSource.create(seed); }
    @Override public void playSound(@Nullable Entity e, BlockPos p, SoundEvent s, SoundSource src, float v, float p2) {}
    @Override public void addParticle(ParticleOptions o, double x, double y, double z, double vx, double vy, double vz) {}
    @Override public void levelEvent(@Nullable Entity e, int type, BlockPos pos, int data) {}
    @Override public void levelEvent(int type, BlockPos pos, int data) {}
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context ctx) {}
    @Override public void gameEvent(@Nullable Entity e, Holder<GameEvent> event, Vec3 pos) {}
    @Override public void gameEvent(@Nullable Entity e, Holder<GameEvent> event, BlockPos pos) {}
    @Override public void gameEvent(Holder<GameEvent> event, BlockPos pos, GameEvent.Context ctx) {}
    @Override public void gameEvent(ResourceKey<GameEvent> key, BlockPos pos, GameEvent.Context ctx) {}

    // ScheduledTickAccess
    @Override public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) { return new ScheduledTick<>(type, pos, (long)delay, priority, 0L); }
    @Override public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) { return new ScheduledTick<>(type, pos, (long)delay, 0L); }

    // TickAccess
    @Override public LevelTickAccess<Block> getBlockTicks() { return new LevelTickAccess<Block>() {
        @Override public void schedule(ScheduledTick<Block> tick) {}
        @Override public boolean willTickThisTick(BlockPos pos, Block type) { return false; }
        @Override public boolean hasScheduledTick(BlockPos pos, Block type) { return false; }
        @Override public int count() { return 0; }
    };}
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return new LevelTickAccess<Fluid>() {
        @Override public void schedule(ScheduledTick<Fluid> tick) {}
        @Override public boolean willTickThisTick(BlockPos pos, Fluid type) { return false; }
        @Override public boolean hasScheduledTick(BlockPos pos, Fluid type) { return false; }
        @Override public int count() { return 0; }
    };}

    // BlockGetter - reads from our block array for our primary chunk, falls back to stone for neighbors
    @Override public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX() - chunkX * 16, z = pos.getZ() - chunkZ * 16, y = pos.getY() - minY;
        if (x >= 0 && x < 16 && z >= 0 && z < 16 && y >= 0 && y < height) {
            BlockState s = blocks[x][z][y];
            return s != null ? s : Blocks.AIR.defaultBlockState();
        }
        // For positions outside our primary chunk, return stone/deepslate for TagMatchTest
        // (used by OreFeature for neighbor chunk blocks that we don't track)
        return pos.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }
    @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public FluidState getFluidState(BlockPos pos) { return Blocks.AIR.defaultBlockState().getFluidState(); }
    @Override public int getHeight(Heightmap.Types t, int x, int z) { return level.getHeight(t, x, z); }
    @Override public int getMaxLocalRawBrightness(BlockPos pos) { return 15; }
    @Override public int getMaxLocalRawBrightness(BlockPos pos, int amb) { return 15; }
    @Override public int getSkyDarken() { return 0; }
    @Override public BiomeManager getBiomeManager() { return level.getBiomeManager(); }
    @Override public Holder<Biome> getBiome(BlockPos pos) { return level.getBiome(pos); }
    @Override public WorldBorder getWorldBorder() { return level.getWorldBorder(); }
    @Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> p) { return p.test(getBlockState(pos)); }
    @Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> p) { return p.test(getFluidState(pos)); }
    @Override public int getSeaLevel() { return level.getSeaLevel(); }

    // LevelWriter - tracks changes in our primary chunk's block array
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int maxDepth) { return setBlock(pos, state, flags); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) {
        int x = pos.getX() - chunkX * 16, z = pos.getZ() - chunkZ * 16, y = pos.getY() - minY;
        if (x >= 0 && x < 16 && z >= 0 && z < 16 && y >= 0 && y < height) {
            blocks[x][z][y] = state;
            return true;
        }
        return false;
    }
    @Override public boolean removeBlock(BlockPos pos, boolean move) { return false; }
    @Override public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity e, int depth) { return false; }
    @Override public boolean destroyBlock(BlockPos pos, boolean drop) { return false; }
    @Override public boolean addFreshEntity(Entity e) { return false; }

    // LevelHeightAccessor
    @Override public int getMinY() { return minY; }
    @Override public int getHeight() { return height; }
    @Override public boolean isOutsideBuildHeight(BlockPos pos) { return pos.getY() < minY || pos.getY() >= maxY; }
    @Override public boolean isOutsideBuildHeight(int y) { return y < minY || y >= maxY; }

    // EntityGetter
    @Override public List<Entity> getEntities(@Nullable Entity e, AABB a, @Nullable Predicate<? super Entity> p) { return Collections.emptyList(); }
    @Override public <E extends Entity> List<E> getEntities(EntityTypeTest<Entity, E> t, AABB a, Predicate<? super E> p) { return Collections.emptyList(); }
    @Override public List<? extends Player> players() { return Collections.emptyList(); }

    // BlockAndLightGetter
    @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }

    // LevelReader - returns proper LevelChunks for nearby positions
    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus st, boolean b) {
        // BulkSectionAccess needs chunks for any position the ore vein touches
        // Generate on-the-fly for any position within a 3-chunk radius
        if (Math.abs(x - chunkX) <= 2 && Math.abs(z - chunkZ) <= 2) {
            return getOrCreateChunk(x, z);
        }
        return null;
    }
    @Override public ChunkAccess getChunk(int x, int z) {
        return getChunk(x, z, ChunkStatus.EMPTY, false);
    }
    @Override public boolean isClientSide() { return false; }
    @Override public DimensionType dimensionType() { return level.dimensionType(); }
    @Override public RegistryAccess registryAccess() { return level.registryAccess(); }
    @Override public FeatureFlagSet enabledFeatures() { return level.enabledFeatures(); }
    @Override public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() { return net.minecraft.world.attribute.EnvironmentAttributeReader.EMPTY; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return level.getUncachedNoiseBiome(x, y, z); }

    // Misc
    @Override public boolean hasBiomes() { return true; }
}
