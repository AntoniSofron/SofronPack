#!/usr/bin/env python3
"""Import unchanged functionality from SofronPackGen and SofronPackLight.

Use a clean destination. Original repositories are never modified.
Minecraft does not automatically remap saved registry IDs when the namespace changes.
"""
from __future__ import annotations
import argparse
import json
import shutil
from pathlib import Path

NEW_ID = "sofronpack"
OLD_IDS = ("sofron_terra_lab", "sofron_pack_light")
TEXT_SUFFIXES = {".java", ".json", ".mcmeta", ".glsl", ".fsh", ".vsh", ".properties", ".gradle", ".py"}

def import_tree(source, destination, skip=()):
    count = 0
    for old in sorted(source.rglob("*")):
        if not old.is_file() or old.relative_to(source).as_posix() in skip:
            continue
        target = destination / old.relative_to(source)
        if target.exists():
            raise RuntimeError(f"File collision: {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(old, target)
        count += 1
    return count

def replace_identifiers(folder):
    for path in folder.rglob("*"):
        if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
            content = path.read_text(encoding="utf-8")
            replacement = content
            for previous in OLD_IDS:
                replacement = replacement.replace(previous, NEW_ID)
            if replacement != content:
                path.write_text(replacement, encoding="utf-8")

def move_namespaces(root):
    resources = root / "src/main/resources"
    for category in ("assets", "data"):
        parent = resources / category
        for previous in OLD_IDS:
            old = parent / previous
            if not old.exists():
                continue
            target = parent / NEW_ID
            target.mkdir(parents=True, exist_ok=True)
            for original in sorted(old.rglob("*")):
                if original.is_file():
                    dst = target / original.relative_to(old)
                    if dst.exists():
                        raise RuntimeError(f"Resource collision: {dst}")
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(original, dst)
            shutil.rmtree(old)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gen", type=Path, required=True)
    parser.add_argument("--light", type=Path, required=True)
    parser.add_argument("--destination", type=Path, default=Path("."))
    args = parser.parse_args()
    gen, light, root = args.gen.resolve(), args.light.resolve(), args.destination.resolve()
    for repo in (gen, light):
        assert (repo / "src/main/resources/fabric.mod.json").is_file(), repo
    if (root / "src/main/java").exists():
        raise RuntimeError("Destination already contains sources; refusing to overwrite.")
    gen_files = import_tree(gen / "src", root / "src")
    light_files = import_tree(light / "src", root / "src", skip={"main/resources/fabric.mod.json"})
    for directory in ("config", "design", "docs", "gradle", "scripts"):
        import_tree(gen / directory, root / directory)
    import_tree(light / "libs", root / "libs")
    for filename in ("LICENSE", "BUILD.bat", "BUILD_AND_INSTALL.bat"):
        shutil.copy2(gen / filename, root / filename)

    move_namespaces(root)
    for folder in (root / "src", root / "gradle", root / "scripts"):
        replace_identifiers(folder)

    res = root / "src/main/resources"
    icon = res / "assets/sofronpack/icon.png"
    if icon.exists():
        icon.rename(res / "assets/sofronpack/light-icon.png")
    shutil.copy2(res / "pack.png", icon)

    meta = {
        "schemaVersion": 1, "id": NEW_ID, "version": "$"+"{version}",
        "name": "SofronPack",
        "description": "World generation, new soil shapes and rice, dynamic lighting, emissive ores and optimized leaf rendering.",
        "authors": ["SofronPack contributors"], "environment": "*",
        "license": "All Rights Reserved",
        "entrypoints": {
            "main": ["pl.sofron.terralab.TerraLabMod", "pl.sofron.light.SofronPackLight"],
            "client": ["pl.sofron.terralab.BirchColors", "pl.sofron.light.SofronPackLightClient"]},
        "depends": {
            "fabricloader": ">=0.20.1", "minecraft": "=26.3",
            "java": ">=25", "fabric-api": ">=0.166.2+26.3"},
        "recommends": {"sodium": "=0.9.2"},
        "mixins": ["sofron-soil.mixins.json", "sofron.mixins.json"],
        "icon": "assets/sofronpack/icon.png"}
    (res / "fabric.mod.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    client = root / "src/main/java/pl/sofron/light/SofronPackLightClient.java"
    code = client.read_text(encoding="utf-8")
    needle = "    @Override public void onInitializeClient() {"
    assert code.count(needle) == 1
    insert = ('\n        if (!FabricLoader.getInstance().isModLoaded("sodium")) {'
              '\n            System.out.println("[SofronPack] Sodium missing: client lighting disabled.");'
              '\n            return;'
              '\n        }')
    client.write_text(code.replace(needle, needle + insert, 1), encoding="utf-8")
    print(f"Imported {gen_files} Gen and {light_files} Light source files into one mod")

if __name__ == "__main__":
    main()
