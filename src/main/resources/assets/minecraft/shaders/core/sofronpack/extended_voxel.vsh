#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 fogRelative;
layout(location = 3) out float sphericalBlocks;
layout(location = 4) out float cylindricalBlocks;

layout(location = 5) flat out vec4 tileUv;
vec2 unpackRange(float value) {
    uint bits = floatBitsToUint(value) - 0x00800000u;
    return vec2(bits & 32767u, bits >> 15u) / 32767.0;
}

uniform sampler2D Sampler2;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    tileUv = vec4(0.0);
    int emission = 0;
    int material = int(round(Color.a * 255.0));
    if (material >= 128 && material <= 143) {
        emission = material - 128;
    } else if (material < 255) {
        vec2 u = unpackRange(UV0.x), v = unpackRange(UV0.y);
        tileUv = vec4(u.x, v.x, u.y - u.x, v.y - v.x);
        int flags = 254 - int(round(Color.a * 255.0));
        int axis = flags % 3;
        emission = flags / 3;
        texCoord0 = axis == 0 ? Position.xz : axis == 1
                ? vec2(Position.x, -Position.y) : vec2(Position.z, -Position.y);
    }
    vertexColor = vec4(Color.rgb * texelFetch(Sampler2, ivec2(emission, 15), 0).rgb, 1.0);

    // The entire LOD horizon uses one floating-origin ModelOffset.
    // Position includes each tile's CPU-baked origin, so the scene
    // retains camera-exact horizontal fog independent of camera pitch.
    vec3 relative = Position + ModelOffset;
    fogRelative = relative;
    sphericalBlocks = length(relative);
    cylindricalBlocks = max(length(relative.xz), abs(relative.y));
}
