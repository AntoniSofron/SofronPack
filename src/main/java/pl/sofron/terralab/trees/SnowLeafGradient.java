package pl.sofron.terralab.trees;

/** Eight discrete white-tint steps; world and voxel meshes share the same arithmetic. */
public final class SnowLeafGradient {
    public static final int DEPTH = 8;
    private SnowLeafGradient() {}
    public static boolean eligible(String id) {
        if (id == null) return false;
        int end = id.indexOf('[');
        if (end < 0) end = id.length();
        int start = id.indexOf(':') + 1;
        return end >= start + 7 && id.regionMatches(end - 7, "_leaves", 0, 7)
                && !id.regionMatches(start, "cherry_", 0, 7)
                && !id.contains("azalea") && !id.contains("poplar");
    }
    public static boolean snow(String id) {
        return id != null && (id.equals("minecraft:snow") || id.startsWith("minecraft:snow[")
                || id.equals("minecraft:snow_block") || id.startsWith("minecraft:snow_block["));
    }
    public static int blend(int rgb, int whiteSteps) {
        if (whiteSteps <= 0) return rgb;
        int w = Math.min(DEPTH, whiteSteps), n = DEPTH - w;
        int r = ((rgb >>> 16 & 255) * n + 255 * w + 4) / DEPTH;
        int g = ((rgb >>> 8 & 255) * n + 255 * w + 4) / DEPTH;
        int b = ((rgb & 255) * n + 255 * w + 4) / DEPTH;
        return (rgb & 0xff000000) | r << 16 | g << 8 | b;
    }
    /** Null for snow-free/non-leaf chunks; scan only actual snow blocks, not every leaf face. */
    public static byte[] voxelSteps(String[] palette, short[] cells) {
        byte[] kinds = new byte[palette.length];
        boolean hasSnow = false, hasLeaves = false;
        for (int i = 1; i < palette.length; i++) {
            if (snow(palette[i])) { kinds[i] = 1; hasSnow = true; }
            else if (eligible(palette[i])) { kinds[i] = 2; hasLeaves = true; }
        }
        if (!hasSnow || !hasLeaves) return null;
        byte[] steps = null;
        for (int i = 256; i < cells.length; i++) {
            if (kinds[cells[i] & 0xffff] != 1) continue;
            for (int j = 1; j <= DEPTH && i >= j * 256; j++) {
                int at = i - j * 256;
                if (kinds[cells[at] & 0xffff] != 2) break;
                if (steps == null) steps = new byte[cells.length];
                steps[at] = (byte)(DEPTH + 1 - j);
            }
        }
        return steps;
    }
}
