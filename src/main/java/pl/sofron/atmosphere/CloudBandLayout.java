package pl.sofron.atmosphere;

/** Stable Overworld cloud band heights; each band has an independent cloud mesh. */
public final class CloudBandLayout {
    public static final float LOW = 256.0f;
    public static final float MIDDLE = 384.0f;
    public static final float HIGH = 512.0f;
    public static final int BAND_COUNT = 3;

    private CloudBandLayout() {}

    public static float[] heights() { return new float[] {LOW, MIDDLE, HIGH}; }

    /** Sorted back to front relative to camera altitude for translucent cloud draws. */
    public static int[] farToNear(float cameraY) {
        float[] h = heights();
        int[] indices = {0, 1, 2};
        for (int i = 0; i < indices.length; i++) {
            for (int j = i + 1; j < indices.length; j++) {
                if (Math.abs(cameraY - h[indices[j]]) > Math.abs(cameraY - h[indices[i]])) {
                    int old = indices[i]; indices[i] = indices[j]; indices[j] = old;
                }
            }
        }
        return indices;
    }
}
