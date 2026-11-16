# SofronPack — Minecraft 26.3 / Fabric

Unified successor to [SofronPackGen](https://github.com/AntoniSofron/SofronPackGen) and [SofronPackLight](https://github.com/AntoniSofron/SofronPackLight).

**Goal:** one Fabric mod (`sofronpack`), one distributable JAR, preserving both projects' functionality. GitHub Actions builds will publish versioned JAR files under `dist/`.

Minecraft 26.3 · Fabric Loader 0.20.1+ · Fabric API 0.166.2+26.3 · Java 25+. For client lighting, install Sodium 0.9.2.

**World migration:** Back up worlds before replacing legacy mods: custom registry IDs will change to `sofronpack`. Keep the two legacy JARs out of the mods directory once the unified JAR is installed.
