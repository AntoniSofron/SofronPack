# SofronPack Extended World — Block-textured voxel LOD for Minecraft 26.3

The distant-world renderer is a native component of the **single SofronPack
JAR**. No separate distant-world rendering mod is required. The former coloured heightfield has
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

## Unified scheduling and memory (v0.1.55)

SofronPack's voxel snapshots now reuse immutable palette strings across
chunks without sharing mutable palette arrays. The chunk prefetcher also
adapts its near-cache capture and distant request budgets to measured
snapshot processing cost, within fixed CPU and in-flight limits. Encoding
remains asynchronous; world block access stays on the integrated server
thread. These changes do not depend on OpenGL or Vulkan.

## Frame-time and cache I/O improvements (v0.1.56)

To mitigate freezes while maintaining full 128-chunk LOD coverage:

- Compressed voxel snapshots are appended to the save on a dedicated
  low-priority archive writer. Game ticks update in-memory voxels and enqueue
  coalesced writes; disk flushes never occur inline on the server tick.
  World close waits for the pending archive writes and optional compaction,
  retaining the previous v4 archive format.
- Unloaded chunks copy their live section data on the owning server thread,
  but compression and publication run on the existing bounded encoder pool.
  Generation-future completions are staged in a small queue. The tick
  processes at most two captured chunk volumes and uses a 1.5 ms shared
  snapshot budget when adding a second capture; the normal simulation
  and render distances remain unchanged.
- Render-frame LOD file existence checks use an in-memory index built by
  a background directory scanner, not repeated SSD metadata calls.
  Cache-index completion triggers a one-time visible tile rescan.
- Meshed tiles become available to the GPU without waiting for cache
  compression. Cache files are compressed using streaming 64 KiB buffers
  instead of allocating whole raw and compressed heap copies, and saved by
  a dedicated bounded I/O worker. At world shutdown, pending completed mesh
  saves are drained to keep previously visited terrain persistent.
- GPU uploads remain incremental, now with at most four tiles, 8 MiB and
  approximately 2 ms of additional transfer submissions per frame.
  These are **per-frame work budgets**, not render-distance or LOD caps.
- If any SofronPack stage consumes unusually long, `latest.log` receives a
  rate-limited `[SofronPack] Frame-time diagnostic:` line indicating
  whether the issue was an LOD server tick, section snapshot, or GPU frame
  preparation. Such lines supplement but do not replace a JVM/OS profiler.

These improvements reduce known blocking paths, but cannot guarantee zero
stutters from Java garbage collection, vanilla chunk generation, the disk
pagefile, shaders or GPU drivers. Compare frame-time percentiles in the same
world, using the same settings, to measure actual improvement.

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
* **v0.1.53: persistent rendered LODs.** Every 4x4-chunk tile actually
  rendered by the mod is stored as compressed, backend-neutral 48-byte quad
  instances. Each used 1x1, 2x2, 4x4 and 8x8 display level-map is saved
  independently under
  `sofronpack-extended-world/<dimension>/lod-mesh-v1/r.<region>/t.<tile>.<detail>.slm`
  in the single-player save. All these files belong to this save, not a
  global Minecraft cache. They can be deleted safely to rebuild geometry from
  the separately retained `*.voxel.v4.bin` snapshots.
* On re-entering a previously explored area, the renderer prioritises
  saved mesh files and uploads decoded quads without re-running the voxel
  mesher. GPU uploads remain batched to avoid out-of-memory spikes: the full
  horizon cannot appear within literally a single frame. Previously generated
  terrain appears as cached files stream in, normally much faster than
  rebuilding the world from scratch.
* Cached meshes are validated using the saved real chunks (including edge
  neighbours) and the actual current model/texture atlas; edited chunks and
  resource pack changes cause regeneration instead of stale rendering.
  Newly generated preview geometry invalidates an older cached preview.
* Only level variants that have actually been rendered are written; this does
  not precompute four expensive complete meshes for every chunk of the world.
  Extra disk use depends on explored area and geometry complexity.
* Loaded nearby chunks are resampled at a limited rate. Distant cached chunks
  stay frozen until they return to the normal view radius.
* Game mechanics, entities, redstone, crops and ticks remain constrained to
  Minecraft's ordinary simulation distance. Multiplayer server chunk
  generation permissions are not bypassed.

