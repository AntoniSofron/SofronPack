package pl.sofron.extended;

/**
 * Distance-dependent DISPLAY resolution of captured distant-world chunks.
 * Source snapshots retain their full 1x1 block palette and exact height;
 * only the generated GPU meshes are reduced horizontally at long distances.
 *
 *   0..48 chunks: 1x1, 49..96: 2x2, 97..128: 4x4
 *
 * Fog and source-world generation are independent of these display rings.
 */
public final class ExtendedLodLevels {
    private ExtendedLodLevels() {}

    public static final int FAR = 128;

    public static int voxelSize(int distanceChunks) {
        return 1 << ringFor(distanceChunks);
    }

    /** First visible mesh: at most 4-block voxels before a later full-detail rebuild. */
    public static int firstPaintVoxelSize(int distanceChunks) {
        return Math.max(4, voxelSize(distanceChunks));
    }

    public static int ringFor(int distanceChunks) {
        if (distanceChunks <= 48) return 0;
        if (distanceChunks <= 96) return 1;
        return 2;
    }

    /**
     * Normal chunk-like (Chebyshev) XZ distance, matching the per-chunk
     * packed detail map used by both the preview and the volume mesher.
     */
    public static int ringForChunk(int chunkX, int chunkZ, int cameraX, int cameraZ) {
        return ringFor(Math.max(Math.abs(chunkX - cameraX), Math.abs(chunkZ - cameraZ)));
    }
}
