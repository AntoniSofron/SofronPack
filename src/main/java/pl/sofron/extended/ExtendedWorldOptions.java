package pl.sofron.extended;

import net.caffeinemc.mods.sodium.api.config.option.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Creates an in-place Sodium option next to vanilla render distance. */
public final class ExtendedWorldOptions {
    private ExtendedWorldOptions() {}

    public static BooleanOptionBuilder build(ConfigBuilder builder) {
        var option = builder.createBooleanOption(Identifier.parse("sofronpack:extended_world"));
        option.setName(Component.literal("Większy świat (64 chunki)"));
        option.setTooltip(Component.literal("Zwykły zasięg renderowania i aktualizacji pozostaje " +
                "bez zmian. Odległy teren generuje się stopniowo i trafia do trwałego LOD. " +
                "Tryb wymaga Voxy jako silnika LOD; nowy teren generowany jest tylko w " +
                "trybie jednoosobowym. Po zmianie opcji ponownie wejdź do świata."));
        option.setBinding(value -> ExtendedWorldConfig.get().setEnabled(value),
                () -> ExtendedWorldConfig.get().enabled);
        option.setDefaultValue(false);
        option.setStorageHandler(() -> ExtendedWorldConfig.get().save());
        return option;
    }
}
