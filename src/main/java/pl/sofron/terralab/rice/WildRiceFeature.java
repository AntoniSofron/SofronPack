package pl.sofron.terralab.rice;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Only the shallow edge of vanilla swamps, not open water or deep channels.
 * Rarity and attempt count are controlled by swamp_wild_rice placed_feature.
 */
public final class WildRiceFeature implements Feature {
    public static final MapCodec<WildRiceFeature> CODEC = MapCodec.unit(new WildRiceFeature());

    @Override
    public MapCodec<WildRiceFeature> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator,
                         RandomSource random, BlockPos origin) {
        if (!WildRiceCropBlock.validSite(level, origin) || !nearShore(level, origin))
            return false;
        return WildRiceCropBlock.placeMature(level, origin);
    }

    private static boolean nearShore(WorldGenLevel level, BlockPos origin) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int radius = 1; radius <= 2; radius++) {
                BlockPos edge = origin.relative(dir, radius);
                var ground = level.getBlockState(edge);
                // The neighbouring waterline has to reach actual shore ground.
                if (ground.is(Blocks.DIRT) || ground.is(Blocks.GRASS_BLOCK)
                        || ground.is(Blocks.MUD) || ground.is(Blocks.CLAY))
                    return true;
            }
        }
        return false;
    }
}
