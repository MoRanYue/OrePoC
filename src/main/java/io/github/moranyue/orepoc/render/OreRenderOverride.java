package io.github.moranyue.orepoc.render;

import io.github.moranyue.orepoc.generator.LocalWorldGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-side rendering override logic.
 * <p>
 * Implements the core algorithm:
 * <ol>
 *   <li>Check if mode is NONE → return null (no override).</li>
 *   <li>Query {@link LocalWorldGenerator} for the predicted ore at a position.</li>
 *   <li>If no ore is predicted, return {@code null} (no override).</li>
 *   <li>If an ore is predicted but the server shows air, return {@code null}
 *       (respect anti-xray — do not reveal ores hidden as air).</li>
 *   <li>Otherwise, return the locally-predicted ore block state.</li>
 * </ol>
 */
public final class OreRenderOverride {

    private OreRenderOverride() {
    }

    /**
     * Determine the override block state to render at the given position.
     *
     * @param pos         The block position being rendered.
     * @param serverState The block state sent by the server for this position.
     * @return The block state to render instead, or {@code null} to render the
     *         original server state unchanged.
     */
    public static BlockState getOverride(BlockPos pos, BlockState serverState) {
        // 0. If mode is NONE, do not override anything
        if (LocalWorldGenerator.INSTANCE.getMode() == LocalWorldGenerator.Mode.NONE) {
            return null;
        }

        // 1. If no seed has been set, do not override anything
        if (!LocalWorldGenerator.INSTANCE.isSeedSet()) {
            return null;
        }

        // 2. Query local generator for ore at this position
        BlockState localOre = LocalWorldGenerator.INSTANCE.getOreAt(pos);
        if (localOre == null || localOre.isAir()) {
            return null;
        }

        // 3. If the server is showing air at this position, respect it
        //    (anti-xray may have replaced the ore with air)
        if (serverState.isAir()) {
            return null;
        }

        // 4. Replace with the locally-generated ore
        return localOre;
    }
}
