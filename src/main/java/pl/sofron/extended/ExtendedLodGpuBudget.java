package pl.sofron.extended;

/**
 * Hard device-memory limit for the custom distant-world VBO cache.
 *
 * This budget is intentionally separate from Sodium's own ordinary-chunk
 * arena, lightmaps, texture atlases and Minecraft's dynamic uniform buffers.
 * On a 12 GiB GPU the previous 3,600-tile count-only cap permitted tens of
 * GiB of allocations and could crash Sodium when it tried to grow its arena.
 */
public final class ExtendedLodGpuBudget {
    /** 256 MiB for ALL resident custom LOD vertex buffers combined. */
    public static final long MAX_VERTEX_BYTES = 256L * 1024 * 1024;
    /** Never let one pathological volume consume the entire distant cache. */
    public static final long MAX_TILE_BYTES = 24L * 1024 * 1024;
    /** Bound per-tile dynamic transform allocations, not only VBO bytes. */
    public static final int MAX_RESIDENT_TILES = 128;
    /** Other render passes need dynamic transforms as well. */
    public static final int MAX_DRAWN_TILES = 64;

    public static final int DEFAULT_NORMAL_RENDER_CAP = 20;

    private ExtendedLodGpuBudget() {}

    /** Only applied in Extended Overworld; 0 disables the effective cap. */
    public static int effectiveNormalDistance(int requested, int normalRenderCap) {
        if (normalRenderCap <= 0) return requested;
        return Math.min(requested, Math.clamp(normalRenderCap, 2, 32));
    }

    public static long vertexBytesForQuads(int quads) {
        if (quads < 0) throw new IllegalArgumentException("Negative quad count");
        return (long) quads * 4L * ExtendedVoxelMesher.VERTEX_BYTES;
    }

    public static boolean acceptableTile(long bytes) {
        return bytes > 0 && bytes <= MAX_TILE_BYTES;
    }

    /** Allocation peak includes the replacement VBO until it can be closed. */
    public static boolean needsEviction(long allocated, long incoming, int resultingTiles) {
        return allocated + incoming > MAX_VERTEX_BYTES || resultingTiles > MAX_RESIDENT_TILES;
    }
}
