package pl.sofron.extended;

/**
 * Five continuous, near-to-far voxel resolution bands.
 * Fog and detail thresholds are independent of the ordinary render distance.
 */
public final class ExtendedLodLevels {
    private ExtendedLodLevels() {}

    public static final int FAR = 128;

    public static int voxelSize(int distanceChunks) {
        if (distanceChunks <= 32) return 1;
        if (distanceChunks <= 48) return 2;
        if (distanceChunks <= 64) return 4;
        if (distanceChunks <= 96) return 8;
        return 16;
    }

    public static int ringFor(int distanceChunks) {
        if (distanceChunks <= 32) return 0;
        if (distanceChunks <= 48) return 1;
        if (distanceChunks <= 64) return 2;
        if (distanceChunks <= 96) return 3;
        return 4;
    }
}
