#!/usr/bin/env python3
"""Verify that the output is one, playable Fabric JAR (not two nested mods)."""
import json
import pathlib
import shutil
import zipfile

root = pathlib.Path("build/libs")
jars = [p for p in root.glob("*.jar") if not p.name.endswith(("-dev.jar", "-sources.jar"))]
assert len(jars) == 1, jars
jar = jars[0]
assert jar.name == "26.3-SofronPack-v0.1.0.jar", jar.name
with zipfile.ZipFile(jar) as z:
    files = set(z.namelist())
    mod = json.loads(z.read("fabric.mod.json"))
    assert mod["id"] == "sofronpack" and mod["name"] == "SofronPack"
    assert mod["version"] == "0.1.0"
    assert len(mod["entrypoints"]["main"]) == 2
    assert len(mod["entrypoints"]["client"]) == 2
    assert len(mod["mixins"]) == 2
    for required in (
        "pl/sofron/terralab/TerraLabMod.class",
        "pl/sofron/terralab/rice/RiceCropBlock.class",
        "pl/sofron/terralab/soil/SoilSlab.class",
        "pl/sofron/light/SofronPackLightClient.class",
        "pl/sofron/light/runtime/LeafDetailRange.class",
        "pl/sofron/light/emissive/OreFades.class",
        "assets/sofronpack/shaders/blocks/block_layer_opaque.fsh",
        "assets/sofronpack/blockstates/rice_crop.json",
        "data/sofronpack/structure/swamp_willow_5.nbt",
        "data/minecraft/worldgen/noise_settings/overworld.json",
        "sofron.mixins.json", "sofron-soil.mixins.json", mod["icon"]
    ):
        assert required in files, required
    assert "assets/sofron_pack_light/icon.png" not in files
    assert "assets/sofron_terra_lab/lang/en_us.json" not in files
pathlib.Path("dist").mkdir(exist_ok=True)
shutil.copy2(jar, pathlib.Path("dist") / jar.name)
print("PASS: one SofronPack Fabric JAR, both mod components and all main resources", jar)
