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
    public static boolean leaf(BlockState state) { return state.getBlock() instanceof LeavesBlock; }
    public static int kind(BlockState state) {
        if (snow(state)) return 1;
        if (eligible(state)) return 2;
        if (leaf(state)) return 3;
        if (state.isAir()) return 4;
        return 0;
    }
    public static boolean eligible(BlockState state) {
        return ELIGIBLE.contains(state.getBlock());
    }
    public static boolean snow(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
    }
    public static int color(int base, BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) return base;
        boolean previousLeaf = true;
        BlockPos.MutableBlockPos cursor = CURSOR.get();
        for (int i = 1; i <= SnowLeafGradient.DEPTH; i++) {
            cursor.set(pos.getX(), pos.getY() + i, pos.getZ());
            BlockState above = level.getBlockState(cursor);
            if (snow(above)) return previousLeaf ? SnowLeafGradient.blend(base, SnowLeafGradient.DEPTH + 1 - i) : base;
            previousLeaf = leaf(above);
            if (!previousLeaf && !above.isAir()) break;
        }
        return base;
    }
}
