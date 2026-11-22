# Extended World fog specification (2026-11-22)

Both Sodium chunks and native distant LODs use the same GPU fog function
(`sofronVolumeFog` in `extended_fog.glsl`). The matching CPU reference
is `ExtendedLodFog.java`.

| Horizontal chunks | Clear sky | Maximum storm |
| ---: | ---: | ---: |
| 0 | 0% | 0% |
| 8 | 0% | 8% |
| 16 | 8% | 32% |
| 32 | 16% | 64% |
| 64 | 32% | 86% |
| 96 | 64% | 92% |
| 128 | 100% | 100% |

Between control points the curve uses `smoothstep`; intermediate weather
strengths blend the clear and storm curves. As before, deep cave influence can
increase the effective weather/fog strength.

**Cloud altitude**: from Y=256 to 383 add 8% opacity; Y=384 to 511 add
16%; from Y=512 upwards add 24%. These thresholds use the existing
`CloudBandLayout`.

**Low altitude**: below Y=60 add 8% per commenced 40 blocks: Y=59..20 is
+8%, 19..-20 is +16%, -21..-60 is +24%, and so on. All bonuses add to the
horizontal fog curve and the result is clamped to 100%. The existing vertical
exponential density profile still applies when a target is above or below
the camera.

The opt-in Extended World mixin communicates the weather/cave factor with
`environmentalEnd = 2048 - 1024 * influence` and the altitude factor via
`environmentalStart = 256 + 1024 * altitudeBonus`. Both chunk shaders decode
these shared fog uniforms. No new GPU buffers, uniform uploads or sampling
loops are required. Non-Extended-World and non-Overworld fog is unaffected.

## Ordinary (non-volumetric) distance fog

The opt-in Extended World terrain also adds an independent, ordinary distance
fog layer. It begins at **8 horizontal chunks (128 blocks)** and reaches
**100% opacity at 128 horizontal chunks (2048 blocks)**. It is independent of
weather, camera Y and cloud bands.

The gradual fade uses one smoothstep across the complete distance interval:

```text
t = clamp((distanceChunks - 8) / 120, 0, 1)
ordinaryFog = t * t * (3 - 2*t)
```

Both normal Sodium chunks and distant LOD geometry blend this ordinary layer
with the unchanged volumetric layer using transmittance composition:
`combined = 1 - (1 - ordinary) * (1 - volumetric)`.

The transition is deliberately gentle near eight chunks, and opacity reaches
100% at exactly 128 chunks. No new vertex data, GPU buffers, uniforms or
per-frame CPU biome sampling are needed. Outside Extended World, vanilla fog
behaviour remains unchanged.
