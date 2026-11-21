package pl.sofron.extended.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import pl.sofron.extended.ExtendedVoxelMesher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps persistent block identifiers to the actual block-model atlas quads,
 * including resource-pack overrides. Nothing here uses hardcoded map colors
 * or custom fake noise textures.
 */
public final class ExtendedVoxelAtlas implements ExtendedVoxelMesher.MaterialResolver {
    private final Map<String, ExtendedVoxelMesher.MaterialUV[]> materials = new HashMap<>();
    private Object previousModels;
    private static final Direction[] FACES = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST
    };

    public void clear() {
        materials.clear();
        previousModels = null;
    }

    public boolean refreshModels() {
        Object models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (previousModels == models) return false;
        clear();
        previousModels = models;
        return true;
    }

    @Override
    public ExtendedVoxelMesher.MaterialUV face(String id, Direction direction) {
        Minecraft mc = Minecraft.getInstance();
        var models = mc.getModelManager().getBlockStateModelSet();
        if (previousModels != models) {
            materials.clear();
            previousModels = models;
        }
        ExtendedVoxelMesher.MaterialUV[] faces = materials.computeIfAbsent(id, this::extract);
        return faces[faceIndex(direction)];
    }

    private ExtendedVoxelMesher.MaterialUV[] extract(String name) {
        ExtendedVoxelMesher.MaterialUV[] result = new ExtendedVoxelMesher.MaterialUV[6];
        BlockState state;
        try {
            var id = Identifier.parse(name);
            state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        } catch (RuntimeException ex) {
            state = Blocks.STONE.defaultBlockState();
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        for (int i = 0; i < FACES.length; i++) {
            Direction dir = FACES[i];
            BakedQuad quad = null;
            for (BlockStateModelPart part : parts) {
                var directed = part.getQuads(dir);
                if (!directed.isEmpty()) { quad = directed.getFirst(); break; }
            }
            if (quad == null) for (BlockStateModelPart part : parts) {
                var free = part.getQuads(null);
                if (!free.isEmpty()) { quad = free.getFirst(); break; }
            }
            if (quad == null) {
                // Fluid blocks have no baked block-state model, so load the
                // genuine vanilla fluid sprite from the active texture pack.
                String spriteName = name.contains("water") ? "minecraft:block/water_still" :
                        name.contains("lava") ? "minecraft:block/lava_still" : null;
                if (spriteName != null) {
                    var texture = (TextureAtlas)Minecraft.getInstance().getTextureManager()
                            .getTexture(Identifier.parse("minecraft:textures/atlas/blocks.png"));
                    TextureAtlasSprite sprite = texture.getSprite(Identifier.parse(spriteName));
                    if (sprite != null) {
                        int tint = name.contains("water") ? -1 : 0xffffff;
                        result[i] = new ExtendedVoxelMesher.MaterialUV(
                                sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                                tint, true);
                    }
                }
                continue;
            }
            float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE;
            float u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int vertex = 0; vertex < 4; vertex++) {
                long packed = quad.packedUV(vertex);
                float u = UVPair.unpackU(packed), v = UVPair.unpackV(packed);
                u0 = Math.min(u0, u); v0 = Math.min(v0, v);
                u1 = Math.max(u1, u); v1 = Math.max(v1, v);
            }
            if (u1 <= u0 || v1 <= v0) continue;
            // Inset UVs slightly to avoid neighbouring sprite bleeding at
            // high mip levels. Quad UVs are directly from the resource pack.
            float eU = (u1 - u0) / 256f;
            float eV = (v1 - v0) / 256f;
            // -1 requests the stored, actual biome tint for each chunk.
            // No fixed "fake grass/leaf colour" survives in the material table.
            int tint = quad.materialInfo().isTinted() ? -1 : 0xffffff;
            boolean translucent = quad.materialInfo().layer() != ChunkSectionLayer.SOLID;
            result[i] = new ExtendedVoxelMesher.MaterialUV(
                    u0 + eU, v0 + eV, u1 - eU, v1 - eV, tint, translucent);
        }
        return result;
    }

    private static int faceIndex(Direction direction) {
        return switch (direction) {
            case UP -> 0;
            case DOWN -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
        };
    }
}
