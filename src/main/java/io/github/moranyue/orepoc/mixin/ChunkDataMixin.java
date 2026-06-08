package io.github.moranyue.orepoc.mixin;

import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Mixin that modifies chunk block states immediately after the client receives
 * a chunk from the server, and triggers re-render via sendBlockUpdated.
 * Only iterates over pre-computed ore positions.
 */
@Mixin(ClientPacketListener.class)
public class ChunkDataMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void orepoc$onChunkLoad(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!LocalWorldGenerator.INSTANCE.isSeedSet()) {
            return;
        }

        ClientPacketListener self = (ClientPacketListener) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }

        int chunkX = packet.getX();
        int chunkZ = packet.getZ();

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        // Get pre-computed ore positions from the generator - fast, no iteration needed
        Map<BlockPos, BlockState> orePositions = LocalWorldGenerator.INSTANCE.getChunkOres(chunkX, chunkZ);
        if (orePositions == null || orePositions.isEmpty()) {
            // In REMOTE mode: still proceed to clear server ore blocks even without predictions yet.
            // Server ores will be replaced with stone; remote ores will be applied when fetched later.
            if (LocalWorldGenerator.INSTANCE.getMode() != LocalWorldGenerator.Mode.REMOTE) {
                return;
            }
            // Use empty map so the clear step below clears ALL server ore blocks
            orePositions = java.util.Collections.emptyMap();
        }

        // Notify generator of player's current chunk position to trigger 3x3 pre-generation
        LocalWorldGenerator.INSTANCE.updatePlayerChunk(chunkX, chunkZ);

        int replacedCount = 0;
        java.util.List<BlockPos> newApplications = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : orePositions.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState localOre = entry.getValue();

            // Skip if position is not in this chunk
            if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) {
                continue;
            }

            BlockState serverState = chunk.getBlockState(pos);

            // If server has air, water, or lava at this ore position, don't override
            // (respect anti-xray and preserve fluids)
            if (serverState.isAir() || serverState.liquid()) {
                continue;
            }

            // Save original server state before overriding
            LocalWorldGenerator.INSTANCE.saveOriginalState(pos, serverState);

            // Replace block state in chunk storage
            chunk.setBlockState(pos, localOre, 0);
            // Trigger client-side chunk re-render (required for Sodium compatibility)
            level.sendBlockUpdated(pos, serverState, localOre, 3);
            replacedCount++;
            newApplications.add(pos);
        }
        // Track applied positions for mode-switching revertibility
        LocalWorldGenerator.INSTANCE.markApplied(newApplications);

        // Clear fake ores (anti-xray): any ore block the server sent that we didn't predict
        int clearedCount = 0;
        java.util.Set<BlockPos> predictedPositions = orePositions.keySet();
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
                        BlockPos pos = new BlockPos(chunkX * 16 + x, baseY + y, chunkZ * 16 + z);
                        if (predictedPositions.contains(pos)) continue;

                        BlockState state = section.getBlockState(x, y, z);
                        if (isOreBlock(state)) {
                            // Save original server state before clearing
                            LocalWorldGenerator.INSTANCE.saveOriginalState(pos, state);
                            BlockState base = LocalWorldGenerator.getBaseBlockForDimension(pos);
                            section.setBlockState(x, y, z, base);
                            level.sendBlockUpdated(pos, state, base, 3);
                            clearedCount++;
                        }
                    }
                }
            }
        }
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
            || state.is(net.minecraft.world.level.block.Blocks.NETHER_GOLD_ORE)
            || state.is(net.minecraft.world.level.block.Blocks.NETHER_QUARTZ_ORE)
            || state.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)
            // Raw ore blocks (large ore veins)
            || state.is(net.minecraft.world.level.block.Blocks.RAW_IRON_BLOCK)
            || state.is(net.minecraft.world.level.block.Blocks.RAW_COPPER_BLOCK)
            || state.is(net.minecraft.world.level.block.Blocks.RAW_GOLD_BLOCK)
            // Additional blocks that may be used by anti-xray or custom ore gen
            || state.is(net.minecraft.world.level.block.Blocks.MOSSY_COBBLESTONE)
            || state.is(net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK)
            || state.is(net.minecraft.world.level.block.Blocks.GILDED_BLACKSTONE)
            || state.is(net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST);
    }
}
