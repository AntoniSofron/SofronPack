package pl.sofron.extended;

/**
 * Horizontal, camera-relative, four-stage distant-terrain fog. These values
 * are mirrored in extended_voxel.fsh; no chunk can abruptly appear or vanish
 * at a fog threshold.
 */
public final class ExtendedLodFog {
    public static final int START = 16;
    public static final int STAGE_TWO = 32;
    public static final int STAGE_THREE = 64;
    public static final int STAGE_FOUR = 96;
    public static final int END = 128;

    private ExtendedLodFog() {}

    public static float opacity(double chunks) {
        if (!Double.isFinite(chunks)) return chunks > 0 ? 1f : 0f;
        if (chunks < START) return 0f;
        if (chunks < STAGE_TWO) return segment(chunks, START, STAGE_TWO, 0.02f, 0.13f);
        if (chunks < STAGE_THREE) return segment(chunks, STAGE_TWO, STAGE_THREE, 0.13f, 0.55f);
        if (chunks < STAGE_FOUR) return segment(chunks, STAGE_THREE, STAGE_FOUR, 0.55f, 0.85f);
        if (chunks < END) return segment(chunks, STAGE_FOUR, END, 0.85f, 1f);
        return 1f;
    }

    private static float segment(double d, double start, double end, float from, float to) {
        double t = Math.clamp((d - start) / (end - start), 0, 1);
        t = t * t * (3 - 2 * t); // C1-continuous smoothstep between milestones
        return (float)(from + (to - from) * t);
    }
}
