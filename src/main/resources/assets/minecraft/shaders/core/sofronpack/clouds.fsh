#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 Pos;
layout(location = 3) in vec3 PlayerPos;
layout(location = 4) in vec3 undersideTint;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 sofronCloudColor() {
    // Keep the real illuminated cloud colour: white, with a gently
    // biome-tinted underside. Never blend half of the sky fog colour into
    // the cloud face; that is what made the banks lose their contrast.
    vec4 color = vertexColor;
    color.rgb = clamp(color.rgb * undersideTint, 0.0, 1.0);

#ifndef OIT_DEPTH_BOUNDS
    float fogProgress = clamp(
        linear_fog_value(vertexDistance, 0.0, FogCloudsEnd), 0.0, 1.0
    );
    // Only the farthest fringe takes atmospheric colour before it fades;
    // the body of the cloud stays visibly lighter than the sky.
    float horizon = smoothstep(0.68, 1.0, fogProgress);
    color.rgb = mix(color.rgb, FogColor.rgb, horizon * 0.16);
    color.a *= 1.0 - fogProgress;
#endif

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
