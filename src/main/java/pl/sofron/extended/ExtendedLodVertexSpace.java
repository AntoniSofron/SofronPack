package pl.sofron.extended;

import java.nio.ByteBuffer;

/** CPU rebasing allows one shared dynamic GPU transform for all distant tiles. */
public final class ExtendedLodVertexSpace {
    public static final long REBASE_DISTANCE_BLOCKS = 8192L;
    private ExtendedLodVertexSpace() {}

    public static boolean needsRebase(long originX, long originZ, double cameraX, double cameraZ) {
        return originX == Long.MIN_VALUE || originZ == Long.MIN_VALUE
                || Math.abs(cameraX - originX) >= REBASE_DISTANCE_BLOCKS
                || Math.abs(cameraZ - originZ) >= REBASE_DISTANCE_BLOCKS;
    }

    /**
     * Input geometry is local to a four-chunk tile. Move only X/Z into the
     * stable shared origin; preserve all Y positions, UVs, tint and emission.
     */
    public static void rebase(ByteBuffer vertices, long tileWorldX, long tileWorldZ,
                              long originX, long originZ) {
        long dx = tileWorldX - originX, dz = tileWorldZ - originZ;
        if (dx == 0 && dz == 0) return;
        if (Math.abs(dx) >= 1_000_000 || Math.abs(dz) >= 1_000_000)
            throw new IllegalArgumentException("LOD tile exceeds floating origin range");
        int first = vertices.position(), last = vertices.limit();
        if ((last - first) % (4 * ExtendedVoxelMesher.VERTEX_BYTES) != 0)
            throw new IllegalArgumentException("Unaligned LOD quad buffer");
        for (int p = first; p < last; p += ExtendedVoxelMesher.VERTEX_BYTES) {
            vertices.putFloat(p, vertices.getFloat(p) + dx);
            vertices.putFloat(p + 8, vertices.getFloat(p + 8) + dz);
        }
    }
}