## Current limits and test requirements

The voxel renderer is substantially more faithful to Minecraft blocks than
the old four-colour surface LOD, but **performance and visual parity with
fully featured long-range renderers is not yet established**. The 1-block
near-LOD resolution, simplified non-cubic models and transparency, and
approximate biome tinting still need improvement. Tests required before release-grade use: in-world visual review
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

## v0.1.49 — packed GPU instances, shared geometry arena and vertical preservation

The pre-crash v0.1.46 remains the rollback reference. This version rebuilds
the *representation* of distant geometry without changing player controls.

- **48 bytes per quad on the GPU vs 96 bytes in v0.1.46.** An explicit
  48-byte instanced vertex format contains four signed fixed-point XYZ
  positions at 1/32-block precision (12 × 16-bit), four pairs of 16-bit
  normalized model UVs (or original bit-exact repeated atlas endpoints),
  the preserved 4-byte color/lighting/emission, and flags. The vertex
  shader reconstructs the four corners using `gl_VertexIndex`. There is
  just one indexed draw per visible tile regardless of quad count. Shader
  and pipeline use RenderPearl's instance step rate, not GL-only SSBOs.
  The server-side palette and world-save format are unchanged. This saves
  exactly 50% of raw quad vertex payload for the same number of quads;
  it does not promise 50% total GPU memory reduction.
- **Shared geometry pool.** Multiple tiles suballocate free ranges from
  4 MiB RenderPearl vertex-buffer pages. Range coalescing and in-place
  uploads reuse old GPU memory on edits and distance-LOD transitions.
  Pages no longer containing allocations are returned to the driver,
  while the last empty page is reused. There is no forced native 20-chunk
  cap and no fixed 64-draw or 256 MiB geometry budget.
- **Vertical detail policy:** within **0–48 chunks, all Y coordinates
  remain exact at one-block resolution**. At 49–96 chunks, opaque side
  walls may merge in 2-block-tall slabs; at 97–128 chunks in 4-block
  slabs. **Top/bottom faces, fluids, foliage and cutout geometry always
  retain exact Y**, including the vanilla sea-level surface.
  This selective policy avoids the unnatural coarse waterline that
  would result from blindly mipping the entire world volume in Y.
- **Original source preserved:** cached voxels, exact real Y, worldgen,
  progressive preview, 1×1/2×2/4×4 XZ rings, 128-chunk horizon, fog,
  clouds, crop textures, detailed near leaves and lighting are intact.

This is the first architectural optimization step, not a full port of
an 8-byte quad encoder, hierarchical GPU Hi-Z occlusion traversal,
multi-draw indirect rendering or lazy RAM snapshot storage. Any claim
about eliminating GPU OOM still requires sustained VRAM measurements
on the user's own hardware.

## v0.1.50 — four distance-dependent LOD bands

Change the render-only distance thresholds, with no modification to stored
world voxels, the 128-chunk horizon or the player's normal chunk setting:

| Camera-to-chunk horizontal Chebyshev distance | Displayed voxel footprint | Opaque-side Y slab |
| --- | --- | --- |
| 0–40 chunks | 1×1 XZ | **Exact 1-block Y** |
| 41–64 chunks | 2×2 XZ | 2 blocks |
| 65–96 chunks | 4×4 XZ | 4 blocks |
| 97–128 chunks | 8×8 XZ | 8 blocks |

Only opaque vertical walls are coarsened in Y; all top/bottom faces,
water, foliage, vegetation and transparent geometry stay at exact
native Y heights regardless of distance. Incomplete terrain may retain
a temporary surface-only preview at ≥4×4 (and ≥8×8 in the farthest ring)
until its accurate real-chunk geometry is ready.

Previous volume and surface meshers had only THREE cached XZ mip levels:
they must grow to FOUR to support the eighth-block ring without array
out-of-bounds. Both the per-chunk 2-bit packed resolution fingerprint
and the 4×4-chunk halo already support ring value 3 and continue to
trigger on-camera-movement rebuilds. Unit tests now cover every boundary,
8×8 occupied-child preservation, 1-block water surfaces, vertical
precision close to the player, and complete source voxel retention.

