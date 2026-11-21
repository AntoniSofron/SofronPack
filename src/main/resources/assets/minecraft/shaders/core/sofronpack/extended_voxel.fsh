#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float horizontalChunks;

layout(location = 0) out vec4 fragColor;

float sofronSmooth(float d, float start, float end, float low, float high) {
    float t = clamp((d - start) / (end - start), 0.0, 1.0);
    t = t * t * (3.0 - 2.0 * t);
    return mix(low, high, t);
}

float sofronLodFog(float chunks) {
    if (chunks < 16.0) return 0.0;
    if (chunks < 32.0) return sofronSmooth(chunks, 16.0, 32.0, 0.02, 0.13);
    if (chunks < 64.0) return sofronSmooth(chunks, 32.0, 64.0, 0.13, 0.55);
    if (chunks < 96.0) return sofronSmooth(chunks, 64.0, 96.0, 0.55, 0.85);
    if (chunks < 128.0) return sofronSmooth(chunks, 96.0, 128.0, 0.85, 1.0);
    return 1.0;
}

void main() {
    vec4 sampled = texture(Sampler0, texCoord0) * vertexColor;
    if (sampled.a < 0.05) discard;

    float fog = sofronLodFog(horizontalChunks);
    // FogColor comes from Minecraft's live Fog uniform, including SofronPack
    // biome/weather overrides. It is NOT a hard-coded grey or blue tint.
    sampled.rgb = mix(sampled.rgb, FogColor.rgb, fog);
    // At 128 chunks even semi-transparent water must disappear completely
    // into the surrounding fog, rather than retaining a distant silhouette.
    sampled.a = mix(sampled.a, 1.0, fog * fog);
    fragColor = sampled;
}
