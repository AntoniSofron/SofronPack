package pl.sofron.atmosphere;

/**
 * Classic fog distance rule adapted from the uploaded classic-fog resource pack.
 *
 * The old pack divides the environmental and render-distance fog starts by
 * three in its shader, without changing the corresponding ends. Modern
 * Minecraft and Sodium share FogData, so scaling these CPU values once
 * applies the same classic start to the vanilla shader path as well.
 *
 * The optional Extended World 128-chunk renderer does not use this rule:
 * its FogData starts encode the custom volumetric-altitude profile.
 */
public final class ClassicFogProfile {
    public static final float START_DIVISOR = 3f;

    private ClassicFogProfile() {}

    public static float start(float originalStart) {
        // Preserve the original pack's formula, including 0/negative values.
        // If an upstream mod uses infinity as a disabled fog sentinel, keep it.
        return Float.isFinite(originalStart)
                ? originalStart / START_DIVISOR
                : originalStart;
    }
}
