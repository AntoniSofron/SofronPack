# Classic Fog integration

Source: user-supplied `classic-fog-main.zip` (resource pack dated 2025-07-10).

The archive overrides two shader includes:

* `assets/minecraft/shaders/include/fog.glsl` changes the beginning of
  linear distance fog to `fogStart / 3.0`; the end distance is unchanged.
* `assets/sodium/shaders/include/fog.glsl` also divides fog start by three
  and applies a smoothstep transition in Sodium's fog shader.

The archived GLSL interfaces are for older Minecraft/Sodium versions.
**Do not copy them over 26.3 shader include files.**

## SofronPack implementation

1. `ExtendedWorldFogMixin` runs after Minecraft establishes FogData,
   including biome, weather and cave overrides. For the **non-Extended-World**
   rendering path, it scales `environmentalStart` and
   `renderDistanceStart` using `ClassicFogProfile.start(original)`.
   Ends, sky/cloud distances, fog color and opacity alpha stay untouched.
2. The normal Sodium terrain shader calls `sofronClassicSodiumFog`, which
   reproduces the smoothstep blend of the source pack using the 26.3
   uniform layout (`vec2 environmentFog`, `vec2 renderFog`,
   `vec2 fragDistance`), including the existing chunk fade factor.
3. Other vanilla passes use their existing shaders with the adjusted FogData,
   reproducing the old vanilla pack's linear distance-fog start without
   replacing or risking incompatibilities in `minecraft:fog.glsl`.

### Interaction with the 128-chunk renderer

The custom Extended World path intentionally remains unchanged.
It uses FogData start/end to encode the separate user-approved volumetric
and ordinary (8..128-chunk) fog system. Applying the classic division to those
encoded values would corrupt the volumetric fog and its Y/cloud gradients.

The non-Extended-World terrain, including ordinary Minecraft view distances,
uses Classic Fog instead of vanilla's original fog falloff. Both paths retain
vanilla fog's hue and the SofronPack atmosphere presets.
