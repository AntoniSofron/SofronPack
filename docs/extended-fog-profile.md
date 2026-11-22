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

**Cloud altitude**: each +8% stage around the existing Y=256, Y=384, Y=512
cloud decks now fades smoothly across **32 vertical blocks**, centered on its
deck. The three windows are Y=240..272, Y=368..400 and Y=496..528.
At a deck's center, half of that stage's 8% bonus has faded in. Beyond the
highest transition, the cloud bonus totals +24%.

**Low altitude**: below Y=60, each successive 40-block band adds +8%, but
now fades in over its first **16 blocks** using smoothstep: Y=60..44,
Y=20..4, Y=-20..-36, and so on. Outside transition windows the
bonus remains flat until the next band. At Y=60 the below-ground
bonus is 0%, at Y=52 it is 4%, and at Y=44 it is 8%.

**Volumetric near-field limit**: volumetric opacity is always **0% within
2 horizontal chunks (32 blocks)** of the camera, regardless of clouds,
weather, depth or cave influence. It smoothly fades into its full previous
strength across 2..6 chunks (32..96 blocks). The same fade applies after
the exponential vertical-ray density profile, so geometry viewed steeply
up/down cannot bypass the minimum horizontal radius.

The altitude bonuses still add to the established weather-dependent
volumetric curve and clamp to 100%. The existing vertical exponential
ray-density profile remains. Non-volumetric distance fog stays independent,
starting gradually at 8 chunks and ending at 128 chunks.

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

## Volumetric smoothing implementation

The CPU altitude result is encoded once in `FogData.environmentalStart`,
then shared by the Sodium and distant LOD shaders. Neither shader samples
biomes or computes altitude steps in the fragment loop. The volume near fade
uses a single smoothstep over the 2..6 horizontal chunk interval. The regular
8..128-chunk fog and the near/far LOD handoff are unmodified.
