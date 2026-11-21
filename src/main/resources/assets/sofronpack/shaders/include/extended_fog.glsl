#ifndef SOFRONPACK_EXTENDED_FOG
#define SOFRONPACK_EXTENDED_FOG

// Identical 4-zone fog in Sodium's real chunk shader and native distant LOD.
// Coordinates are camera-relative HORIZONTAL chunks, independent of altitude.
float sofronFogSegment(float distanceChunks, float startChunks, float endChunks,
                       float low, float high) {
    float t = clamp((distanceChunks - startChunks) / max(endChunks - startChunks, 0.001),
                    0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(low, high, t);
}

// Environmental fog end is produced by SofronPack's blended biome, cave and
// weather rules on the CPU. Shrink all four bands consistently in heavy fog.
float sofronFogStrength(float environmentalEndBlocks) {
    return clamp((2048.0 - environmentalEndBlocks) / 1024.0, 0.0, 1.0);
}

float sofronExtendedFog(float distanceChunks, float strength) {
    strength = clamp(strength, 0.0, 1.0);
    float a = 16.0;
    float b = mix(32.0, 22.0, strength);
    float c = mix(64.0, 32.0, strength);
    float d = mix(96.0, 56.0, strength);
    float e = mix(128.0, 64.0, strength);

    if (distanceChunks < a) return sofronFogSegment(distanceChunks, 0.0, a, 0.0, 0.02 * (1.0 - strength));
    if (distanceChunks < b) return sofronFogSegment(distanceChunks, a, b, 0.02 * (1.0 - strength), 0.13);
    if (distanceChunks < c) return sofronFogSegment(distanceChunks, b, c, 0.13, 0.55);
    if (distanceChunks < d) return sofronFogSegment(distanceChunks, c, d, 0.55, 0.85);
    if (distanceChunks < e) return sofronFogSegment(distanceChunks, d, e, 0.85, 1.0);
    return 1.0;
}
// Integrate an exponential vertical density profile along the camera ray.
float sofronVolumeFog(vec3 relative, float environmentalEnd) {
    float base = sofronExtendedFog(length(relative.xz) / 16.0, sofronFogStrength(environmentalEnd));
    if (base <= 0.0 || base >= 1.0) return base;
    float h = clamp(relative.y / 384.0, -1.0, 1.0);
    float density = abs(h) < 0.01 ? 1.0 - 0.5 * h : (1.0 - exp(-h)) / h;
    return 1.0 - pow(1.0 - base, density);
}
#endif
