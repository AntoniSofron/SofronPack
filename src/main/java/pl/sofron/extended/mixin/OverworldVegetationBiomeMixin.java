package pl.sofron.extended.mixin;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OverworldBiomeBuilder.class)
abstract class OverworldVegetationBiomeMixin {
    @Unique private static final ResourceKey<Biome> SHRUBLAND =
            ResourceKey.create(Registries.BIOME, Identifier.parse("sofronpack:shrubland"));
    @Unique private static final ResourceKey<Biome> RAINFOREST =
            ResourceKey.create(Registries.BIOME, Identifier.parse("sofronpack:rainforest"));

    // Assign warm inland climate slots while vanilla builds the Overworld climate index.
    @Inject(method = "pickMiddleBiome", at = @At("HEAD"), cancellable = true)
    private void sofronpack$warmBiomes(int temperature, int humidity, Climate.Parameter weirdness,
                                       CallbackInfoReturnable<ResourceKey<Biome>> result) {
        if (temperature != 3) return;
        if (humidity == 1 && weirdness.min() >= 0) result.setReturnValue(SHRUBLAND);
        if (humidity == 4 && weirdness.max() < 0) result.setReturnValue(RAINFOREST);
    }
}
