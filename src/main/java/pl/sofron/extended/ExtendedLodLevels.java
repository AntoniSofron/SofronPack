package pl.sofron.extended;

/**
 * Three continuous, near-to-far voxel resolution bands.
 * Fog and detail thresholds are independent of the ordinary render distance.
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
        if (distanceChunks <= 86) return 1;
        return 2;
    }
}
