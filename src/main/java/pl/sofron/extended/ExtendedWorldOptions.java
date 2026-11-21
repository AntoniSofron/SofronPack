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
        option.setName(Component.literal("Większy świat (LOD 64 chunki)"));
        option.setTooltip(Component.literal("SofronPack samodzielnie wyświetla i zapisuje uproszczony " +
                "teren do 64 chunków. Zwykły zasięg renderowania i symulacji " +
                "pozostaje bez zmian. Generowanie nowego terenu działa stopniowo " +
                "w trybie jednoosobowym. Nie potrzebujesz dodatkowego moda."));
        option.setBinding(value -> ExtendedWorldConfig.get().setEnabled(value),
                () -> ExtendedWorldConfig.get().enabled);
        option.setDefaultValue(false);
        option.setStorageHandler(() -> ExtendedWorldConfig.get().save());
        return option;
    }
}
