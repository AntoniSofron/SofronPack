#version 330
#extension GL_ARB_separate_shader_objects : require

#include <sofronpack:darker_light.glsl>

// Matches the 26.3 LightmapInfo uniform layout. Preserve night vision,
// gamma / brightness, block tint and the boss darkness overlay.
layout(std140) uniform LightmapInfo {
    float SkyFactor;
    float BlockFactor;
    float NightVisionFactor;
    float DarknessScale;
    float BossOverlayWorldDarkeningFactor;
    float BrightnessFactor;
    vec3 BlockLightTint;
    vec3 SkyLightColor;
    vec3 AmbientColor;
    vec3 NightVisionColor;
} lightmapInfo;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 sofronNotGamma(vec3 color) {
    float peak = max(max(color.x, color.y), color.z);
    if (peak <= 0.0) return vec3(0.0);
    float inverse = 1.0 - peak;
    float scaled = 1.0 - inverse * inverse * inverse;
    return color * (scaled / peak);
}

void main() {
    float blockLevel = floor(texCoord.x * 16.0) / 15.0;
    float skyLevel = floor(texCoord.y * 16.0)
        * lightmapInfo.SkyFactor / 15.0;
    float lightLevel = clamp(max(blockLevel, skyLevel), 0.0, 1.0);
    float ambient = max(
        (lightmapInfo.AmbientColor.r + lightmapInfo.AmbientColor.g
            + lightmapInfo.AmbientColor.b) / 3.0 + 0.01,
        0.05
    );
    vec3 color = vec3(sofronPackDarkLight(lightLevel, ambient));
    color = mix(color, color * max(lightLevel, 0.4),
        lightmapInfo.BossOverlayWorldDarkeningFactor);
    color = clamp(color - vec3(lightmapInfo.DarknessScale), 0.0, 1.0);
    color = mix(color, sofronNotGamma(color),
        lightmapInfo.BrightnessFactor);
    if (lightmapInfo.NightVisionFactor > 0.0) {
        color += lightmapInfo.NightVisionColor
            * lightmapInfo.NightVisionFactor;
    }
    fragColor = vec4(color, 1.0);
}
