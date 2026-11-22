package pl.sofron.extended;

/**
 * Distance-dependent DISPLAY resolution of captured distant-world chunks.
 * Source snapshots retain their full 1x1 block palette and exact height;
 * only the generated GPU meshes are reduced horizontally at long distances.
 *
 *   0..40 chunks: 1x1, 41..64: 2x2, 65..96: 4x4, 97..128: 8x8
 *
 * Fog and source-world generation are independent of these display rings.
 */
public final class ExtendedLodLevels {
    private ExtendedLodLevels() {}

    public static final int FAR = 128;

    public static int voxelSize(int distanceChunks) {
        return 1 << ringFor(distanceChunks);
    }

    /**
     * Opaque vertical SIDE faces use this coarse slab size. All near
     * geometry (0–40 chunks) stays at EXACT 1-block Y; all top/bottom
     * faces, fluids, foliage and cutouts retain exact Y even at distance.
     * Source voxel snapshots are always full-height, 1-block precision.
     */
    public static int opaqueSideYSize(int distanceChunks) {
        return 1 << ringFor(distanceChunks);
    }

    /** Preview-first world sampling: coarse nearby, never finer than the final ring. */
    public static int firstPaintVoxelSize(int distanceChunks) {
        return Math.max(4, voxelSize(distanceChunks));
    }

    public static int ringFor(int distanceChunks) {
        if (distanceChunks <= 40) return 0;
        if (distanceChunks <= 64) return 1;
        if (distanceChunks <= 96) return 2;
        return 3;
    }

    /**
     * Each 4x4-chunk tile stores sixteen two-bit display levels in a long.
     * A tile needs a background reduction if ANY chunk has moved into a
     * coarser ring; finer chunks can be upgraded on demand in the view cone.
     */
    public static boolean requiresCoarsening(long displayed, long wanted) {
        for (int shift = 0; shift < 32; shift += 2)
            if (((wanted >>> shift) & 3L) > ((displayed >>> shift) & 3L))
                return true;
        return false;
    }

    /**
     * Normal chunk-like (Chebyshev) XZ distance, matching the per-chunk
     * packed detail map used by both the preview and the volume mesher.
     */
    public static int ringForChunk(int chunkX, int chunkZ, int cameraX, int cameraZ) {
        return ringFor(Math.max(Math.abs(chunkX - cameraX), Math.abs(chunkZ - cameraZ)));
    }
}
