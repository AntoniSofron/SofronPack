// The Sofron 16x pack's dark-light transfer curve.
// Keep this in our own namespace: do not replace vanilla general.glsl.
float sofronPackDarkLight(float level, float ambient) {
    float darkness = 1.0 - level;
    float value = level / (darkness * 3.0 + 1.0)
        * (1.0 - ambient) + ambient;
    float shadow = smoothstep(0.0, 0.65, darkness);
    return value * mix(1.0, 0.68, shadow);
}
