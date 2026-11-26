package pl.sofron.terralab;

import net.fabricmc.api.ModInitializer;
import pl.sofron.terralab.villages.VillageStructure;
import pl.sofron.terralab.trees.AcaciaVariants;
import pl.sofron.terralab.trees.BirchVariants;
import pl.sofron.terralab.trees.DarkOakVariants;
import pl.sofron.terralab.trees.GiantSpruceVariants;
import pl.sofron.terralab.trees.GroundedWillow;
import pl.sofron.terralab.trees.JungleVariants;
import pl.sofron.terralab.trees.OakVariants;
import pl.sofron.terralab.trees.SpruceVariants;

public final class TerraLabMod implements ModInitializer {
    public static final String MOD_ID = "sofronpack";

    @Override
    public void onInitialize() {
        BiomePalette.register();
        VillageStructure.register();
        pl.sofron.atmosphere.SofronCloudAirflow.warmUp();
        pl.sofron.extended.ExtendedWorldPrefetch.register();
        pl.sofron.terralab.soil.SoilBlocks.register();
        pl.sofron.terralab.rice.RiceContent.register();
        pl.sofron.terralab.food.FoodContent.register();
        pl.sofron.terralab.mill.MillContent.register();
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "birch_variants"), BirchVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "oak_variants"), OakVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "grounded_willow"), GroundedWillow.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "spruce_variants"), SpruceVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "giant_spruce_variants"), GiantSpruceVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "acacia_variants"), AcaciaVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "jungle_variants"), JungleVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "dark_oak_variants"), DarkOakVariants.CODEC);
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.FEATURE_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "wild_rice"),
                pl.sofron.terralab.rice.WildRiceFeature.CODEC);
        System.out.println("[SofronPack] Worldgen ready.");
    }
}
