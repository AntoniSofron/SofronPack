#version 330

#include <minecraft:globals.glsl>

layout(location = 0) out vec4 fragColor;

// Original Sofron 16x star scintillation, with individual phase offsets.
void main() {
    float phase = GameTime * 5000.0
        + 0.00001 * gl_FragCoord.x * gl_FragCoord.y;
    float twinkle = (sin(phase) + 1.0) * 0.5;
    fragColor = vec4(1.0, 1.0, 1.0, mix(1.0, 0.5, twinkle));
}
