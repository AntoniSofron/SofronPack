#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <sofronpack:extended_fog.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float horizontalChunks;
layout(location = 3) in float sphericalBlocks;
layout(location = 4) in float cylindricalBlocks;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 sampled = texture(Sampler0, texCoord0) * vertexColor;
    if (sampled.a < 0.05) discard;

    // Dynamic per-pixel handoff: an old cached buffer must stop drawing the
    // instant the camera moves inside 7/8 of the normal render distance.
    if (FogRenderDistanceEnd >= 2047.5 &&
            horizontalChunks < FogRenderDistanceStart / 16.0) discard;

    // Same four-zone function as the native Sodium terrain shader.
    float fog = FogRenderDistanceEnd >= 2047.5
            ? sofronExtendedFog(horizontalChunks, sofronFogStrength(FogEnvironmentalEnd))
            : max(linear_fog_value(sphericalBlocks, FogEnvironmentalStart, FogEnvironmentalEnd),
                  linear_fog_value(cylindricalBlocks, FogRenderDistanceStart, FogRenderDistanceEnd));
    // FogColor comes from the live blended biome/environment, not a fixed hue.
    sampled.rgb = mix(sampled.rgb, FogColor.rgb, fog);
    // At 128 chunks even semi-transparent water must disappear completely
    // into the surrounding fog, rather than retaining a distant silhouette.
    sampled.a = mix(sampled.a, 1.0, fog * fog);
    fragColor = sampled;
}
