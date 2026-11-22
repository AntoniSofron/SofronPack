# SofronPack FPS optimization provenance

This document records third-party techniques actually incorporated into the
SofronPack source tree. Original authorship and licence notices must not be
removed merely to unify code style.

## Integrated source-derived components

- **ImmediatelyFast 1.17.2-SNAPSHOT for Minecraft 26.3** — RK_01 / RaphiMC
  and contributors, [source](https://github.com/RaphiMC/ImmediatelyFast).
  GNU LGPL **3.0 or later**. Adapted GUI spatial list/quadtree, text vertex
  consumer reuse and redundant OpenGL framebuffer-unbind handling.
  Adapted Java sources retain individual copyright headers in
  `src/main/java/pl/sofron/optimization/`.
  The OpenGL optimizations are backend-specific; Vulkan still uses the
  existing RenderPearl path.

- **Lithium 0.26.4 for Minecraft 26.3** — JellySquid, 2No2Name and other
  Lithium contributors, [source](https://github.com/CaffeineMC/lithium).
  GNU LGPL **3.0 only**. Adapted early-exit for inactive elytra
  checks; stream-free AI gate tasks and weighted task iteration; bounding-box
  axis selection; fast direction lookup and allocation-free direction loops
  for pistons and redstone. All are **CPU-side** and work with either Vulkan
  or OpenGL. These are selected modules, not the complete Lithium engine.

- **Dynamic FPS 26.3** — Dynamic FPS contributors,
  [source](https://github.com/juliand665/Dynamic-FPS).
  Adapted the concept of limiting the render frame rate while Minecraft
  lacks window focus; implemented independently with the 26.3 SDL flag API.

- **FerriteCore 9.0.0 / Minecraft 26.1** — malte0811 and contributors,
  [source](https://github.com/malte0811/FerriteCore).
  LGPL-3.0-only. Adapted empty `PatchedDataComponentMap` deduplication
  to reduce RAM used by unchanged item stacks. Blockstate and shape structure
  replacements still need compatibility validation against Minecraft 26.3.

## Integrations from the newly supplied source archives (v0.1.55)

- **ModernFix 5.28 / Minecraft 1.20.1 Forge** — embeddedt and other
  ModernFix contributors. Source provided as `ModernFix-1.20.zip`.
  The old 1.20.1 surface-rule classes no longer exist in Minecraft 26.3,
  so the direct 1.20.1 mixins were **discarded after failing compilation**.
  Instead, ModernFix's immutable-value interning technique was adapted to
  SofronPack's persistent voxel-palette strings. Thousands of snapshots
  share weakly interned immutable block-state names, reducing potential
  heap duplication without modifying the original terrain or data format.
  Distinct mutable palette arrays are deliberately never shared. This does
  NOT include ModernFix's Forge-specific classloader, registry, texture,
  resource-pack or memory-layout replacements.

- **Async 0.2.6+alpha-26.3** — AxalotL, Alchemy, Bliss, FurryMileon,
  Grider and jediminer543. Source provided as `Async-26.3.zip`.
  Adapted `ParallelProcessor.CostModel`'s 25% EWMA to bound SofronPack's
  own **server-thread** chunk snapshot acquisition and background prefetch
  submission, using actual measured snapshot costs. The asynchronous
  compression worker pool remains as before. The main thread still owns
  all block, entity and biome reads, preserving world correctness.
  Full multithreaded entity ticking and Async's world/AI lock-rewrite mixins
  have **not** been incorporated: the supplied source still uses alpha
  version numbering and these require independent world-concurrency
  regression tests alongside Lithium.

The user reports permission from upstream authors for integration. The
supplied ZIP archives contain no complete standalone license file: source
authorship and this provenance record have been preserved; distribution
terms should be independently verified before external release.

## Analyzed but not yet ported or enabled

- **EntityCulling:** multithreaded CPU occlusion testing requires a
  separate compatibility pass against Sodium's render hooks and SofronPack
  dynamic lighting. Incorrect culling would hide visible objects.
- **C2ME (entire project, including ore BitSet reuse):** deliberately excluded
  for now. The chunk scheduler, storage and worldgen rewrites may conflict
  with SofronPack's existing chunk prefetch, storage and previews. Even the
  small ore BitSet patch is withheld to keep this release free of C2ME
  integration until focused compatibility and benchmark evidence exists.
  C2ME's optional OpenCL module in the supplied archive specifically
  declares `All-Rights-Reserved`; it has not been incorporated.
- **FerriteCore blockstate fastmaps and shape deduplication:** need a separate
  26.3 adaptation with controlled memory snapshots and regression tests.
- **Cloth Config v8 / Minecraft 1.19.x:** configuration-screen dependency,
  not an FPS optimization and not compatible with the current game version.

## Distribution and testing

SofronPack retains its own copyright for independently developed files;
copied/adapted LGPL source retains the licensing rights and obligations of
the original rightsholders. Distributors should include the applicable full
LGPL license text and ensure recipients can access and modify the
corresponding LGPL source. Individual upstream licenses, including the
licensing of Dynamic FPS, should be rechecked before a public redistribution.

The modules are intended as **compatible incremental optimizations**, not a
claim that the seven source projects were all merged. In particular, no
measured FPS increase is claimed without controlled game benchmarks.


## Graphics backend contract

The Lithium-derived gameplay/physics/AI changes and the FerriteCore-derived
item-component memory optimization have no OpenGL calls and run independently
of Vulkan/OpenGL. GUI and glyph
render batching uses Minecraft's common RenderPearl rendering interfaces.
Only the two ImmediatelyFast-derived framebuffer optimizations directly
reference OpenGL classes; their mixin targets are OpenGL-only and are never
called by the Vulkan device implementation. Vulkan has its own mandatory
world-start + real LOD GPU draw smoke test:
`.github/workflows/optimization-vulkan-smoke.yml`.
No code should claim Vulkan validated unless that test reports the exact
`Using graphics backend Vulkan` line and an actual voxel draw.

The in-game performance improvement of each module is not yet quantified.
Any unstable or ineffective module should be removed rather than silently
changing worldgen outcomes, block behavior or render quality.
