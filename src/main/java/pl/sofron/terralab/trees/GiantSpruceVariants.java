package pl.sofron.terralab.trees;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

public record GiantSpruceVariants(Holder<Feature> original) implements Feature {
    public static final MapCodec<GiantSpruceVariants> CODEC = Feature.CODEC.fieldOf("original")
            .xmap(GiantSpruceVariants::new, GiantSpruceVariants::original);

    @Override
    public MapCodec<GiantSpruceVariants> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var biome = level.getBiome(origin);
        if (level instanceof net.minecraft.server.level.ServerLevel)
            return original.value().place(level, generator, random, origin);
        var pineKey = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.FEATURE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("sofronpack", "vanilla_mega_pine"));
        boolean pine = biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || original.is(pineKey);
        if ((!pine && !biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) || random.nextBoolean()) {
            if (!pine) return original.value().place(level, generator, random, origin);
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.FEATURE,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("sofronpack", "mega_pine_tree"));
            return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.FEATURE)
                    .getOrThrow(key).value().place(level, generator, random, origin);
        }
        if (!(original.value() instanceof TreeFeature tree)) return false;

        int variant = 1 + random.nextInt(2);
        int root = !pine && variant == 1 ? 4 : 5;
        String template = (pine ? "tall_pine_" : "tall_spruce_") + variant;
        if (!TreeTemplates.placeTwoByTwo(level, random, origin, tree, template, new BlockPos(root, 0, root),
                Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.SPRUCE_LEAVES)) return false;

        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) level.setBlock(origin.offset(x, -1, z), (pine ? Blocks.PODZOL : Blocks.GRASS_BLOCK).defaultBlockState(), 19);
        }
        return true;
    }
}
