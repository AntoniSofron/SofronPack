#!/usr/bin/env python3
import json
import pathlib
import shutil
import zipfile

properties = {}
for line in pathlib.Path("gradle.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
version = properties["mod_version"]
expected_name = f'{properties["archives_base_name"]}-v{version}.jar'
root = pathlib.Path("build/libs")
jars = [p for p in root.glob("*.jar") if not p.name.endswith(("-dev.jar", "-sources.jar"))]
assert len(jars) == 1, jars
jar = jars[0]
assert jar.name == expected_name, jar.name
with zipfile.ZipFile(jar) as z:
    files = set(z.namelist())
    mod = json.loads(z.read("fabric.mod.json"))
    assert mod["id"] == "sofronpack" and mod["name"] == "SofronPack"
    assert mod["version"] == version
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
        "pl/sofron/terralab/rice/WildRiceCropBlock.class",
        "pl/sofron/terralab/rice/WildRiceFeature.class",
        "assets/sofronpack/blockstates/wild_rice_crop.json",
        "assets/sofronpack/items/wild_rice.json",
        "data/sofronpack/worldgen/feature/swamp_wild_rice.json",
        "data/sofronpack/worldgen/placed_feature/swamp_wild_rice.json",
        "data/sofronpack/loot_table/blocks/wild_rice_crop.json",
        "data/minecraft/worldgen/noise_settings/overworld.json",
        "sofron.mixins.json", "sofron-soil.mixins.json", mod["icon"]
    ):
        assert required in files, required
    assert "assets/sofron_pack_light/icon.png" not in files
    assert "assets/sofron_terra_lab/lang/en_us.json" not in files

    states = json.loads(z.read("assets/sofronpack/blockstates/wild_rice_crop.json"))
    parts = states["multipart"]
    assert len(parts) == 9
    assert parts[0] == {
        "when": {"half": "lower"},
        "apply": {"model": "sofronpack:block/wild_rice_bottom"}
    }
    for age in range(8):
        assert parts[age + 1] == {
            "when": {"half": "upper", "age": str(age)},
            "apply": {"model": f"sofronpack:block/rice_stage_{age}"}
        }
    base = json.loads(z.read("assets/sofronpack/models/block/wild_rice_bottom.json"))
    assert base["parent"] == "sofronpack:block/rice_crop_raised"
    assert base["textures"]["crop"] == "sofronpack:block/rice_bottom"
    assert "assets/sofronpack/textures/block/rice_bottom.png" in files

pathlib.Path("dist").mkdir(exist_ok=True)
shutil.copy2(jar, pathlib.Path("dist") / jar.name)
print("PASS: one SofronPack Fabric JAR, both mod components and all main resources", jar)
