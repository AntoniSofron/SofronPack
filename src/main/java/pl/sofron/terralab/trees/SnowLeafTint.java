package pl.sofron.terralab.trees;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

public final class SnowLeafTint {
    private static final ThreadLocal<BlockPos.MutableBlockPos> CURSOR = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final java.util.Set<net.minecraft.world.level.block.Block> ELIGIBLE = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    static {
        for (var block : BuiltInRegistries.BLOCK) {
            if (block instanceof LeavesBlock && SnowLeafGradient.eligible(BuiltInRegistries.BLOCK.getKey(block).toString())) ELIGIBLE.add(block);
        }
    }
    private SnowLeafTint() {}
    public static boolean eligible(BlockState state) {
        return ELIGIBLE.contains(state.getBlock());
    }
    public static boolean snow(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
    }
    public static int color(int base, BlockAndTintGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = CURSOR.get();
        for (int i = 1; i <= SnowLeafGradient.DEPTH; i++) {
            cursor.set(pos.getX(), pos.getY() + i, pos.getZ());
            BlockState above = level.getBlockState(cursor);
            if (snow(above)) return SnowLeafGradient.blend(base, SnowLeafGradient.DEPTH + 1 - i);
            if (!eligible(above)) break;
        }
        return base;
    }
}
