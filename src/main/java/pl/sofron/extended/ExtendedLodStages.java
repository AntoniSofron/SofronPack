package pl.sofron.extended;

/**
 * Ordered progressive terrain fill stages. The radius is Chebyshev (square
 * horizontal rings), matching vanilla chunk view and spiralOffsets().
 */
public final class ExtendedLodStages {
    public static final int[] RADII = {8, 16, 32, 48, 64, 96, 128};

    private ExtendedLodStages() {}

    public static int count() { return RADII.length; }

    public static int radius(int index) { return RADII[index]; }

    public static int endExclusive(int index) {
        int side = 2 * radius(index) + 1;
        return side * side;
    }

    public static int startInclusive(int index) {
        return index == 0 ? 0 : endExclusive(index - 1);
    }

    public static int indexForRadius(int distanceChunks) {
        for (int i = 0; i < RADII.length - 1; i++) {
            if (distanceChunks <= RADII[i]) return i;
        }
        return RADII.length - 1;
    }
}
