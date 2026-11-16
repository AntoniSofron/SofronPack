# SofronPack — Minecraft 26.3 / Fabric

**One mod, one JAR, one ID: `sofronpack`.** This is the unified successor to [SofronPackGen](https://github.com/AntoniSofron/SofronPackGen) and [SofronPackLight](https://github.com/AntoniSofron/SofronPackLight).

## New in v0.1.1: wild rice

Wild rice (`sofronpack:wild_rice`) is a rare two-block plant in one-block-deep swamp water, rooted on ordinary dirt immediately near shore. Mature wild plants naturally generate in new swamp chunks. Breaking either half yields wild rice seeds, with the same 1-to-3 count at age 7 as cultivated rice. Plant the wild seed into a **full water-source block** directly above ordinary dirt, with **air above the water** (not two blocks of water). Wild rice progresses through the same eight ages and uses the ordinary wet-farmland rice growth probability, plus bone meal. Its temporary models reuse the existing rice crop textures.

## What is included

- **Gen:** the existing custom Overworld density functions and approved world-generation configuration; custom tree shapes, swamp willows and biome palettes; new soil blocks, soil slabs and stairs, waterlogged farmland and rice.
- **Light:** dynamic colored terrain lighting, emissive ores, leaf-detail optimization, Sodium shader integration and performance improvements.
- **Unified assets:** models, textures, shaders and language files are under `assets/sofronpack`; worldgen definitions are under `data/sofronpack`.
- **One loader entry:** one `fabric.mod.json` loads both original code modules and both mixin configurations.

The original GitHub repositories have not been changed. The import preserves source assets, including the custom NBT trees, binary textures and shader files.

## Requirements

Minecraft **26.3**, Fabric Loader **0.20.1+**, Fabric API **0.166.2+26.3**, and Java **25+**. Install **Sodium 0.9.2** on clients to enable the Light rendering features. Dedicated servers do not require Sodium for the world generator.

## Download and install

Download the newest versioned JAR from [dist/](dist/), place it in your Minecraft `mods` directory, and **remove both older SofronPackGen and SofronPackLight JARs** from that directory. Do not use all three mods together. Back up existing worlds before changing mods.

## Build from source

GitHub Actions validates the imported world-generation contract, compiles the mod and tests that both parts occur inside a **single** Fabric JAR. Versioned JARs are uploaded as workflow artifacts and committed to [dist/](dist/) for permanent retention.

To build locally, use Java 25 and Gradle 9.5.0, then run `gradle --no-daemon clean build`. On Windows, `BUILD.bat` builds the JAR; `BUILD_AND_INSTALL.bat` additionally backs up old Sofron JAR files and installs the new one into the mods folder. Local builds also copy the JAR to `dist/`.

For each subsequent release, increment `mod_version` in `gradle.properties`. CI automatically derives the new filename from that version and keeps older releases in `dist/`.

## World compatibility

**Back up old saves before using this mod.** The custom registry namespace changed from `sofron_terra_lab` (Gen) to `sofronpack`; Minecraft does not automatically remap existing blocks, crops or worldgen references. Already generated chunks remain unchanged. A new world is the safest option for testing world generation.

The historical 84-parameter configuration and earlier technical documents are retained under `config/`, `design/`, and `docs/`.
