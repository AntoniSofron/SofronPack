package pl.sofron.terralab;

import net.fabricmc.api.ModInitializer;

public final class TerraLabMod implements ModInitializer {
    public static final String MOD_ID = "sofronpack";

    @Override
    public void onInitialize() {
        pl.sofron.atmosphere.SofronCloudAirflow.warmUp();
        pl.sofron.extended.ExtendedWorldPrefetch.register();
        pl.sofron.terralab.soil.SoilBlocks.register();
        pl.sofron.terralab.rice.RiceContent.register();
        pl.sofron.terralab.food.FoodContent.register();
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
