# SofronPack Extended World — Block-textured voxel LOD for Minecraft 26.3

The distant-world renderer is a native component of the **single SofronPack
JAR**. No Voxy installation is needed. The former coloured heightfield has
been removed: triangles with synthetic map colours are no longer used.

## Rendering

* A 3D material volume is sampled at **1 × 1 × 1 block** resolution, with
  actual block registry names and a compact per-chunk palette. Unlike the old
  heightfield it contains cliffs, trees and overhang silhouettes. Blocks are
  drawn as square, axis-aligned faces.
* Materials resolve to the Minecraft block-state model's actual baked quad UVs,
  so texture packs are used. Model/atlas caches are reset when resource packs
  change. For some complex shapes, flowers, fluids and block entities, the
  representation is still approximate.
* The mesher culls internal faces and uses LOD cube sizes of 1, 2, 4, 8, or 16 blocks
  according to distance. Near distance chunks are rendered **only by the
  original Minecraft/Sodium renderer**, avoiding double-rendering.
* Each four-by-four-chunk group is a reusable GPU buffer. The renderer
  budgets up to 24 tile updates per frame with a 5 ms target for mesh work.
  Previously visible buffers remain until their replacement is uploaded. The renderer
  uses RenderPearl and a depth-tested position/UV/colour pipeline sampling
  Minecraft's block texture atlas.
* The far projection extends to 128 chunks without changing the normal render
  or simulation distance.

## Persistence / gameplay

* Integrated single-player worlds generate FULL chunks gradually out to a
  128-chunk radius, with at most 12 pending requests and a busy-server
  backoff. Two daemon workers compress captured immutable voxel volumes,
  keeping zlib off the integrated server's tick thread. Gameplay simulation
  distance is unchanged.
* The persistent distant voxel cache lives in the save's
  `sofronpack-extended-world/*.voxel.v4.bin` files, separately by dimension.
  It uses compressed material indices, full block-state property strings, versioned records, checksums and per-chunk real biome grass, foliage and water tints. Old
  `*.surface.bin` heightfields are **not** used, so they can safely be ignored.
* Loaded nearby chunks are resampled at a limited rate. Distant cached chunks
  stay frozen until they return to the normal view radius.
* Game mechanics, entities, redstone, crops and ticks remain constrained to
  Minecraft's ordinary simulation distance. Multiplayer server chunk
  generation permissions are not bypassed.

## Current limits and test requirements

The voxel renderer is substantially more faithful to Minecraft blocks than
the old four-colour surface LOD, but **not yet identical to Voxy's detail or
performance**. The 1-block near-LOD resolution, simplified handling of
non-cubic models and transparency, and approximate biome tinting still differ
from Voxy. Tests required before release-grade use: in-world visual review
across biomes, trees, water, structures and transitions, Iris, Sodium,
OpenGL/Vulkan, FPS, VRAM, and persistent-world reload regression.

The previous triangular, map-coloured LOD is removed from this version; old surface and older voxel-v2/v3 caches are ignored and rebuilt in the new 1-block v4 format. Enable the
feature through the **Większy świat** toggle below the normal render-distance
option in Sodium's General Video Settings.

## Native four-stage fog (SofronPack v0.1.34)

Fog starts just beyond 16 chunks and is evaluated every vertex in camera-relative
world X/Z. The shader samples Minecraft's current FogColor (including the mod's
biome/weather settings) and blends it against actual block textures. Smooth
intervals yield approximately 13% at 32 chunks, 55% at 64, 85% at 96, and
100% at 128 chunks. The last interval deliberately hides all distant voxel
color to avoid a hard visible cut-off. Fog is continuous at the boundaries.

## Detail rings and improved streaming

* 0-32 chunks: 1x1 block voxels (only where not replaced by normal chunks)
* 33-48 chunks: 2x2 voxels
* 49-64 chunks: 4x4 voxels
* 65-96 chunks: 8x8 voxels
* 97-128 chunks: 16x16 voxels

