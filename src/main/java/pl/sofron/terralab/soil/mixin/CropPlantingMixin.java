package pl.sofron.terralab.soil.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.terralab.soil.SoilCropRules;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class CropPlantingMixin {
    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
    private void sofronRequireWaterForShapedFarmland(LevelReader level, BlockPos pos,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        BlockState plant = (BlockState)(Object)this;
        if (plant.is(BlockTags.MAINTAINS_FARMLAND)
                && SoilCropRules.isDryShapedField(level.getBlockState(pos.below())))
            cir.setReturnValue(false);
    }
}
