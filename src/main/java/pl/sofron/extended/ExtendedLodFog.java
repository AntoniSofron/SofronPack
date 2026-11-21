package pl.sofron.extended;

/** Four-zone shared distance fog; GLSL mirror is sofronpack:extended_fog.glsl. */
public final class ExtendedLodFog {
    public static final int START = 16, STAGE_TWO = 32, STAGE_THREE = 64;
    public static final int STAGE_FOUR = 96, END = 128;

    private ExtendedLodFog() {}

    public static float opacity(double chunks) { return opacity(chunks, 0f); }

    public static float opacity(double chunks, float strength) {
        if (!Double.isFinite(chunks)) return chunks > 0 ? 1f : 0f;
        double w = Float.isFinite(strength) ? Math.clamp(strength, 0f, 1f) : 0;
        double a = mix(16, 8, w), b = mix(32, 16, w), c = mix(64, 32, w);
        double d = mix(96, 56, w), e = mix(128, 64, w);
        if (chunks < a) return 0f;
        if (chunks < b) return segment(chunks, a, b, 0f, 0.13f);
        if (chunks < c) return segment(chunks, b, c, 0.13f, 0.55f);
        if (chunks < d) return segment(chunks, c, d, 0.55f, 0.85f);
        if (chunks < e) return segment(chunks, d, e, 0.85f, 1f);
        return 1f;
    }

    public static float strengthFromEnvironmentalEnd(float endBlocks) {
        return Float.isFinite(endBlocks)
                ? Math.clamp((2048f - endBlocks) / 1024f, 0f, 1f) : 0f;
    }

    private static double mix(double a, double b, double t) { return a + (b - a) * t; }

    private static float segment(double d, double start, double end, float low, float high) {
        double t = Math.clamp((d - start) / (end - start), 0d, 1d);
        t = t * t * (3 - 2 * t);
        return (float)(low + (high - low) * t);
    }
}
