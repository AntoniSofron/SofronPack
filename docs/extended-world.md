# SofronPack Extended World — Block-textured voxel LOD for Minecraft 26.3

The distant-world renderer is a native component of the **single SofronPack
JAR**. No Voxy installation is needed. The former coloured heightfield has
been removed: triangles with synthetic map colours are no longer used.

## Rendering

* A 3D material volume is sampled at **2 × 2 × 2 block** resolution, with
  actual block registry names and a compact per-chunk palette. Unlike the old
  heightfield it contains cliffs, trees and overhang silhouettes. Blocks are
  drawn as square, axis-aligned faces.
* Materials resolve to the Minecraft block-state model's actual baked quad UVs,
  so texture packs are used. Model/atlas caches are reset when resource packs
  change. For some complex shapes, flowers, fluids and block entities, the
  representation is still approximate.
* The mesher culls internal faces and uses LOD cube sizes of 2, 4, or 8 blocks
  according to distance. Near distance chunks are rendered **only by the
  original Minecraft/Sodium renderer**, avoiding double-rendering.
* Each four-by-four-chunk group is a reusable GPU buffer. At most one far tile
  is rebuilt per frame; unchanged tiles are not re-uploaded. The renderer
  uses RenderPearl and a depth-tested position/UV/colour pipeline sampling
  Minecraft's block texture atlas.
* The far projection extends to 64 chunks without changing the normal render
  or simulation distance.

## Persistence / gameplay

* Integrated single-player worlds generate new full chunks gradually up to a
  64-chunk radius, at most one asynchronous FULL-chunk request at a time and
  one new attempt every four server ticks. Existing generated terrain is loaded.
* The persistent distant voxel cache lives in the save's
  `sofronpack-extended-world/*.voxel.v3.bin` files, separately by dimension.
  It uses compressed material indices, versioned records, checksums and per-chunk real biome grass, foliage and water tints. Old
  `*.surface.bin` heightfields are **not** used, so they can safely be ignored.
* Loaded nearby chunks are resampled at a limited rate. Distant cached chunks
  stay frozen until they return to the normal view radius.
* Game mechanics, entities, redstone, crops and ticks remain constrained to
  Minecraft's ordinary simulation distance. Multiplayer server chunk
  generation permissions are not bypassed.

## Current limits and test requirements

The voxel renderer is substantially more faithful to Minecraft blocks than
the old four-colour surface LOD, but **not yet identical to Voxy's detail or
performance**. The 2-block minimum resolution, simplified handling of
non-cubic models and transparency, and approximate biome tinting still differ
from Voxy. Tests required before release-grade use: in-world visual review
across biomes, trees, water, structures and transitions, Iris, Sodium,
OpenGL/Vulkan, FPS, VRAM, and persistent-world reload regression.

The previous triangular, map-coloured LOD is removed from this version; old surface and voxel-v2 caches are ignored and rebuilt in the new v3 format. Enable the
feature through the **Większy świat** toggle below the normal render-distance
option in Sodium's General Video Settings.
