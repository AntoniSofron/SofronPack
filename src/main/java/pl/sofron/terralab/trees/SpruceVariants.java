package pl.sofron.terralab.trees;

import pl.sofron.terralab.TerraLabMod;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

public record SpruceVariants(Holder<Feature> original) implements Feature {
    public static final MapCodec<SpruceVariants> CODEC = Feature.CODEC.fieldOf("original")
            .xmap(SpruceVariants::new, SpruceVariants::original);
    static final TagKey<Biome> TAIGAS = TagKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "spruce_variants"));
    static final ResourceKey<Biome> FIR_TAIGA = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "old_growth_fir_taiga"));
    private static final ResourceKey<Feature> NEW_FIR = ResourceKey.create(Registries.FEATURE, Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "fir_tree"));
    private static final TagKey<Biome> MIXED_CONIFERS = TagKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "mixed_conifers"));
    private static final ResourceKey<Feature> VANILLA_PINE = ResourceKey.create(Registries.FEATURE,
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "vanilla_pine"));
    private static final ResourceKey<Feature> VANILLA_SPRUCE = ResourceKey.create(Registries.FEATURE,
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "vanilla_spruce"));
    private static final ResourceKey<Feature> NEW_PINE = ResourceKey.create(Registries.FEATURE,
            Identifier.fromNamespaceAndPath(TerraLabMod.MOD_ID, "pine_tree"));
    private static final BlockPos NARROW_ROOT = new BlockPos(2, 0, 2);

    private static final BlockPos WIDE_ROOT = new BlockPos(3, 0, 3);

    @Override
    public MapCodec<SpruceVariants> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var biome = level.getBiome(origin);
        if (biome.is(FIR_TAIGA)) {
            Feature nativeFir = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(NEW_FIR).value();
            if (!(original.value() instanceof TreeFeature tree) || level instanceof net.minecraft.server.level.ServerLevel) return nativeFir.place(level, generator, random, origin);
            int v = 1 + random.nextInt(5);
            if (TreeTemplates.place(level, random, origin, tree, "spruce_" + v, v >= 4 ? WIDE_ROOT : NARROW_ROOT, Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.SPRUCE_LEAVES)) return true;
            return nativeFir.place(level, generator, random, origin);
        }
        boolean mixed = biome.is(MIXED_CONIFERS);
        boolean pine = biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || original.is(VANILLA_PINE);
        if (level instanceof net.minecraft.server.level.ServerLevel)
            return original.value().place(level, generator, random, origin);
        if (biome.is(Biomes.TAIGA) && random.nextInt(5) == 0) {
            return level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(NEW_FIR).value()
                    .place(level, generator, random, origin);
        }
        Feature vanilla = original.value();
        if (mixed) {
            pine = random.nextInt(4) < 3;
            vanilla = level.registryAccess().lookupOrThrow(Registries.FEATURE)
                    .getOrThrow(pine ? VANILLA_PINE : VANILLA_SPRUCE).value();
        }
        if (!mixed && !biome.is(TAIGAS) || random.nextInt(5) >= 3) {
            if (!pine) return vanilla.place(level, generator, random, origin);
            return level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(NEW_PINE).value()
                    .place(level, generator, random, origin);
        }
        if (!(vanilla instanceof TreeFeature tree)) return false;
        int variant = 1 + random.nextInt(pine ? 3 : 5);
        BlockPos root = variant >= (pine ? 3 : 4) ? WIDE_ROOT : NARROW_ROOT;
        String template = (pine ? "pine_" : "spruce_") + variant;
        return TreeTemplates.place(level, random, origin, tree, template, root,
                Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.SPRUCE_LEAVES);
    }
}
