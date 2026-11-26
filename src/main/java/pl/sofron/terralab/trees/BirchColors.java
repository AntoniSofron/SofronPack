package pl.sofron.terralab.trees;

import pl.sofron.terralab.BiomePalette;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorResolverRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class BirchColors implements ClientModInitializer {
    private static BiomePalette palette(Biome biome) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var id = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome);
        return id == null ? null : BiomePalette.get(id.toString());
    }

    @Override
    public void onInitializeClient() {
        TreeBoatRenderers.register();
        BlockColorRegistry.register(List.of(BlockTintSources.grass()),
                pl.sofron.terralab.soil.SoilBlocks.slab(Blocks.GRASS_BLOCK),
                pl.sofron.terralab.soil.SoilBlocks.stairs(Blocks.GRASS_BLOCK));
        register(Blocks.BIRCH_LEAVES, 0xFF000000 | BiomePalette.DEFAULT_BIRCH, (biome, x, z) -> {
            var p = palette(biome);
            return p == null ? BiomePalette.DEFAULT_BIRCH : p.birch();
        });
        register(TreeWoodContent.PINE.leaves(), 0xFF000000 | BiomePalette.DEFAULT_PINE, (biome, x, z) -> {
            var p = palette(biome);
            return p == null ? BiomePalette.DEFAULT_PINE : p.pine();
        });
        register(TreeWoodContent.APPLE.leaves(), BlockTintSources.foliage().color(Blocks.OAK_LEAVES.defaultBlockState()),
                (biome, x, z) -> biome.getFoliageColor());
        register(TreeWoodContent.FIR.leaves(), 0xFF000000 | BiomePalette.DEFAULT_FIR, (biome, x, z) -> {
            var p = palette(biome);
            return p == null ? BiomePalette.DEFAULT_FIR : p.fir();
        });
        register(TreeWoodContent.CHESTNUT.leaves(), BlockTintSources.foliage().color(Blocks.OAK_LEAVES.defaultBlockState()),
                (biome, x, z) -> biome.getFoliageColor());
        register(Blocks.SPRUCE_LEAVES, 0xFF000000 | BiomePalette.DEFAULT_SPRUCE, (biome, x, z) -> {
            var p = palette(biome);
            return p == null ? BiomePalette.DEFAULT_SPRUCE : p.spruce();
        });
        for (Block leaf : new Block[]{Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES}) {
            register(leaf, BlockTintSources.foliage().color(leaf.defaultBlockState()),
                    (biome, x, z) -> biome.getFoliageColor());
        }
        // Pale oak texture is normalized so the fixed tint reproduces its original color.
        register(Blocks.PALE_OAK_LEAVES, 0xFFA0A69C, (biome, x, z) -> 0xA0A69C);
        register(Blocks.OAK_LEAVES, BlockTintSources.foliage().color(Blocks.OAK_LEAVES.defaultBlockState()),
                (biome, x, z) -> {
                    var p = palette(biome);
                    return p == null || p.oak() < 0 ? biome.getFoliageColor() : p.oak();
                });
    }

    private static void register(Block block, int defaultColor, ColorResolver resolver) {
        ColorResolverRegistry.register(resolver);
        BlockColorRegistry.register(List.of(new BlockTintSource() {
            public int color(BlockState state) { return defaultColor; }

            public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                return SnowLeafTint.color(0xFF000000 | level.getBlockTint(pos, resolver), level, pos);
            }
        }), block);
    }
}