Normal chunks remain at the player's selected render distance. A voxel LOD
snapshot remains visible if its normal client chunk has not yet arrived. The
integrated server can queue up to six asynchronous requests, submitting up to
two per tick while average tick processing is healthy, and backs off under
high load. This is intentionally limited; full real-chunk pregeneration to
128 chunks can take significant time and disk space and is not guaranteed to
be 100x faster.

## Cloud layers

The Overworld renders three independent textured cloud decks at Y=256, 384 and
512. The upper surfaces remain bright; only the underside and lower part of
the side walls gain a restrained biome-sky hue. The blend incorporates 70%
white before applying at most 60% to the underside, so no full face is painted
the raw sky colour.


## Shared biome/weather/cave fog (v0.1.36)

The LOD fragment shader uses exactly the same **environmental fog start and
end distances** (and live fog color) as Minecraft's normal chunks. A single
CPU-side signal, sourced from AtmospherePresets' blended surface-biome fog
rules, live rain/thunder, and enclosed-cave factor, enables the environmental
fog on the LOD pipeline. The signal is transmitted via the LOD-only dynamic
transform color alpha; the source material's texture transparency is preserved.
In heavy fog, the radial camera-to-voxel distance is compared with the same
FogEnvironmentalStart and FogEnvironmentalEnd uniforms as the normal renderer.
The full environment fog is combined with the always-on 16/32/64/96/128 distant
fog using `max`, never delayed with an extra weather-specific 128-chunk
envelope. Clear-weather LODs keep their extended visibility instead of
inheriting the ordinary render-distance fade. Cloud undersides inherit the
biome's blended sky color at a stronger intensity, while their tops stay
bright and the three layers remain at 256/384/512.

The vanilla normal-chunk render-distance fade is extended to the same
256-2048 block horizon when Extended World is enabled, while world chunk
loading and simulation remain unchanged. Custom environmental fog still
shortens visibility equally on both renderers. The environmental fog
selection acts as a toggle, not a second opacity multiplier: smoothly
blended biome fog distances are already taken into account on the CPU.

## v0.1.37 — progressive first paint, single fog and bounded snapshot encoding

* **Preview first:** when a tile is newly visible and its final voxel step
  would be 1 or 2 blocks, the renderer initially uses 4-block voxels. This
  performs up to 64x fewer voxel-grid visits for the *initial mesh only*;
  the stored source volume remains exact. The mesh is then upgraded to its
  correct distance-dependent resolution without requiring camera movement.
  The scheduler prioritizes close previews, close upgrades, far previews,
  then far upgrades. If a coarse sample contains no geometry, the mesher
  retries at the exact detail so content is never permanently lost.
* **Avoid temporary cliffs:** when the neighbouring coarse LOD chunk is
  genuinely unknown (not merely air), side faces against it are omitted
  rather than producing giant vertical stone curtains. Full-detail near
  voxels retain their exact geometry.
* **One fog environment:** both Minecraft/Sodium and the custom LOD shader
  consume the same `FogEnvironmentalStart/End`,
  `FogRenderDistanceStart/End`, and `FogColor`. The LOD additionally
  maintains its user-requested 16/32/64/96/128 distance gradient.
  Clear-sky vanilla haze is expanded to the 128-chunk horizon; real biome,
  rain, thunder and cave fog still brings the end point closer. There is no
  second LOD-only weather opacity switch, and distant textures retain full
  alpha regardless of fog conditions.
* **Independent compression:** source blocks and biome tint are copied on
  the server thread, then compressed on two bounded daemon threads. Up to
  12 full-generation tasks plus four near-cache snapshots can remain
  pending. When the server is healthy, its already-loaded nearby cache can
  be scanned at up to 2 captures/tick rather than 1 every 5 ticks; heavy
  tick loads automatically restore the old conservative rate. Known
  unchanged chunks are sampled infrequently to reduce wasted CPU work.
