#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <sofronpack:extended_fog.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 fogRelative;
layout(location = 3) in float sphericalBlocks;
layout(location = 4) in float cylindricalBlocks;

layout(location = 5) flat in vec4 tileUv;
layout(location = 6) in vec2 tileXZ;
layout(location = 7) flat in int readyMask;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 sampled = tileUv.z > 0.0
            ? textureGrad(Sampler0, tileUv.xy + fract(texCoord0) * tileUv.zw,
                    dFdx(texCoord0) * tileUv.zw, dFdy(texCoord0) * tileUv.zw)
            : texture(Sampler0, texCoord0);
    sampled *= vertexColor;
    if (sampled.a < 0.05) discard;

    // Dynamic per-pixel handoff: an old cached buffer must stop drawing the
    // instant the camera moves inside 7/8 of the normal render distance.
    float horizontalChunks = length(fogRelative.xz) / 16.0;
    ivec2 cell = clamp(ivec2(floor(tileXZ / 16.0)), ivec2(0), ivec2(3));
    bool normalReady = (readyMask & (1 << (cell.y * 4 + cell.x))) != 0;
    if (normalReady && FogRenderDistanceEnd >= 2047.5 &&
            horizontalChunks < FogRenderDistanceStart / 16.0) discard;

    // Keep continuous backing geometry across the overlap. The ordinary
    // chunk wins the depth test wherever it actually covers the pixel.

    // Same four-zone function as the native Sodium terrain shader.
    float fog = FogRenderDistanceEnd >= 2047.5
            ? sofronVolumeFog(fogRelative, FogEnvironmentalEnd)
            : max(linear_fog_value(sphericalBlocks, FogEnvironmentalStart, FogEnvironmentalEnd),
                  linear_fog_value(cylindricalBlocks, FogRenderDistanceStart, FogRenderDistanceEnd));
    // FogColor comes from the live blended biome/environment, not a fixed hue.
    sampled.rgb = mix(sampled.rgb, FogColor.rgb, FogRenderDistanceEnd >= 2047.5 ? fog : fog * FogColor.a);
    // At 128 chunks even semi-transparent water must disappear completely
    // into the surrounding fog, rather than retaining a distant silhouette.
    sampled.a = 1.0;
    fragColor = sampled;
}
