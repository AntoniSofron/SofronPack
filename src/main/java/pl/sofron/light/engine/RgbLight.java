package pl.sofron.light.engine;

public record RgbLight(float red, float green, float blue) {
    public static final RgbLight BLACK = new RgbLight(0f, 0f, 0f);

    public RgbLight {
        red = clamp(red); green = clamp(green); blue = clamp(blue);
    }
    private static float clamp(float x) { return Float.isNaN(x) ? 0f : Math.max(0f, Math.min(1f, x)); }

    public RgbLight scale(float factor) {
        return new RgbLight(red * factor, green * factor, blue * factor);
    }

    public RgbLight add(RgbLight other) {
        return new RgbLight(red + other.red, green + other.green, blue + other.blue);
    }
}
