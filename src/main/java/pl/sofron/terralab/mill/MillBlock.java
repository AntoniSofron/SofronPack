package pl.sofron.terralab.mill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A small batch mill. Both inventories are persisted in the block state,
 * so saving, unloading and restarting need no extra block entity or GUI.
 * Server ticks never load a neighboring chunk: the shaft stays in one column.
 */
public final class MillBlock extends Block {
    public static final IntegerProperty INPUT = IntegerProperty.create("input", 0, MillLogic.CAPACITY);
    public static final IntegerProperty OUTPUT = IntegerProperty.create("output", 0, MillLogic.CAPACITY);

    public MillBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(INPUT, 0).setValue(OUTPUT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(INPUT, OUTPUT);
    }

    private static boolean powered(ServerLevel level, BlockPos pos) {
        return MillLogic.powered(distance -> {
            BlockPos above = pos.above(distance);
            if (above.getY() < level.getMinY() || above.getY() >= level.getMinY() + level.getHeight())
                return MillLogic.Part.OTHER;
            BlockState state = level.getBlockState(above);
            if (state.is(MillContent.SHAFT)) return MillLogic.Part.SHAFT;
            if (state.is(MillContent.SAILS)) return MillLogic.Part.SAILS;
            return MillLogic.Part.OTHER;
        });
    }

    private void schedule(Level level, BlockPos pos, BlockState state) {
        if (!level.isClientSide() && MillLogic.canGrind(state.getValue(INPUT), state.getValue(OUTPUT)))
            level.scheduleTick(pos, this, MillLogic.GRIND_TICKS);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!oldState.is(this)) schedule(level, pos, state);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.WHEAT)) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            int amount = MillLogic.insertAmount(state.getValue(INPUT), stack.getCount());
            if (amount > 0) {
                // Always consume the actual input, including in Creative.
                // That keeps the mill's inventory accounting straightforward.
                stack.shrink(amount);
                BlockState next = state.setValue(INPUT, state.getValue(INPUT) + amount);
                level.setBlockAndUpdate(pos, next);
                schedule(level, pos, next);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!player.mayBuild()) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            int output = state.getValue(OUTPUT);
            int input = state.getValue(INPUT);
            BlockState next = state;
            if (output > 0) {
                next = state.setValue(OUTPUT, 0);
                level.setBlockAndUpdate(pos, next);
                ItemStack flour = new ItemStack(MillContent.FLOUR, output);
                if (!player.getInventory().add(flour)) Block.popResource(level, pos, flour);
            } else if (player.isShiftKeyDown() && input > 0) {
                next = state.setValue(INPUT, 0);
                level.setBlockAndUpdate(pos, next);
                ItemStack wheat = new ItemStack(Items.WHEAT, input);
                if (!player.getInventory().add(wheat)) Block.popResource(level, pos, wheat);
            }
            schedule(level, pos, next);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int input = state.getValue(INPUT), output = state.getValue(OUTPUT);
        if (!MillLogic.canGrind(input, output)) return;
        BlockState next = state;
        if (powered(level, pos)) {
            next = state.setValue(INPUT, input - 1).setValue(OUTPUT, output + 1);
            level.setBlockAndUpdate(pos, next);
        }
        schedule(level, pos, next);
    }
}
