package pl.sofron.terralab.trees;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

public final class SnowLeafTint {
    private SnowLeafTint() {}
    public static boolean eligible(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && SnowLeafGradient.eligible(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
    public static boolean snow(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
    }
    public static int color(int base, BlockAndTintGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= SnowLeafGradient.DEPTH; i++) {
            cursor.set(pos.getX(), pos.getY() + i, pos.getZ());
            BlockState above = level.getBlockState(cursor);
            if (snow(above)) return SnowLeafGradient.blend(base, SnowLeafGradient.DEPTH + 1 - i);
            if (!eligible(above)) break;
        }
        return base;
    }
}
