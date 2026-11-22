#ifndef SOFRONPACK_EXTENDED_FOG
#define SOFRONPACK_EXTENDED_FOG

// One shared GPU fog function for Sodium chunks and distant world LOD.
// Control points: 0/8/16/32/64/96/128 chunks; see ExtendedLodFog.java.
float sofronFogSegment(float distanceChunks, float startChunks, float endChunks,
                       float low, float high) {
    float t = clamp((distanceChunks - startChunks) / max(endChunks - startChunks, 0.001),
                    0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(low, high, t);
}

// 2048 blocks = clear; 1024 blocks = maximal storm/cave influence.
float sofronFogStrength(float environmentalEndBlocks) {
    return clamp((2048.0 - environmentalEndBlocks) / 1024.0, 0.0, 1.0);
}

// Encoded by ExtendedWorldFogMixin: 256 + 1024 * altitudeBonus.
float sofronAltitudeFogBonus(float environmentalStartBlocks) {
    return clamp((environmentalStartBlocks - 256.0) / 1024.0, 0.0, 1.0);
}

// Two smoothstep curves interpolated by weather/cave influence.
float sofronExtendedFog(float distanceChunks, float strength) {
    strength = clamp(strength, 0.0, 1.0);
    if (distanceChunks < 8.0)
        return sofronFogSegment(distanceChunks, 0.0, 8.0, 0.0, mix(0.0, 0.08, strength));
    if (distanceChunks < 16.0)
        return sofronFogSegment(distanceChunks, 8.0, 16.0,
                mix(0.0, 0.08, strength), mix(0.08, 0.32, strength));
    if (distanceChunks < 32.0)
        return sofronFogSegment(distanceChunks, 16.0, 32.0,
                mix(0.08, 0.32, strength), mix(0.16, 0.64, strength));
    if (distanceChunks < 64.0)
        return sofronFogSegment(distanceChunks, 32.0, 64.0,
                mix(0.16, 0.64, strength), mix(0.32, 0.86, strength));
    if (distanceChunks < 96.0)
        return sofronFogSegment(distanceChunks, 64.0, 96.0,
                mix(0.32, 0.86, strength), mix(0.64, 0.92, strength));
    if (distanceChunks < 128.0)
        return sofronFogSegment(distanceChunks, 96.0, 128.0,
                mix(0.64, 0.92, strength), 1.0);
    return 1.0;
}

// Ordinary, altitude-independent distance haze. Smoothstep grows very slowly
// immediately beyond 8 chunks and reaches 100% exactly at 128 chunks.
float sofronRegularDistanceFog(float distanceChunks) {
    float t = clamp((distanceChunks - 8.0) / 120.0, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Volumetric fog cannot cover anything closer than 2 horizontal chunks.
// Fade in smoothly between 2 and 6 chunks to avoid a visible ring.
float sofronVolumetricNearFade(float distanceChunks) {
    float t = clamp((distanceChunks - 2.0) / 4.0, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Keep the established weather curve and exponential vertical density. The
// Java-side Y bonus now changes continuously across cloud/underground levels.
float sofronVolumeFogAtDistance(vec3 relative, float distanceChunks,
                                float environmentalStart, float environmentalEnd) {
    float nearFade = sofronVolumetricNearFade(distanceChunks);
    if (nearFade <= 0.0) return 0.0;
    float base = sofronExtendedFog(distanceChunks, sofronFogStrength(environmentalEnd));
    base = clamp(base + sofronAltitudeFogBonus(environmentalStart), 0.0, 1.0);
    if (base <= 0.0 || base >= 1.0) return base * nearFade;
    float h = clamp(relative.y / 384.0, -1.0, 1.0);
    float density = abs(h) < 0.01 ? 1.0 - 0.5 * h : (1.0 - exp(-h)) / h;
    return (1.0 - pow(1.0 - base, density)) * nearFade;
}

float sofronVolumeFog(vec3 relative, float environmentalStart, float environmentalEnd) {
    return sofronVolumeFogAtDistance(relative, length(relative.xz) / 16.0,
                                     environmentalStart, environmentalEnd);
}

// Layer the ordinary fog over the volumetric fog rather than replacing it.
// Compositing via transmittance avoids a hard max() boundary and preserves
// smooth changes as either layer's opacity varies. Compute distance only once.
float sofronCombinedFog(vec3 relative, float environmentalStart, float environmentalEnd) {
    float chunks = length(relative.xz) / 16.0;
    float ordinary = sofronRegularDistanceFog(chunks);
    float volumetric = sofronVolumeFogAtDistance(relative, chunks,
                                                  environmentalStart, environmentalEnd);
    return 1.0 - (1.0 - ordinary) * (1.0 - volumetric);
}
#endif