The packed 48-byte GPU quad instances, paginated reusable buffer arena,
fog, clouds, foliage, seven-stage generation, resource packs, simulation
distance and all other SofronPack functions are unchanged. The farthest
ring samples up to 64 times fewer horizontal cell locations per Y layer
than the exact 1×1 representation, prior to greedy surface meshing;
actual VRAM/FPS savings depend on terrain and graphics hardware.


## v0.1.71 — lossless storage compression, no world/cache limits

The v0.1.70 radius reduction, 64 MiB eviction cap and disabled mesh
persistence are reverted. All original 128-chunk generation, exact block
volumes, detail rings, and persistent geometry variants are retained.

* Voxel records choose the smallest of original fast zlib, palette-width
  bit packing with zlib, and vertical run-length coding with zlib. The
  original raw 16-bit material sequence is reconstructed exactly. No
  palette entries, coordinates, tints, or block-state properties are lost.
* The existing archive filename remains `*.voxel.v4.bin` for migration;
  its header version is 5. Version 4 and legacy zlib records remain
  readable by the new mod. On closing a legacy archive, it is repacked
  atomically when possible, with all unique snapshots retained. Newer
  archives compact on shutdown whenever obsolete records exist, based
  on actual serialized byte counts rather than a per-chunk estimate.
* Mesh files version 2 use reversible byte-plane shuffling in 1024-quad
  batches and stronger zlib compression. All 48 GPU bytes of each
  quad survive exactly. Version 1 files are still readable; new writes
  replace the corresponding old file. Existing unused mesh variants are
  not bulk deleted. Source re-encoding can invalidate older fingerprints.
* Compression runs on the existing encoding/archive/mesh workers.
  Repacking a large existing archive can increase the first shutdown
  time and requires temporary disk space. Back up before upgrading;
  older releases cannot read all new codec records.
* No cap on disk usage, no reduction in generation range, no loss of
  chunks and no modification of vanilla region/player/entity/POI data.
  Minecraft itself still stores tens of thousands of actual chunks.
  Consequently total save size cannot be guaranteed to remain in MB
  for arbitrary terrain or exploration. Compare region files, voxel
  archives and mesh files separately to quantify the actual reduction.


## v0.1.72 — restore block-shaped opening terrain

The coarse 48 KiB opening overview (one sloped 64×64 block quad per tile,
sampled every 256 blocks) is removed. The pre-v0.1.66 progressive
height-preview path is restored: height samples at 32, 16, then 8 blocks
(the 8-block pass is local to the first 48 chunks). The regular surface
mesher displays 4×4 or 8×8 block-shaped previews, followed by full voxel
geometry using the unchanged distance-dependent LOD rings.

Fresh worlds can display the first predicted tile before the first real
voxel capture. The instant low-poly sheet no longer appears; the block
preview fills progressively instead. Later primitive-map, GPU arena,
projection, fog, generation radius, and v0.1.71 lossless persistence
changes are preserved.


## v0.1.74 — Overworld build ceiling Y=512

The Overworld dimension type retains min_y=-64 and increases height and
logical_height from 384 to 576. The exclusive upper bound is Y=512; the
highest placeable block occupies Y=511. The natural noise settings remain
min_y=-64, height=384 (exclusive Y=320), preserving terrain generation
and sea level. Nether and End are unchanged.

The full-height voxel format supports up to 1024 vertical cells, and the
packed LOD coordinates support Y=511. Empty upper sections do not require
block-state copies; the lossless voxel codec compresses their air cells.
A taller dimension does increase transient full-volume allocations and
section metadata. Existing worlds should be backed up before upgrading;
compatibility with existing custom dimension datapacks is not guaranteed.


## v0.1.75 — remove the old Y320 generator boundary

Natural noise generation now uses the same -64..511 block range as the
Overworld dimension. The existing mountain gradient is extended to Y512
at exactly its previous slope (-0.005 per block at the approved setting).
Its zero crossing, mountain strength, peakHeight target, all biome and
relief functions, and sea level are unchanged. No higher mountain type
or extra elevation is introduced. Existing chunks are not regenerated.
This removes the old hard noise-generation ceiling, not the smooth
density falloff that defines natural peaks. World generation can require
more work due to additional sampled vertical space.
