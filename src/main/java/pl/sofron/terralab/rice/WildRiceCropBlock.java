package pl.sofron.terralab.rice;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.tags.FluidTags;
import org.jetbrains.annotations.Nullable;

public final class WildRiceCropBlock extends DoublePlantBlock implements BonemealableBlock {
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 7);

    private static final VoxelShape[] LOWER_OUTLINE = {
            Block.box(4, 0, 4, 13, 3, 13),
            Block.box(2, 0, 2, 13, 8, 13),
            Block.box(1, 0, 1, 13, 13, 13),
            Block.box(1, 0, 1, 14, 16, 14),
            Block.box(1, 0, 1, 14, 16, 14),
            Block.box(1, 0, 1, 14, 16, 14),
            Block.box(2, 0, 2, 14, 16, 14),
            Block.box(1, 0, 1, 14, 16, 14)
    };

    private static final VoxelShape[] UPPER_OUTLINE = {
            Shapes.empty(),
            Shapes.empty(),
            Shapes.empty(),
            Block.box(4, 0, 4, 13, 4, 13),
            Block.box(4, 0, 4, 13, 6, 13),
            Block.box(1, 0, 1, 14, 7, 14),
            Block.box(0, 0, 0, 14, 9, 14),
            Block.box(0, 0, 0, 14, 10, 14)
    };

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int age = state.getValue(AGE);
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER_OUTLINE[age] : UPPER_OUTLINE[age];
    }

    public WildRiceCropBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(AGE, 0)
                .setValue(BlockStateProperties.WATERLOGGED, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(AGE, BlockStateProperties.WATERLOGGED);
    }

    public static boolean validSite(LevelReader level, BlockPos lower) {
        if (!level.getBlockState(lower.below()).is(Blocks.DIRT)) return false;
        FluidState water = level.getFluidState(lower);
        if (!water.is(FluidTags.WATER) || !water.isSource()) return false;
        return level.getBlockState(lower.above()).isAir();
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!validSite(context.getLevel(), context.getClickedPos())) return null;
        return defaultBlockState().setValue(AGE, 0);
    }

    @Override
    protected boolean mayPlaceOn(BlockState soil, BlockGetter level, BlockPos pos) {
        return soil.is(Blocks.DIRT);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return !state.getValue(BlockStateProperties.WATERLOGGED)
                    && below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return state.getValue(BlockStateProperties.WATERLOGGED)
                && level.getBlockState(pos.below()).is(Blocks.DIRT);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
                ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks,
                                     BlockPos pos, Direction side, BlockPos neighbour,
                                     BlockState adjacent, RandomSource random) {
        if (state.getValue(BlockStateProperties.WATERLOGGED))
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));

        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            if ((side == Direction.DOWN && !adjacent.is(Blocks.DIRT))
                    || (side == Direction.UP
                    && (!adjacent.is(this) || adjacent.getValue(HALF) != DoubleBlockHalf.UPPER)))
                return Blocks.WATER.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, side, neighbour, adjacent, random);
    }

    public static boolean placeMature(LevelAccessor level, BlockPos pos) {
        if (!validSite(level, pos)) return false;
        BlockState lower = RiceContent.WILD_RICE_CROP.defaultBlockState().setValue(AGE, 7);
        DoublePlantBlock.placeAt(level, lower, pos, 2);
        return level.getBlockState(pos).is(RiceContent.WILD_RICE_CROP)
                && level.getBlockState(pos.above()).is(RiceContent.WILD_RICE_CROP);
    }

    private void grow(ServerLevel level, BlockPos lowerPos, BlockState lower, int age) {
        BlockState upper = level.getBlockState(lowerPos.above());
        if (!upper.is(this) || upper.getValue(HALF) != DoubleBlockHalf.UPPER) return;
        int newAge = Math.min(7, age);
        level.setBlockAndUpdate(lowerPos.above(), upper.setValue(AGE, newAge));
        level.setBlockAndUpdate(lowerPos, lower.setValue(AGE, newAge));
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER || state.getValue(AGE) >= 7
                || !RiceCropBlock.hasRiceGrowthLight(level, pos)) return;
        if (random.nextInt(3) != 0) return;
        float fertility = RiceCropBlock.wheatEquivalentFertility(level, pos, state, true);
        if (random.nextInt((int)(25.0F / fertility) + 1) == 0)
            grow(level, pos, state, state.getValue(AGE) + 1);
    }

    private static BlockPos rootPos(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state,
                                         BonemealSource source) {
        BlockState root = level.getBlockState(rootPos(pos, state));
        return root.is(this) && root.getValue(AGE) < 7;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos,
                                     BlockState state, BonemealSource source) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos,
                                BlockState state, BonemealSource source) {
        BlockPos lowerPos = rootPos(pos, state);
        BlockState root = level.getBlockState(lowerPos);
        if (root.is(this))
            grow(level, lowerPos, root, root.getValue(AGE) + 2 + random.nextInt(4));
    }
}
