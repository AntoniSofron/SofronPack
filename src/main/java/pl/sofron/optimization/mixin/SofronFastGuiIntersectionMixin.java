package pl.sofron.optimization.mixin;

import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.optimization.gui.SofronSpatialGuiArrayList;

/** Falls back to vanilla whenever spatial indexing is invalidated. */
@Mixin(GuiRenderState.class)
public abstract class SofronFastGuiIntersectionMixin {
    @Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true)
    private void sofron$fastIntersects(ScreenRectangle bounds, List<? extends ScreenArea> states,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (states instanceof SofronSpatialGuiArrayList<?> indexed && indexed.isStateValid())
            cir.setReturnValue(indexed.intersects(bounds));
    }
}
