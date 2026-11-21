package pl.sofron.extended;

/** Where cached distant tiles yield to the normal loaded-chunk renderer. */
public final class ExtendedLodHandoff {
    private ExtendedLodHandoff() {}

    public static float nearStartChunks(int renderChunks) {
        return Math.max(1f, Math.max(2, renderChunks) * (7f / 8f));
    }

    public static boolean hidden(double horizontalChunks, int renderChunks) {
        return horizontalChunks < nearStartChunks(renderChunks);
    }
}
