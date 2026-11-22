#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

// 48 bytes PER QUAD at instance rate, rather than 96 bytes per quad
// (four 24-byte vertices). The fixed six-index quad buffer reconstructs
// its four corners with gl_VertexID; works with RenderPearl OpenGL/Vulkan.
layout(location = 0) in uvec4 PackedA;
layout(location = 1) in uvec4 PackedB;
layout(location = 2) in uvec4 PackedC;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 fogRelative;
layout(location = 3) out float sphericalBlocks;
layout(location = 4) out float cylindricalBlocks;
layout(location = 5) flat out vec4 tileUv;
layout(location = 6) out vec2 tileXZ;
layout(location = 7) flat out int readyMask;

uniform sampler2D Sampler2;

// The lower 24 bytes store 12 signed fixed-point 16-bit numbers:
// 4 vertices * (x, y, z), at 1/32-block precision.
uint positionWord(int index) {
    return index < 4 ? PackedA[index] : PackedB[index - 4];
}

float coordinate(int index) {
    uint word = positionWord(index >> 1);
    uint bits = ((index & 1) == 0) ? (word & 65535u) : (word >> 16u);
    // Sign extend 16-bit coordinate (world Y can be negative).
    return float(int(bits << 16u) >> 16) * (1.0 / 32.0);
}

uint uvWord(int corner) {
    return corner == 0 ? PackedB.z : corner == 1 ? PackedB.w
            : corner == 2 ? PackedC.x : PackedC.y;
}

vec2 unpackRange(float value) {
    uint bits = floatBitsToUint(value) - 0x00800000u;
    return vec2(bits & 32767u, bits >> 15u) / 32767.0;
}

void main() {
    int corner = gl_VertexID & 3;
    vec3 Position = vec3(
            coordinate(corner * 3),
            coordinate(corner * 3 + 1),
            coordinate(corner * 3 + 2));
    uint rgba = PackedC.z;
    vec4 Color = vec4(
            float(rgba & 255u),
            float((rgba >> 8u) & 255u),
            float((rgba >> 16u) & 255u),
            float((rgba >> 24u) & 255u)) * (1.0 / 255.0);

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    readyMask = int(ColorModulator.a);
    tileXZ = Position.xz;
    tileUv = vec4(0.0);
    int emission = 0;
    int material = int(round(Color.a * 255.0));

    if ((PackedC.w & 1u) != 0u) {
        // Repeating scan-merged faces: original 30-bit atlas endpoints,
        // kept bit-for-bit rather than reduced to normalized 16-bit UVs.
        vec2 u = unpackRange(uintBitsToFloat(PackedB.z));
        vec2 v = unpackRange(uintBitsToFloat(PackedB.w));
        tileUv = vec4(u.x, v.x, u.y - u.x, v.y - v.x);
        int flags = 254 - material;
        int axis = flags % 3;
        emission = flags / 3;
        texCoord0 = axis == 0 ? Position.xz : axis == 1
                ? vec2(Position.x, -Position.y) : vec2(Position.z, -Position.y);
    } else {
        // Baked model geometry, resource-pack UV, biome tint and emission.
        uint uv = uvWord(corner);
        texCoord0 = vec2(float(uv & 65535u), float(uv >> 16u)) * (1.0 / 65535.0);
        if (material >= 128 && material <= 143) emission = material - 128;
    }
    vertexColor = vec4(Color.rgb * texelFetch(Sampler2, ivec2(emission, 15), 0).rgb, 1.0);

    // ModelOffset is the exact camera-relative origin of this LOD tile.
    // Fog calculations retain the original 16/32/64/128-chunk bands.
    vec3 relative = Position + ModelOffset;
    fogRelative = relative;
    sphericalBlocks = length(relative);
    cylindricalBlocks = max(length(relative.xz), abs(relative.y));
}