* **Benchmark scope:** 64x is the reduction in grid visits for 1-block
  to 4-block initial meshing; 10x is the increase in the scheduled
  capture rate of already-loaded nearby chunks (from 4 to 40/sec at 20 TPS).
  Neither is a measured claim that Minecraft's **new FULL chunk worldgen**
  is 10x faster. Validate full-world streaming throughput, first visible
  tile latency, frame times and VRAM on the target computer.

The old distance and caching rules documented above describe historical
implementations; v0.1.37 takes precedence where they differ.

## v0.1.38 — shared four-stage fog and seven-stage terrain preparation

The same `sofronpack:extended_fog.glsl` function is used in Sodium's
ordinary-chunk fragment pass and in SofronPack's textured LOD pass. Clear
weather: 0% at 16 chunks, 13% at 32, 55% at 64, 85% at 96 and
100% at 128 chunks. The four ranges are smoothstep interpolated.
In the extended-world mode, the native renderer uses the custom
four-stage distance curve instead of the much shorter linear fade.
The global blended environmental fog end distance, which incorporates
weather, cave and biome rules, shifts all the stages closer together
without applying a second unrelated fog overlay. Non-extended worlds
continue to use normal Minecraft/Sodium fog.

The LOD geometry is hidden at distances strictly less than
`renderDistance * 7 / 8`, including old GPU tiles from before the camera
moved. The source-mesh culling and shader-pixel clipping enforce the
same dynamic threshold. Vanilla client chunks retain priority in the
overlap between the 7/8 threshold and full view radius.

Full worldgen/caching prioritizes **square rings** in this exact order:
8, 16, 32, 48, 64, 96 and 128 chunks. The scheduler waits for the
current ring to finish being captured and persisted before requesting
new chunks in the next ring. Already-loaded chunks are captured without
another generation request; in-flight requests are deduplicated.
The first two stages have a larger dispatch budget when the server is
healthy, but still respect a strict maximum of 12 outstanding jobs
and back off if the server is overloaded. This describes task priority;
it is not a measured 10x worldgen speedup.

## v0.1.45 — precise stored voxels, distance-dependent displayed voxels

The integrated worldgen and persisted `ExtendedVoxelStore` keep each real
source block at **full 1×1 resolution**. Rendering is separate:

| Horizontal chunk distance | Visible LOD XZ voxel footprint |
| --- | --- |
| 0–48 inclusive | 1×1 |
| 49–96 inclusive | 2×2 |
| 97–128 inclusive | 4×4 |

The Y coordinate of the real-volume mesher is **not** reduced. Exact source
data are never downsampled or written back into the saved cache as a result
of changing the displayed LOD. Initial, incomplete terrain can temporarily
use a very coarse preview; when the source chunks are available, it is
replaced by the appropriate 1×1/2×2/4×4 volume mesh.

Both the tile's packed resolution fingerprint and every source chunk's
(including halo neighbours) selected mip level are derived from the camera
chunk position. Previous full-source meshes incorrectly passed an all-zero
mip-level array, and marked fully captured GPU tiles with a permanent
`-2` detail value; together these forced 1×1 resolution regardless of
distance. Both behaviours have been removed. On player movement, the
tile can rebuild at the new correct resolution without regenerating the
source world. The old visible GPU buffer remains in place until its
replacement is ready. Original four-stage fog, seven generation stages,
normal-chunk handoff, and the vanilla-only distant leaves remain intact.

This reduces horizontal voxel-grid visits from a maximum of 256 per
chunk per sampled Y layer (1×1) to 64 (2×2) or 16 (4×4), before greedy
surface merging. It does not guarantee a proportional FPS improvement:
cache decoding, Y detail, chunk generation and GPU driver overhead can
still dominate. Benchmark first paint, GPU vertices, frame times, and
memory across the three distance rings on actual hardware.
