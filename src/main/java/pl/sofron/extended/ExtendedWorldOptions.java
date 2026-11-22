package pl.sofron.extended;

import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Creates an in-place Sodium option next to vanilla render distance. */
public final class ExtendedWorldOptions {
    private ExtendedWorldOptions() {}

    public static BooleanOptionBuilder build(ConfigBuilder builder) {
        var option = builder.createBooleanOption(Identifier.parse("sofronpack:extended_world"));
        option.setName(Component.literal("Większy świat (tekstury bloków, 128 chunków)"));
        option.setTooltip(Component.literal("Wokselowy świat do 128 chunków. Dla oszczędzania GPU " +
                "zwykłe chunki w Nadziemiu renderują się maksymalnie do 20 chunków " +
                "(suwak pozostaje bez zmian). Parametr normalRenderCap w " +
                "config/sofronpack-extended-world.json: 0 wyłącza ten limit. " +
                "Odległość symulacji pozostaje bez zmian."));
        option.setBinding(value -> ExtendedWorldConfig.get().setEnabled(value),
                () -> ExtendedWorldConfig.get().enabled);
        option.setDefaultValue(false);
        option.setStorageHandler(() -> ExtendedWorldConfig.get().save());
        return option;
    }
}
