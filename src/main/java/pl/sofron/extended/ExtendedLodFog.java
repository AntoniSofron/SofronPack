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
        float raw = Math.clamp(opacity(chunks, strength) + altitudeBonus(cameraY), 0f, 1f);
        return raw * volumetricNearFade(chunks);
    }

    /**
     * A hard 2-chunk exclusion radius around the camera for volumetric fog.
     * From 2 to 6 chunks it rises smoothly to its original intensity, with
     * no abrupt boundary at the 2-chunk mark.
     */
    public static float volumetricNearFade(double chunks) {
        if (!Double.isFinite(chunks)) return chunks > 0 ? 1f : 0f;
        return (float)smoothstep((chunks - 2.0) / 4.0);
    }

    /**
     * Ordinary, non-volumetric distance fog begins gently at 8 chunks and
     * reaches full opacity at 128 chunks, irrespective of the camera altitude.
     * It uses horizontal distance, so looking up/down does not shift the horizon.
     */
    public static float regularOpacity(double chunks) {
        if (!Double.isFinite(chunks)) return chunks > 0 ? 1f : 0f;
        double t = Math.clamp((chunks - 8.0) / 120.0, 0.0, 1.0);
        return (float) (t * t * (3.0 - 2.0 * t));
    }

    /**
     * Reference opacity of the two independent fog layers combined; the shader
     * additionally integrates the existing volumetric vertical-ray profile.
     */
    public static float combinedOpacity(double chunks, float strength, double cameraY) {
        float volume = opacity(chunks, strength, cameraY);
        float regular = regularOpacity(chunks);
        return 1f - (1f - volume) * (1f - regular);
    }

    /**
     * Cloud bonuses rise by 8% around each existing deck Y=256/384/512.
     * Their 32-block transition windows eliminate visible horizontal bands.
     *
     * Underground bonuses still advance by 8% every 40 blocks below Y=60,
     * but each increment fades in across the first 16 blocks of its band.
     * Both calculations are continuous (including at negative Y).
     */
    public static float altitudeBonus(double cameraY) {
        if (!Double.isFinite(cameraY)) return 0f;
        double cloud = .08 * (
                smoothstep((cameraY - (CloudBandLayout.LOW - 16.0)) / 32.0)
                + smoothstep((cameraY - (CloudBandLayout.MIDDLE - 16.0)) / 32.0)
                + smoothstep((cameraY - (CloudBandLayout.HIGH - 16.0)) / 32.0));
        double underground = 0.0;
        if (cameraY < 60.0) {
            double depth = 60.0 - cameraY;
            double completed = Math.floor(depth / 40.0);
            double progress = (depth - completed * 40.0) / 16.0;
            underground = .08 * (completed + smoothstep(progress));
        }
        return (float)Math.clamp(cloud + underground, 0.0, 1.0);
    }

    private static double smoothstep(double t) {
        t = Math.clamp(t, 0d, 1d);
        return t * t * (3d - 2d * t);
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
