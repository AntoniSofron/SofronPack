#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 Pos;
layout(location = 3) in vec3 PlayerPos;
layout(location = 4) in vec4 undersideTint;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 sofronCloudColor() {
    vec4 color = vertexColor;

    // Sofron fog/cloud blend
    color = clamp(
        mix(color, FogColor, 0.5),
        0.0,
        0.9
    );

    // W fazie depth-bounds nie nakładamy zwykłego fog fade
#ifndef OIT_DEPTH_BOUNDS
    color.a *=
        1.0 -
        linear_fog_value(
            vertexDistance,
            0.0,
            FogCloudsEnd
        );
#endif

    // Pionowy gradient
    float fadeDirection = -10.0;

    float fadeIntensity =
        abs(PlayerPos.y / 40.0);

    float topFadeHeight =
        1.0 - ((10.0 - Pos.y) / 10.0);

    float bottomFadeHeight =
        1.0 - (Pos.y / 20.0);

    float fadeHeight = mix(
        topFadeHeight,
        bottomFadeHeight,
        clamp(fadeDirection, 0.2, 1.0)
    );

    float fadeFinal = mix(
        1.0,
        fadeHeight,
        clamp(fadeIntensity, 0.0, 1.0)
    );

    vec3 topColor = mix(
        vec3(1.3),
        FogColor.rgb,
        0.25
    );

    vec3 bottomColor = mix(
        vec3(0.85, 0.9, 1.3),
        FogColor.rgb,
        0.25
    );

    color.rgb *= mix(
        bottomColor,
        topColor,
        fadeFinal
    );

    color.a *= mix(
        1.0,
        0.75,
        fadeFinal
    );

    color.rgb = mix(color.rgb, undersideTint.rgb, undersideTint.a);
    return color;
}

vec4 calculateFinalColor(vec4 color) {
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif

    return color;
}

void main() {
    vec4 color = sofronCloudColor();

#ifdef OIT_ALPHA_ONLY

    executeAlphaOnlyPhase(
        gl_FragCoord.z,
        color.a
    );

#else

    fragColor = calculateFinalColor(color);

#endif
}
