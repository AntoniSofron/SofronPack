# SofronPack Extended World — native LOD, Minecraft 26.3

The Extended World mode is fully part of **one SofronPack JAR**. It does not
load or require Voxy, and no Voxy code is copied into this implementation.
The architecture follows the same basic principle of separating far, frozen
geometries from near, live Minecraft chunks.

## How it works

* A **Większy świat (LOD 64 chunki)** toggle is inserted directly underneath
  the normal render-distance control in Sodium's Video Settings.
* Minecraft's normal render and simulation distances are untouched.
* In single-player, the integrated server incrementally visits chunk
  coordinates in near-to-far rings up to **64 chunks** from the player,
  loading previously generated chunks and generating missing ones.
* Only one FULL-chunk request is in flight, and attempts are paced to no more
  than once every four ticks. These are Minecraft's normal chunk generation
  requests and don't add permanent simulation tickets.
* Finished chunks yield a compact 8-block-resolution surface representation
  (3x3 heights and 2x2 colours per chunk), saved as a versioned binary database
  under the world save. Far terrain uses this frozen snapshot.
* Nearby, loaded chunks are resampled at a bounded pace to reflect gameplay
  changes before they eventually become distant again. Distant snapshots are
  not continuously simulated or re-rendered by vanilla.
* The built-in RenderPearl client renderer groups the far data into GPU tiles,
  reuses each tile until its sources change or the camera moves across a LOD
  boundary, and performs at most 3 tile uploads per frame.

## Boundaries / safety

* The native renderer is **surface-only LOD**: land, height variations and
  large-scale shapes are approximated using 8-block quads. It is not a
  block-perfect reproduction of caves, foliage, facades, overhangs or structures.
  Developing full voxel LOD of Voxy's fidelity is a separate engineering step.
* Gameplay, redstone, entities, block ticks and crop growth remain governed by
  Minecraft's standard live chunk view and simulation distances.
* Far pregeneration is **single-player only**. On external multiplayer servers
  a client must not compel generation of chunks the server has not sent.
* The system is intentionally off by default. Generating 64 chunk radii can
  take substantial disk space and CPU time; the queue is paced for stability.
* Minecraft 26.3's RenderPearl pipeline is used instead of raw OpenGL. The
  renderer is intended to work with both OpenGL and Vulkan backends, subject
  to in-game testing (automated client bootstrap alone is insufficient).
* Existing worlds are not converted or modified outside their normal Minecraft
  region data; the distant surface cache is stored separately.

## Implementation

`ExtendedWorldPrefetch` → `ExtendedWorldStorage` →
`ExtendedWorldMeshBuilder` → `ExtendedWorldLodRenderer`

All four stages are compiled into the same SofronPack JAR.
