# SofronPack Extended World (Minecraft 26.3)

This is an optional, conservative distant-world feature, implemented as a
small integration layer rather than an in-tree copy of Voxy.

## In-game behavior

* **Render distance** and **simulation distance** remain Minecraft settings.
  SofronPack never changes them automatically.
* An **Extended World / Większy świat** switch appears directly after the render
  distance slider in Sodium's General settings. Rejoin the world to apply it.
* In an integrated single-player server, a bounded background queue loads
  existing chunks or generates missing chunks up to **64 chunks** from the
  player's current chunk. Traversal uses successive Chebyshev rings.
* At most **one Minecraft asynchronous chunk request** may be in flight.
  A new request is scheduled at most once every four server ticks.
* FULL chunks are delivered once to the optional Voxy LOD ingestion service.
  The generator does not permanently force-load them or grant them simulation
  tickets. The ordinary engine remains in charge of live updates nearby.
* Completed LOD ingestions are tracked per dimension in the save's
  `sofronpack-extended-world/` directory so the same distant columns are
  not repeatedly pre-generated. The actual LOD appearance/storage is managed
  by the rendering backend.

## Compatibility and limits

**Voxy 26.3 must also be installed** to render the distant terrain. This
release of SofronPack has its own scheduler and setting, but does **not** yet
bundle or replace Voxy's GPU rendering and persistent LOD engine. The toggle
is disabled when Voxy is missing. No upstream source code is copied into this
module.

Only integrated single-player generation is supported. External multiplayer
servers control which chunks exist and which chunks may be generated; the
client must not bypass server view-distance permissions. Voxy may require
an OpenGL backend and suitable GPU capabilities.

Generating a new 129 x 129 chunk area can take a long time and occupy disk
space. The queue is intentionally gradual to avoid overwhelming the tick
thread. Chunk generation is not the same as a GPU LOD mesh, and continuous
movement may postpone work at the outer rings.

## Files and entry points

* `ExtendedWorldConfig`: independent opt-in configuration.
* `ExtendedWorldOptions` and client mixin: UI below render distance.
* `ExtendedWorldPrefetch`: bounded background generation and progress log.
* `ExtendedChunkFutureAccess`: Minecraft's own chunk generation future.
* `ExtendedWorldVoxyBridge`: optional runtime interface for LOD ingestion.

A complete standalone renderer would require a dedicated GPU pipeline,
frustum/occlusion selection, geometry generation, shader integration, storage
versioning and end-to-end testing with Iris and Sodium; it is not part of
this change.
