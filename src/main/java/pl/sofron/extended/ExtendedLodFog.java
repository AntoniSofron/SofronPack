package pl.sofron.extended;

import pl.sofron.atmosphere.CloudBandLayout;

/** Shared seven-point horizontal fog profile; GLSL mirror is extended_fog.glsl. */
public final class ExtendedLodFog {
    public static final int FIRST_BAND = 8;
    // Retain the older named landmarks for source compatibility.
    public static final int START = 16, STAGE_TWO = 32, STAGE_THREE = 64;
    public static final int STAGE_FOUR = 96, END = 128;
    private static final double[] DISTANCES = {0, 8, 16, 32, 64, 96, 128};
    private static final float[] CLEAR = {0f, 0f, .08f, .16f, .32f, .64f, 1f};
    private static final float[] STORM = {0f, .08f, .32f, .64f, .86f, .92f, 1f};
    private static final float ENCODED_START_BASE = 256f;
    private static final float ENCODED_ALTITUDE_SCALE = 1024f;

    private ExtendedLodFog() {}

    public static float opacity(double chunks) { return opacity(chunks, 0f); }

    /** Interpolate clear/storm curves at the same horizontal distances. */
    public static float opacity(double chunks, float strength) {
        if (!Double.isFinite(chunks)) return chunks > 0 ? 1f : 0f;
        float w = Float.isFinite(strength) ? Math.clamp(strength, 0f, 1f) : 0f;
        float clear = curve(chunks, CLEAR);
        float storm = curve(chunks, STORM);
        return clear + (storm - clear) * w;
    }

    public static float opacity(double chunks, float strength, double cameraY) {
        return Math.clamp(opacity(chunks, strength) + altitudeBonus(cameraY), 0f, 1f);
    }

    /**
     * Cloud decks are at Y=256, 384, 512: +8%, +16%, +24%, respectively.
     * Below Y=60 add 8% for each commenced 40-block band:
     * Y=59..20 -> +8%, 19..-20 -> +16%, -21..-60 -> +24%, etc.
     */
    public static float altitudeBonus(double cameraY) {
        if (!Double.isFinite(cameraY)) return 0f;
        float cloud = cameraY >= CloudBandLayout.HIGH ? .24f
                : cameraY >= CloudBandLayout.MIDDLE ? .16f
                : cameraY >= CloudBandLayout.LOW ? .08f : 0f;
        double steps = cameraY < 60.0 ? Math.ceil((60.0 - cameraY) / 40.0) : 0.0;
        float below = (float) Math.min(1.0, steps * .08);
        return Math.clamp(cloud + below, 0f, 1f);
    }

    /**
     * Encode the altitude bonus in the existing FogData environmentalStart.
     * Both terrain shaders receive this value without new GPU uniforms.
     * Only the opt-in Extended World fog path uses the encoding.
     */
    public static float environmentalStartForAltitude(double cameraY) {
        return ENCODED_START_BASE + ENCODED_ALTITUDE_SCALE * altitudeBonus(cameraY);
    }

    public static float altitudeBonusFromEnvironmentalStart(float start) {
        return Float.isFinite(start)
                ? Math.clamp((start - ENCODED_START_BASE) / ENCODED_ALTITUDE_SCALE, 0f, 1f) : 0f;
    }

    /** Existing 2048..1024 environmental-end weather/cave encoding. */
    public static float strengthFromEnvironmentalEnd(float endBlocks) {
        return Float.isFinite(endBlocks)
                ? Math.clamp((2048f - endBlocks) / 1024f, 0f, 1f) : 0f;
    }

    private static float curve(double d, float[] opacity) {
        if (d <= 0.0) return opacity[0];
        for (int i = 1; i < DISTANCES.length; i++) {
            if (d <= DISTANCES[i])
                return segment(d, DISTANCES[i - 1], DISTANCES[i], opacity[i - 1], opacity[i]);
        }
        return 1f;
    }

    private static float segment(double d, double a, double b, float low, float high) {
        double t = Math.clamp((d - a) / (b - a), 0d, 1d);
        t = t * t * (3d - 2d * t);
        return (float)(low + (high - low) * t);
    }
}
