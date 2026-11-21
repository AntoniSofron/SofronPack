#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

uniform isamplerBuffer CloudFaces;

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 Pos;
layout(location = 3) out vec3 PlayerPos;
layout(location = 4) out vec4 undersideTint;

const vec3[] vertices = vec3[](
    vec3(1, 0, 0),
    vec3(1, 0, 1),
    vec3(0, 0, 1),
    vec3(0, 0, 0),

    vec3(0, 1, 0),
    vec3(0, 1, 1),
    vec3(1, 1, 1),
    vec3(1, 1, 0),

    vec3(0, 0, 0),
    vec3(0, 1, 0),
    vec3(1, 1, 0),
    vec3(1, 0, 0),

    vec3(1, 0, 1),
    vec3(1, 1, 1),
    vec3(0, 1, 1),
    vec3(0, 0, 1),

    vec3(0, 0, 1),
    vec3(0, 1, 1),
    vec3(0, 1, 0),
    vec3(0, 0, 0),

    vec3(1, 0, 0),
    vec3(1, 1, 0),
    vec3(1, 1, 1),
    vec3(1, 0, 1)
);

const vec4[] faceColors = vec4[](
    vec4(0.7, 0.7, 0.7, 0.8),
    vec4(1.0, 1.0, 1.0, 0.8),
    vec4(0.8, 0.8, 0.8, 0.8),
    vec4(0.8, 0.8, 0.8, 0.8),
    vec4(0.9, 0.9, 0.9, 0.8),
    vec4(0.9, 0.9, 0.9, 0.8)
);

void main() {
    int quadVertex = gl_VertexIndex % 4;
    int index = (gl_VertexIndex / 4) * 3;

    int cellX = texelFetch(CloudFaces, index).r;
    int cellZ = texelFetch(CloudFaces, index + 1).r;
    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;

    int direction = dirAndFlags & FLAG_MASK_DIR;

    bool isInsideFace =
        (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;

    bool useTopColor =
        (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;

    cellX = (cellX << 1) |
        ((dirAndFlags & FLAG_EXTRA_X) >> 7);

    cellZ = (cellZ << 1) |
        ((dirAndFlags & FLAG_EXTRA_Z) >> 6);

    vec3 faceVertex =
        vertices[
            (direction * 4) +
            (isInsideFace ? 3 - quadVertex : quadVertex)
        ];

    vec3 realCellSize = CellSize;

    // SOFRON — chmury dwa razy grubsze
    realCellSize.y *= 2.0;

    vec3 pos =
        (faceVertex * realCellSize) +
        (vec3(cellX, 0, cellZ) * realCellSize) +
        CloudOffset;

    gl_Position =
        ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_spherical_distance(pos);

    vertexColor =
        (useTopColor
            ? faceColors[1]
            : faceColors[direction])
        * CloudColor;

    vertexColor =
        (vertexColor * 0.5) +
        (CloudColor * 0.5);

    // Use the same camera sky sample as the sky disc, including biome transitions.
    float underside = (useTopColor && CloudOffset.y < 0.0) ? 0.0 : 1.0 - faceVertex.y;
    vec3 sky = clamp(ColorModulator.rgb, 0.0, 1.0);
    // Lift the weaker sky channels towards the cloud highlight. This keeps the
    // biome hue visible and lets weather and night dim the highlight naturally.
    float skyPeak = max(sky.r, max(sky.g, sky.b));
    float cloudPeak = max(CloudColor.r, max(CloudColor.g, CloudColor.b));
    float highlight = max(skyPeak, cloudPeak);
    vec3 headroom = max(vec3(highlight) - sky, vec3(0.0));
    vec3 shadedSky = sky + 0.62 * headroom * sqrt(headroom / max(highlight, 0.001));
    undersideTint = vec4(shadedSky, ColorModulator.a < 0.0 ? underside : 0.0);
    PlayerPos = pos;

    Pos =
        (faceVertex * realCellSize) +
        (vec3(cellX, 0, cellZ) * realCellSize);
}
