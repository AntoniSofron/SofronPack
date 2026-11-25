package pl.sofron.extended;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;
import net.minecraft.core.Direction;

/**
 * Stable across game launches and sensitive to saved chunk edits, adjoining
 * chunk changes, resource-pack atlas UV changes and non-cubic block geometry.
 * A cached GPU quad is never trusted based on tile coordinates alone.
 */
public final class ExtendedLodMeshFingerprint {
    private static final Direction[] FACES = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST
    };

    private ExtendedLodMeshFingerprint() {}

    public static byte[] of(ExtendedVoxelStore.VoxelChunk[] sources,
                            ExtendedVoxelMesher.MaterialResolver materials) {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        // Bump this format salt whenever mesh algorithms change.
        string(hash, "SofronPack-mesh-v1:volume5:surface5:packed48");
        TreeSet<String> used = new TreeSet<>();
        for (var chunk : sources) {
            if (chunk == null) {
                hash.update((byte) 0);
                continue;
            }
            hash.update((byte) 1);
            integer(hash, chunk.chunkX());
            integer(hash, chunk.chunkZ());
            integer(hash, chunk.minY());
            integer(hash, chunk.yCount());
            integer(hash, chunk.grassTint());
            integer(hash, chunk.foliageTint());
            integer(hash, chunk.waterTint());
            for (String name : chunk.palette()) {
                string(hash, name);
                if (!name.equals("minecraft:air")) used.add(name);
            }
            // The compressed source changes when the block map changes.
            byte[] source = chunk.deflated();
            integer(hash, source.length);
            hash.update(source);
        }
        // Previews use these entries even when no captured voxel chunk exists.
        for (String name : ExtendedTerrainPreview.MATERIALS)
            if (!name.equals("minecraft:air")) used.add(name);
        for (String name : used) {
            string(hash, name);
            for (Direction dir : FACES) {
                var uv = materials.face(name, dir);
                if (uv == null) {
                    hash.update((byte) 0);
                    continue;
                }
                hash.update((byte) 1);
                integer(hash, Float.floatToIntBits(uv.u0()));
                integer(hash, Float.floatToIntBits(uv.v0()));
                integer(hash, Float.floatToIntBits(uv.u1()));
                integer(hash, Float.floatToIntBits(uv.v1()));
                integer(hash, uv.tint());
                integer(hash, uv.translucent() ? 1 : 0);
                integer(hash, uv.emission());
            }
            var models = materials.model(name);
            integer(hash, models == null ? -1 : models.length);
            if (models == null) continue;
            for (var model : models) {
                integer(hash, model.tint());
                integer(hash, model.shade());
                integer(hash, model.emission());
                integer(hash, model.cull() == null ? -1 : model.cull().ordinal());
                integer(hash, model.vertices().length);
                for (float vertex : model.vertices()) integer(hash, Float.floatToIntBits(vertex));
            }
        }
        return hash.digest();
    }

    private static void string(MessageDigest hash, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        integer(hash, bytes.length);
        hash.update(bytes);
    }

    private static void integer(MessageDigest hash, int value) {
        hash.update((byte) (value >>> 24));
        hash.update((byte) (value >>> 16));
        hash.update((byte) (value >>> 8));
        hash.update((byte) value);
    }
}
