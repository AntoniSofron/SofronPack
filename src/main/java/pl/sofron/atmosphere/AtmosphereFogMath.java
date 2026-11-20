package pl.sofron.atmosphere;

final class AtmosphereFogMath {
    private AtmosphereFogMath() {}

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static double smoothstep(double left, double right, double value) {
        double t = clamp01((value - left) / (right - left));
        return t * t * (3.0 - 2.0 * t);
    }

    // Desert biomes do not render rain, but global rain/thunder levels still
    // control the atmosphere's weather expressions.
    static double stormStrength(double rain, double thunder) {
        return clamp01(Math.max(rain, thunder));
    }

    // Underground fog develops progressively beneath the terrain surface.
    // Light from open shafts suppresses it, preventing fog in open valleys.
    static double caveWeight(double depthBelowSurface, int skyLight) {
        double depth = smoothstep(4.0, 20.0, depthBelowSurface);
        double roof = 1.0 - smoothstep(4.0, 13.0, skyLight);
        return clamp01(depth * roof);
    }

    static double interpolate(double a, double b, double weight) {
        return a + (b - a) * clamp01(weight);
    }
}
