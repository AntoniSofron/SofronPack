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
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 sampled = tileUv.z > 0.0
            ? textureGrad(Sampler0, tileUv.xy + fract(texCoord0) * tileUv.zw,
                    dFdx(texCoord0) * tileUv.zw, dFdy(texCoord0) * tileUv.zw)
            : texture(Sampler0, texCoord0);
    sampled *= vertexColor;
    if (sampled.a < 0.05) discard;

    // CPU suppresses fully ready close tiles. For partially loaded tiles,
    // native terrain wins the ordinary depth test. No per-tile readiness
    // uniform is necessary, avoiding thousands of GPU transforms.

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
