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
import net.minecraft.world.level.block.state.properties.Property;
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
    private final Map<String, ExtendedVoxelMesher.ModelFace[]> shapes = new HashMap<>();
    private Object previousModels;
    private static final Direction[] FACES = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST
    };

    public void clear() {
        materials.clear();
        shapes.clear();
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
            clear();
            previousModels = models;
        }
        ExtendedVoxelMesher.MaterialUV[] faces = materials.computeIfAbsent(id, this::extract);
        return faces[faceIndex(direction)];
    }

    /** Resolve live game models here before handing immutable UV tables to the worker. */
    ExtendedVoxelMesher.MaterialResolver snapshot(pl.sofron.extended.ExtendedVoxelStore.VoxelChunk[] chunks) {
        Map<String, ExtendedVoxelMesher.MaterialUV[]> table = new HashMap<>();
        for (String name : pl.sofron.extended.ExtendedTerrainPreview.MATERIALS)
            if (!name.equals("minecraft:air")) table.put(name, materials.computeIfAbsent(name, this::extract));
        for (var chunk : chunks) {
            if (chunk == null) continue;
            for (String name : chunk.palette()) {
                if (!name.equals("minecraft:air"))
                    table.computeIfAbsent(name, id -> materials.computeIfAbsent(id, this::extract));
            }
        }
        Map<String, ExtendedVoxelMesher.ModelFace[]> models = new HashMap<>();
        for (String id : table.keySet()) {
            var shape = shapes.get(id);
            if (shape != null) models.put(id, shape);
        }
        return new ExtendedVoxelMesher.MaterialResolver() {
            public ExtendedVoxelMesher.MaterialUV face(String id, Direction dir) { return table.get(id)[faceIndex(dir)]; }
            public ExtendedVoxelMesher.ModelFace[] model(String id) { return models.get(id); }
        };
    }

    private ExtendedVoxelMesher.MaterialUV[] extract(String name) {
        ExtendedVoxelMesher.MaterialUV[] result = new ExtendedVoxelMesher.MaterialUV[6];
        BlockState state;
        int bracket = name.indexOf('[');
        String baseName = bracket < 0 ? name : name.substring(0, bracket);
        try {
            var id = Identifier.parse(baseName);
            state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
            if (bracket >= 0 && name.endsWith("]")) {
                String attributes = name.substring(bracket + 1, name.length() - 1);
                for (String assignment : attributes.split(",")) {
                    int equal = assignment.indexOf('=');
                    if (equal <= 0) continue;
                    Property<?> property = state.getBlock().getStateDefinition()
                            .getProperty(assignment.substring(0, equal));
                    if (property != null) state = restoreState(
                            state, property, assignment.substring(equal + 1));
                }
            }
        } catch (RuntimeException ex) {
            state = Blocks.STONE.defaultBlockState();
        }
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        if (!parts.isEmpty() && !baseName.equals("minecraft:water") && !baseName.equals("minecraft:lava") && !baseName.equals("minecraft:bubble_column")) {
            boolean simple = parts.size() == 1 && parts.getFirst().getQuads(null).isEmpty()
                    && !name.contains("axis=x") && !name.contains("axis=z");
            if (simple) for (Direction dir : FACES) {
                var faces = parts.getFirst().getQuads(dir);
                if (faces.size() != 1 || !wholeFace(faces.getFirst(), dir)) { simple = false; break; }
            }
            // Near-only leaf models contain the vanilla cube plus four
            // decorative top/bottom planes. LODs MUST always render the
            // original six cube faces; never bake the decorative planes
            // into a distant mesh, even when the active texture pack has
            // replaced the normal leaf block model.
            if (!simple && !baseName.endsWith("_leaves")) {
                List<ExtendedVoxelMesher.ModelFace> baked = new ArrayList<>();
                for (var part : parts) {
                    for (Direction dir : FACES)
                        for (var q : part.getQuads(dir)) baked.add(bake(q, dir, state.getLightEmission()));
                    for (var q : part.getQuads(null)) baked.add(bake(q, null, state.getLightEmission()));
                }
                if (!baked.isEmpty()) shapes.put(name, baked.toArray(ExtendedVoxelMesher.ModelFace[]::new));
            }
        }
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
            boolean water = baseName.equals("minecraft:water") || baseName.equals("minecraft:bubble_column");
            if (water || baseName.equals("minecraft:lava") || quad == null) {
                // Fluid blocks have no baked block-state model, so load the
                // genuine vanilla fluid sprite from the active texture pack.
                String spriteName = water ? "minecraft:block/water_still" :
                        baseName.equals("minecraft:lava") ? "minecraft:block/lava_still" : null;
                if (spriteName != null) {
                    var texture = (TextureAtlas)Minecraft.getInstance().getTextureManager()
                            .getTexture(Identifier.parse("minecraft:textures/atlas/blocks.png"));
                    TextureAtlasSprite sprite = texture.getSprite(Identifier.parse(spriteName));
                    if (sprite != null) {
                        int tint = water ? -1 : 0xffffff;
                        result[i] = new ExtendedVoxelMesher.MaterialUV(
                                sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                                tint, true, state.getLightEmission());
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
            boolean translucent = !state.canOcclude()
                    || quad.materialInfo().layer() != ChunkSectionLayer.SOLID;
            result[i] = new ExtendedVoxelMesher.MaterialUV(
                    u0 + eU, v0 + eV, u1 - eU, v1 - eV, tint, translucent, state.getLightEmission());
        }
        return result;
    }

    private static boolean wholeFace(BakedQuad q, Direction dir) {
        if (q.direction() != dir) return false;
        int corners = 0;
        for (int i = 0; i < 4; i++) {
            var v = q.position(i);
            if ((v.x() != 0 && v.x() != 1) || (v.y() != 0 && v.y() != 1)
                    || (v.z() != 0 && v.z() != 1)) return false;
            float coordinate = v.x()*dir.getStepX() + v.y()*dir.getStepY() + v.z()*dir.getStepZ();
            if (coordinate != Math.max(0, dir.getStepX()+dir.getStepY()+dir.getStepZ())) return false;
            corners |= 1 << ((int)v.x() + 2*(int)v.y() + 4*(int)v.z());
        }
        return Integer.bitCount(corners) == 4;
    }

    private static ExtendedVoxelMesher.ModelFace bake(BakedQuad q, Direction cull, int emission) {
        float[] vertices = new float[20];
        for (int i = 0; i < 4; i++) {
            var v = q.position(i);
            int n = i * 5;
            vertices[n] = v.x(); vertices[n+1] = v.y(); vertices[n+2] = v.z();
            vertices[n+3] = UVPair.unpackU(q.packedUV(i));
            vertices[n+4] = UVPair.unpackV(q.packedUV(i));
        }
        int shade = switch(q.direction()) { case UP -> 255; case DOWN -> 140;
            case NORTH, SOUTH -> 209; default -> 179; };
        return new ExtendedVoxelMesher.ModelFace(vertices, q.materialInfo().isTinted() ? -1 : 0xffffff,
                shade, emission, cull);
    }

    /** Restore vanilla and modded block-state properties from persistent LOD. */
    private static <T extends Comparable<T>> BlockState restoreState(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
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
