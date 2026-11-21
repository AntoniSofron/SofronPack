package pl.sofron.extended;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inserts the optional distant-world toggle directly below Sodium's render
 * distance slider, ahead of the simulation distance setting.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder", remap = false)
public abstract class ExtendedWorldSodiumOptionsMixin {
    @ModifyExpressionValue(
        method = "buildGeneralPage",
        at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/api/config/structure/OptionGroupBuilder;addOption(Lnet/caffeinemc/mods/sodium/api/config/structure/OptionBuilder;)Lnet/caffeinemc/mods/sodium/api/config/structure/OptionGroupBuilder;",
            ordinal = 0),
        remap = false, require = 0)
    private OptionGroupBuilder sofron$insertExtendedWorld(
            OptionGroupBuilder group, @Local(argsOnly = true) ConfigBuilder builder) {
        return group.addOption(ExtendedWorldOptions.build(builder));
    }
}
