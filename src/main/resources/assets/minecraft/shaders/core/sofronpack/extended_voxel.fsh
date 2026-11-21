#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float horizontalChunks;
layout(location = 3) in float sphericalBlocks;
layout(location = 4) in float atmosphereBlend;

layout(location = 0) out vec4 fragColor;

float sofronSmooth(float d, float start, float end, float low, float high) {
    float t = clamp((d - start) / (end - start), 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(low, high, t);
}

// Four calm-weather distance bands are preserved. Environmental fog uses
// the same live Minecraft fog uniforms as ordinary terrain (including
// SofronPack's biome, rain, thunder and cave overrides).
float sofronLodFog(float chunks) {
    if (chunks < 16.0) return 0.0;
    if (chunks < 32.0) return sofronSmooth(chunks, 16.0, 32.0, 0.0, 0.13);
    if (chunks < 64.0) return sofronSmooth(chunks, 32.0, 64.0, 0.13, 0.55);
    if (chunks < 96.0) return sofronSmooth(chunks, 64.0, 96.0, 0.55, 0.85);
    if (chunks < 128.0) return sofronSmooth(chunks, 96.0, 128.0, 0.85, 1.0);
    return 1.0;
}

void main() {
    vec4 sampled = texture(Sampler0, texCoord0) * vertexColor;
    if (sampled.a < 0.05) discard;

    float baseFog = sofronLodFog(horizontalChunks);

    // Use the SAME environmental start/end distances as normal Minecraft
    // terrain. In thick biome/weather/cave fog, a 100% fogged normal chunk
    // must not be followed by a fully visible distant LOD tile.
    //
    // Render-distance clipping is NOT carried into the distant world: only
    // the custom environment is shared. This preserves 128-chunk visibility
    // in clear weather. The atmosphereBlend signal comes from the CPU's
    // actual biome/cave/weather fog selection, never distance heuristics.
    float environmentFog = clamp(
        (sphericalBlocks - FogEnvironmentalStart)
            / max(FogEnvironmentalEnd - FogEnvironmentalStart, 0.001),
        0.0, 1.0
    );
    // Camera biome weights were ALREADY blended into FogEnvironmentalEnd.
    // Multiplying that fog by the biome weight a second time would make the
    // LOD half-transparent when the normal chunk is completely fogged.
    float unifiedEnvironment = environmentFog * step(0.001, atmosphereBlend);
    float fog = max(baseFog, unifiedEnvironment);
    // FogColor comes from the live blended biome/environment, not a fixed hue.
    sampled.rgb = mix(sampled.rgb, FogColor.rgb, fog);
    // At 128 chunks even semi-transparent water must disappear completely
    // into the surrounding fog, rather than retaining a distant silhouette.
    sampled.a = mix(sampled.a, 1.0, fog * fog);
    fragColor = sampled;
}
