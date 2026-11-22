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
  GNU LGPL **3.0 only**. Adapted early-exit optimization for inactive elytra
  flight checks.

- **Dynamic FPS 26.3** — Dynamic FPS contributors,
  [source](https://github.com/juliand665/Dynamic-FPS).
  Adapted the concept of limiting the render frame rate while Minecraft
  lacks window focus; implemented independently with the 26.3 SDL flag API.

## Analyzed but not yet ported or enabled

- **EntityCulling:** multithreaded CPU occlusion testing requires a
  separate compatibility pass against Sodium's render hooks and SofronPack
  dynamic lighting. Incorrect culling would hide visible objects.
- **C2ME:** highly invasive chunk pipeline threading and worldgen rewrites
  can race SofronPack's existing chunk prefetch, storage and preview tasks.
  C2ME's optional OpenCL module in the supplied archive specifically declares
  `All-Rights-Reserved`; it has not been incorporated.
- **FerriteCore 9.0.0 / Minecraft 26.1:** version-specific blockstate and
  shape internals require a 26.3 port and memory/regression benchmarks.
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
