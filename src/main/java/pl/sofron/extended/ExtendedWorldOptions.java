package pl.sofron.extended;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The switch is kept inside the existing Sodium settings UI. */
public final class ExtendedWorldOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        String version = FabricLoader.getInstance().getModContainer("sofronpack")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("26.3");
        var mod = builder.registerModOptions("sofronpack", "SofronPack", version);
        var page = builder.createOptionPage();
        page.setName(Component.literal("SofronPack"));
        var group = builder.createOptionGroup();

        var extended = builder.createBooleanOption(Identifier.parse("sofronpack:extended_world"));
        extended.setName(Component.literal("Większy świat (64 chunki)"));
        extended.setTooltip(Component.literal("Odległy teren jest zapamiętywany w formie LOD. " +
                "Zwykłe chunki są renderowane i aktualizowane tylko w ustawionej wyżej odległości. " +
                "Generowanie nowych odległych chunków jest ograniczane, by chronić wydajność. " +
                "Renderowanie LOD wymaga zgodnego silnika Voxy; w grze wieloosobowej " +
                "SofronPack nie wymusza generowania terenu na serwerze."));
        extended.setBinding(value -> ExtendedWorldConfig.get().setEnabled(value),
                () -> ExtendedWorldConfig.get().enabled);
        extended.setDefaultValue(false);
        extended.setStorageHandler(() -> ExtendedWorldConfig.get().save());

        group.addOption(extended);
        page.addOptionGroup(group);
        mod.addPage(page);
    }
}
