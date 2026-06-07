# WorldGenLevel Implementation Plan

## Goal
Create a proper `WorldGenLevel` implementation that wraps a simple 3D block array, enabling use of `Feature.ORE.place()` for vanilla-accurate ore generation.

## Interface Hierarchy

```
WorldGenLevel
  extends ServerLevelAccessor
    extends LevelAccessor
      extends CommonLevelAccessor
        extends BlockGetter (many methods)
        extends EntityGetter (many methods)
        extends LevelWriter
          - setBlock(BlockPos, BlockState, int, int)
          - setBlock(BlockPos, BlockState, int)  
          - removeBlock(BlockPos, boolean)
          - destroyBlock(BlockPos, boolean, Entity, int)
          - destroyBlock(BlockPos, boolean)
      extends ScheduledTickAccess<Block>
        - scheduleBlockTick(BlockPos, Block, int, TickPriority)
        - scheduleFluidTick(BlockPos, Fluid, int, TickPriority)
        - getBlockTicks()
        - getFluidTicks()
        - getFreeTicks()
  + getSeed()
  + ensureCanWrite(BlockPos) [default]
  + setCurrentlyGenerating(Supplier<String>) [default]
```

## Implementation Strategy

1. **Block storage**: Use `BlockState[][][]` array sized [16][16][height] or use `LevelChunkSection[]`
2. **Core methods**: `getBlockState`, `setBlock`, `getSeed`
3. **Delegate methods**: Forward to the underlying `ClientLevel` when possible
4. **No-op/return defaults**: For most entity/sound/particle methods
5. **Empty collections**: For entity lists, player lists

## ChunkGenerator Access

The `ChunkGenerator` is needed by `Feature.ORE.place()`. Options:
- A: Get from `Minecraft.getInstance().level.getChunkSource()` → `ChunkSource` → use `chunkSource.chunkMap.generator` or similar
- B: Store the generator reference when the chunk is loaded in `ChunkDataMixin`

For Option B, the `ChunkDataMixin` already has access to `Level.getChunk()` which gives us a `LevelChunk` that knows its `Level`, which has `ChunkSource`. We just need to pass this to the generator.

## Getting Ore PlacedFeatures

From `Minecraft.getInstance().level.registryAccess()`:
1. Get `Registry<PlacedFeature>`
2. Filter entries where the feature type is `Feature.ORE`
3. This gives us the exact same ore configurations Minecraft uses

## Data Flow

```
ChunkDataMixin (on chunk load)
  → creates OreGenLevel(chunkX, chunkZ, minY, maxY, seed)
  → gets PlacedFeatures from registry
  → for each ore PlacedFeature:
    → get its placement modifiers
    → determine vein positions (count, height range)
    → for each vein:
      → create RandomSource with seed + chunk pos
      → call Feature.ORE.place(config, oreGenLevel, chunkGenerator, random, pos)
  → get only the ore positions from OreGenLevel
  → compare with server blocks (skip air)
  → replace with local ore block
```
