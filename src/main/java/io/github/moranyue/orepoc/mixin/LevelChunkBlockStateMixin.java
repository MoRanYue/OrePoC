package io.github.moranyue.orepoc.mixin;

import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin that intercepts LevelChunk.getBlockState() to dynamically replace
 * server ore blocks with stone when in REMOTE mode (unless we have a matching
 * prediction from the OrePoC server).
 * <p>
 * This handles ALL loaded chunks, including those loaded before a mode switch.
 */
@Mixin(LevelChunk.class)
public class LevelChunkBlockStateMixin {

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void orepoc$overrideBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        // Only apply in REMOTE mode with seed set
        LocalWorldGenerator gen = LocalWorldGenerator.INSTANCE;
        if (gen.getMode() != LocalWorldGenerator.Mode.REMOTE) return;
        if (!gen.isSeedSet()) return;

        BlockState original = cir.getReturnValue();

        // If the server says the block is air (e.g. after being mined),
        // don't override — respect the mining and prevent ghost blocks.
        if (original.isAir()) return;

        // Step 1: Always check for a remote prediction first.
        // This works even after clearOresInLoadedChunks() replaced ore with stone.
        BlockState remoteOre = gen.getOreAt(pos);
        if (remoteOre != null && !remoteOre.isAir()) {
            cir.setReturnValue(remoteOre);
            return;
        }

        // Step 2: No prediction, but the chunk has an ore block — hide it (show dimension-appropriate base block).
        if (isOreBlock(original)) {
            cir.setReturnValue(LocalWorldGenerator.getBaseBlockForDimension(pos));
        }
        // Step 3: Otherwise, leave the original block state unchanged.
    }

    /** Check if a block state is one of the known ore types (same list as in ChunkDataMixin). */
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
            || state.is(Blocks.GILDED_BLACKSTONE)
            || state.is(Blocks.BUDDING_AMETHYST);
    }
}
