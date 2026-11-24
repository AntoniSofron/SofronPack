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
        option.setTooltip(Component.literal("SofronPack wyświetla teren do 128 chunków. Daleki nieodwiedzony " +
                "teren jest przewidywany z seeda bez zapisywania tysięcy chunków. " +
                "Dokładne woksele są zachowywane w ograniczonym cache. " +
                "Zwykły zasięg renderowania i symulacji pozostaje bez zmian."));
        option.setBinding(value -> ExtendedWorldConfig.get().setEnabled(value),
                () -> ExtendedWorldConfig.get().enabled);
        option.setDefaultValue(false);
        option.setStorageHandler(() -> ExtendedWorldConfig.get().save());
        return option;
    }
}
