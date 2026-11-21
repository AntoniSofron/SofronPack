#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out float horizontalChunks;
layout(location = 3) out float sphericalBlocks;
layout(location = 4) out float atmosphereBlend;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    // Repurpose ColorModulator.a for the shared environment fog strength.
    // Source texture alpha stays intact, including leaves and water.
    vertexColor = vec4(Color.rgb * ColorModulator.rgb, Color.a);
    atmosphereBlend = ColorModulator.a;

    // ModelOffset is the *unrotated camera-relative tile origin* (world X/Z).
    // It is independent of pitch, so fog bands remain at the requested
    // horizontal distances even during high-altitude flight.
    vec3 relative = Position + ModelOffset;
    horizontalChunks = length(relative.xz) * (1.0 / 16.0);
    sphericalBlocks = length(relative);
}
