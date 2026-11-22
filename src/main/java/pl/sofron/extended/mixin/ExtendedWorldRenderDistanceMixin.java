package pl.sofron.extended.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.extended.ExtendedLodGpuBudget;
import pl.sofron.extended.ExtendedWorldConfig;

/**
 * Opt-in (through Extended World) native chunk distance cap.
 * The original options slider is never overwritten.
 */
@Mixin(Options.class)
public abstract class ExtendedWorldRenderDistanceMixin {
    private static boolean sofron$loggedCap;

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void sofron$saveGpuMemory(CallbackInfoReturnable<Integer> cir) {
        var config = ExtendedWorldConfig.get();
        if (!config.enabled || config.normalRenderCap <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.level.dimension() != Level.OVERWORLD) return;

        int original = cir.getReturnValue();
        int effective = ExtendedLodGpuBudget.effectiveNormalDistance(
                original, config.normalRenderCap);
        if (effective != original) {
            cir.setReturnValue(effective);
            if (!sofron$loggedCap) {
                sofron$loggedCap = true;
                System.out.println("[SofronPack] GPU saver: normal view " + original +
                        " -> " + effective + " chunks; distant LOD still 128 chunks");
            }
        }
    }
}
