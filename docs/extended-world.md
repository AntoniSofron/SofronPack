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
* The mesher culls internal faces and uses LOD cube sizes of 1, 2, or 4 blocks
  according to distance. Near distance chunks are rendered **only by the
  original Minecraft/Sodium renderer**, avoiding double-rendering.
* Each four-by-four-chunk group is a reusable GPU buffer. At most one far tile
  is rebuilt per frame; unchanged tiles are not re-uploaded. The renderer
  uses RenderPearl and a depth-tested position/UV/colour pipeline sampling
  Minecraft's block texture atlas.
* The far projection extends to 128 chunks without changing the normal render
  or simulation distance.

## Persistence / gameplay

* Integrated single-player worlds generate new full chunks gradually up to a
  128-chunk radius, at most one asynchronous FULL-chunk request at a time and
  one new attempt every four server ticks. Existing generated terrain is loaded.
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
512. The cloud shader keeps them visibly pale/white, with a light pastel
biome-sky influence only on the underside. The earlier 50% fog-colour wash was
removed because it made clouds look like tinted sky.
