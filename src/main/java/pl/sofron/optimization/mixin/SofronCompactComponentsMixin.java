package pl.sofron.optimization.mixin;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import java.util.Optional;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Empty component patches should not retain a mutable hash table on every
 * otherwise ordinary item stack. Vanilla copy-on-write safely recreates a
 * mutable map when an item stack changes later.
 */
@Mixin(PatchedDataComponentMap.class)
public abstract class SofronCompactComponentsMixin {
    @Shadow private Reference2ObjectMap<DataComponentType<?>, Optional<?>> patch;
    @Shadow private boolean copyOnWrite;

    @Inject(method = {"applyPatch(Lnet/minecraft/core/component/DataComponentPatch;)V", "restorePatch"},
            at = @At("RETURN"), require = 0)
    private void sofron$deduplicateEmptyPatch(CallbackInfo ci) {
        if (patch.isEmpty()) {
            patch = Reference2ObjectMaps.emptyMap();
            copyOnWrite = true;
        }
    }

    @Inject(method = {"set", "remove"}, at = @At("RETURN"), require = 0)
    private void sofron$deduplicateEmptyPatchAfterMutation(CallbackInfoReturnable<?> cir) {
        if (patch.isEmpty()) {
            patch = Reference2ObjectMaps.emptyMap();
            copyOnWrite = true;
        }
    }
}
