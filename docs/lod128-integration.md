# LOD128 integration and benchmark cleanup

The production renderer stays at the newer 0.1.67 state. PR #39 already incorporated
the frame-cost optimization and added the batched 128x128 opening overview; PR #40
already merged the subsystem organization changes. The voxel store and JAR verifier
match the old optimization branch byte-for-byte, while the current renderer includes
the old frame-cost improvements and additional overview code. Do not revert the
renderer or version to 0.1.66.

The six old measurement branches modified the wild-rice test with disposable
benchmark instrumentation, including swapping historical Java source files and
installing system graphics drivers. That is not shipping functionality.
The original commits are retained as parents of this integration commit, and
the normal wild-rice smoke remains unchanged. Their useful measurements are now
available through one opt-in tool: `tools/lod128_benchmark.py`.

On a Linux machine with Java 25, Gradle, Xvfb and Mesa/EGL/Vulkan installed, run:

```sh
python3 tools/lod128_benchmark.py --backend vulkan --require-overview
python3 tools/lod128_benchmark.py --backend opengl --require-overview
python3 tools/lod128_benchmark.py --ref f07e57be7c266b256e5502d3d748aef8dbb9bcd8 --ref HEAD --ref HEAD --ref f07e57be7c266b256e5502d3d748aef8dbb9bcd8 --backend vulkan
```

Each invocation uses a fresh detached worktree for each ref, never edits the
working checkout, and preserves logs in bench-results. It measures first overview
draw relative to the first in-world frame, opening frame-time spikes, steady-state
FPS and p99 frame time, process CPU, RSS, disk I/O and save size. It requires the
requested graphics backend to actually initialize; Vulkan fallback cannot count
as an OpenGL pass. These software-rendered CI measurements do not establish
performance on a player's physical GPU. The tool is opt-in, not part of the
ordinary wild-rice test. Its end-to-end operation requires a graphics runner.

The OpenGL smoke workflow retains the EGL configuration from the in-progress
fix branch and also runs on relevant pushes to main. It is not marked successful
unless the requested backend, a loaded world and a textured LOD draw are observed.

## Preserved historical commits

- `feature/bench-lod128-v0166`: `faec6027a2bd9071bb1468e4b744a1c56778a9b8`
- `feature/measure-lod128-ab`: `f3a8e7cbe1f28e999cf39fa91dca6f162efc825a`
- `feature/measure-lod128-ba`: `25ac121a3b26289ed8703f5e9ec5628c0647d4e4`
- `feature/opengl-opening-offscreen`: `15d84ef4e2ec7639f554e19c8c99985bb2a817d4`
- `feature/perf-128-opening-ab`: `50e65cfbe4db2158032fe8c93e837469b824e96c`
- `feature/perf-128-opening-ba`: `99c1093111144c5d09f5d58bdd3bff5e5f911ba6`
- `optimization/lod-frame-cost`: `da5cf0256f3b9fab922153dffd8f422968281d18`
- `fix/ci-opengl-egl-26-3`: `496a0e2911d19c34bffffa053051f0bfcfedba6f`

The historical branches can be deleted after integration; their full commit
history remains reachable from main. The already merged PR #40 branch can also
be deleted. Main must not be deleted.
