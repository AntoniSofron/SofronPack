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
    assert len(mod["mixins"]) == 5
    assert "sofron-extended.mixins.json" in mod["mixins"]
    assert "sofron-extended-client.mixins.json" in mod["mixins"]
    far_mixins = json.loads(z.read("sofron-extended-client.mixins.json"))
    assert "ExtendedWorldFogMixin" in far_mixins["client"]
    assert "pl/sofron/extended/ExtendedWorldVoxyBridge.class" not in files
    assert "pl/sofron/extended/ExtendedWorldMeshBuilder.class" not in files
    assert "pl/sofron/extended/ExtendedWorldStorage.class" not in files
    for class_name in (
        "pl/sofron/extended/ExtendedWorldConfig.class",
        "pl/sofron/extended/ExtendedWorldPrefetch.class",
        "pl/sofron/extended/ExtendedVoxelStore.class",
        "pl/sofron/extended/ExtendedVoxelMesher.class",
        "pl/sofron/extended/client/ExtendedVoxelAtlas.class",
        "pl/sofron/extended/client/ExtendedVoxelPipeline.class",
        "pl/sofron/extended/mixin/ExtendedVoxelPipelineMixin.class",
        "pl/sofron/extended/client/ExtendedWorldLodRenderer.class",
        "pl/sofron/extended/mixin/ExtendedWorldSodiumRenderMixin.class",
        "pl/sofron/extended/mixin/ExtendedWorldSodiumOptionsMixin.class",
        "pl/sofron/extended/mixin/ExtendedWorldFogMixin.class",
    ):
        assert class_name in files, class_name
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
    assert "pl/sofron/atmosphere/AtmospherePackDecoder.class" in files
    assert "pl/sofron/atmosphere/AtmosphereResourceLoader.class" in files
    assert "pl.sofron.atmosphere.AtmosphereResourceLoader.register();" in pathlib.Path(
        "src/main/java/pl/sofron/light/SofronPackLightClient.java").read_text(encoding="utf-8")
    import zlib
    atmosphere = json.loads(zlib.decompress(
        z.read("assets/sofronpack/atmosphere/earth_sky.zlib")))
    assert len(atmosphere["colors"]) == 63
    assert len(atmosphere["fog"]) == 10
    assert atmosphere["colors"]["minecraft:swamp"] == [11056276, 9149554]
    assert atmosphere["colors"]["minecraft:plains"] == [11521022, 7512319]

    assert "sofron-atmosphere.mixins.json" in files
    assert "sofron-atmosphere.mixins.json" in mod["mixins"]
    atmosphere_mixins = json.loads(z.read("sofron-atmosphere.mixins.json"))
    assert atmosphere_mixins["package"] == "pl.sofron.atmosphere.mixin"
    assert atmosphere_mixins["client"] == ["AtmosphereBuilderMixin", "AtmosphericFogMixin", "CloudLayersMixin", "CloudPipelineMixin"]
    for extension in ("vsh", "fsh"):
        shader = f"assets/minecraft/shaders/core/sofronpack/clouds.{extension}"
        assert shader in files and b"undersideTint" in z.read(shader)
    for mixin in atmosphere_mixins["client"]:
        path = "pl/sofron/atmosphere/mixin/" + mixin + ".class"
        assert path in files, path

    # Regression guard: the actual shipped JAR must contain THREE bright cloud
    # layers plus the 128-chunk fogged voxel shader (not just source files).
    for path in (
        "pl/sofron/atmosphere/CloudBandLayout.class",
        "pl/sofron/extended/ExtendedLodFog.class",
        "pl/sofron/extended/ExtendedLodLevels.class",
    ):
        assert path in files, path
    for extension in ("vsh", "fsh"):
        path = f"assets/minecraft/shaders/core/sofronpack/extended_voxel.{extension}"
        assert path in files, path
    extended_shader = z.read("assets/minecraft/shaders/core/sofronpack/extended_voxel.fsh")
    assert b"FogColor.rgb" in extended_shader
    assert b"FogEnvironmentalStart" in extended_shader
    assert b"FogEnvironmentalEnd" in extended_shader
    assert b"FogRenderDistanceEnd" in extended_shader
    assert b"sphericalBlocks" in extended_shader
    assert b"cylindricalBlocks" in extended_shader
    assert b"FogRenderDistanceStart" in extended_shader
    assert b"linear_fog_value" in extended_shader
    assert b"max(baseFog, nativeFog)" in extended_shader
    cloud_vertex = z.read("assets/minecraft/shaders/core/sofronpack/clouds.vsh")
    assert b"0.60 * underside" in cloud_vertex
    assert b"ColorModulator.rgb" in cloud_vertex
    assert b"sofronPackDarkLight" in z.read(
        "assets/sofronpack/shaders/include/darker_light.glsl")
    assert b"sofronPackDarkLight" in z.read(
        "assets/minecraft/shaders/core/lightmap.fsh")
    assert b"GameTime" in z.read("assets/minecraft/shaders/core/stars.fsh")
    lod_vertex = z.read("assets/minecraft/shaders/core/sofronpack/extended_voxel.vsh")
    assert b"horizontalChunks" in lod_vertex
    assert b"cylindricalBlocks" in lod_vertex
    lod_renderer = pathlib.Path(
        "src/main/java/pl/sofron/extended/client/ExtendedWorldLodRenderer.java").read_text()
    assert "relative, new Vector4f(1f, 1f, 1f, 1f)" in lod_renderer
    assert "AtmospherePresets.distantFogBlend()" not in lod_renderer
    fog_mixin_source = pathlib.Path(
        "src/main/java/pl/sofron/extended/mixin/ExtendedWorldFogMixin.java").read_text()
    assert "fog.renderDistanceStart = start" in fog_mixin_source
    assert "AtmospherePresets.distantFogBlend()" in fog_mixin_source
    assert "Unified Extended World fog active" in fog_mixin_source
    assert "fog.renderDistanceEnd = end" in fog_mixin_source
    assert b"sphericalBlocks = length(relative)" in lod_vertex
    atmosphere_source = pathlib.Path(
        "src/main/java/pl/sofron/atmosphere/AtmospherePresets.java").read_text()
    assert "distantFogBlend = (float)" in atmosphere_source
    assert "customBiomeWeight" in atmosphere_source
    cloud_source = pathlib.Path(
        "src/main/java/pl/sofron/atmosphere/CloudBandLayout.java").read_text()
    assert "LOW = 256.0f" in cloud_source
    assert "MIDDLE = 384.0f" in cloud_source
    assert "HIGH = 512.0f" in cloud_source
    original_mixins = json.loads(z.read("sofron.mixins.json"))
    assert not any("atmosphere" in name for name in original_mixins["client"])
    atmosphere_source = pathlib.Path("src/main/java/pl/sofron/atmosphere/AtmospherePresets.java").read_text(encoding="utf-8")
    assert "level.getOverworldClockTime()" in atmosphere_source

    assert "assets/sofron_pack_light/icon.png" not in files
    assert "assets/sofron_terra_lab/lang/en_us.json" not in files

    states = json.loads(z.read("assets/sofronpack/blockstates/wild_rice_crop.json"))
    expected = [
        {
            "when": {"half": half, "age": str(age)},
            "apply": {"model": f"sofronpack:block/wild_rice_{half}_stage_{age}"}
        }
        for age in range(8) for half in ("lower", "upper")
    ]
    assert states["multipart"] == expected
    for age in range(8):
        for name in (f"rice_stage_{age}", f"wild_rice_lower_stage_{age}",
                     f"wild_rice_upper_stage_{age}"):
            model_path = f"assets/sofronpack/models/block/{name}.json"
            png_path = f"assets/sofronpack/textures/block/{name}.png"
            assert model_path in files and png_path in files, name
            model = json.loads(z.read(model_path))
            assert model["parent"] == "sofronpack:block/rice_crop_raised"
            assert model["textures"]["crop"] == f"sofronpack:block/{name}"
            png = z.read(png_path)
            assert png[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
            assert png[12:16] == b"IHDR"
            assert int.from_bytes(png[16:20], "big") == 16
            assert int.from_bytes(png[20:24], "big") == 16
            assert png[24] == 8 and png[25] == 6
    bottom = z.read("assets/sofronpack/textures/block/rice_bottom.png")
    assert bottom[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
    assert bottom[12:16] == b"IHDR"
    assert int.from_bytes(bottom[16:20], "big") == 16
    assert int.from_bytes(bottom[20:24], "big") == 16

    assert "assets/sofronpack/models/block/wild_rice_bottom.json" not in files

    wild_item = json.loads(z.read("assets/sofronpack/items/wild_rice.json"))
    assert wild_item["model"]["model"] == "sofronpack:item/wild_rice"
    wild_model = json.loads(z.read("assets/sofronpack/models/item/wild_rice.json"))
    assert wild_model["parent"] == "minecraft:item/generated"
    assert wild_model["textures"]["layer0"] == "sofronpack:item/wild_rice"
    import hashlib
    image = z.read("assets/sofronpack/textures/item/wild_rice.png")
    assert image[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
    assert int.from_bytes(image[16:20], "big") == 16
    assert int.from_bytes(image[20:24], "big") == 16
    assert hashlib.sha256(image).hexdigest() == "93aff8fa814e91e3fb73f7a40f69611ecf729c471e9b26e3dd54da419dd672a9"



    hitbox_source = pathlib.Path("src/main/java/pl/sofron/terralab/rice/WildRiceCropBlock.java").read_text(encoding="utf-8")
    assert "protected VoxelShape getShape(" in hitbox_source
    assert "LOWER_OUTLINE[age]" in hitbox_source and "UPPER_OUTLINE[age]" in hitbox_source
    assert hitbox_source.count("Shapes.empty()") >= 3
    for half, expected in (
        ("LOWER_OUTLINE", [
            (4, 0, 4, 13, 3, 13), (2, 0, 2, 13, 8, 13),
            (1, 0, 1, 13, 13, 13), (1, 0, 1, 14, 16, 14),
            (1, 0, 1, 14, 16, 14), (1, 0, 1, 14, 16, 14),
            (2, 0, 2, 14, 16, 14), (1, 0, 1, 14, 16, 14),
        ]),
        ("UPPER_OUTLINE", [
            None, None, None, (4, 0, 4, 13, 4, 13),
            (4, 0, 4, 13, 6, 13), (1, 0, 1, 14, 7, 14),
            (0, 0, 0, 14, 9, 14), (0, 0, 0, 14, 10, 14),
        ]),
    ):
        import re
        section = hitbox_source.split("private static final VoxelShape[] " + half + " = {", 1)[1].split("};", 1)[0]
        actual = [
            tuple(int(v.strip()) for v in coordinates.split(","))
            if coordinates else None
            for coordinates in re.findall(r"Block\.box\(([^)]*)\)|Shapes\.empty\(\)", section)
        ]
        assert actual == expected, (half, actual)


    for bowl in ("rice_bowl", "wild_rice_bowl", "cooked_rice_bowl",
                 "cooked_wild_rice_bowl", "mixed_rice_bowl"):
        path = f"assets/sofronpack/textures/item/{bowl}.png"
        assert path in files, path
        tex = z.read(path)
        assert tex[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
        assert tex[12:16] == b"IHDR"
        assert int.from_bytes(tex[16:20], "big") == 16
        assert int.from_bytes(tex[20:24], "big") == 16
        assert tex[24] == 8 and tex[25] == 6



    assert "pl/sofron/atmosphere/AtmosphereBiomeBlend.class" in files
    assert "pl/sofron/atmosphere/AtmosphereFogMath.class" in files
    assert "caveWeight" in pathlib.Path("src/main/java/pl/sofron/atmosphere/AtmospherePresets.java").read_text(encoding="utf-8")

    for biome, sky in (
        ("swamp", "#78a7ff"),
        ("taiga", "#7da3ff"),
        ("savanna", "#6eb1ff"),
        ("jungle", "#77a8ff"),
    ):
        path = f"data/minecraft/worldgen/biome/{biome}.json"
        attrs = json.loads(z.read(path))["attributes"]
        assert attrs["minecraft:visual/sky_color"] == sky, (biome, attrs)
        assert "minecraft:visual/fog_color" not in attrs, biome

    assert "assets/sofronpack/textures/item/raw_mixed_rice_bowl.png" not in files

    foods = {
        "raw_meat_pieces": (1, 1, False),
        "cooked_meat_pieces": (4, 4, False),
        "raw_fish_pieces": (1, 1, False),
        "cooked_fish_pieces": (3, 4, False),
        "raw_poultry_pieces": (1, 1, False),
        "cooked_poultry_pieces": (3, 4, False),
        "mixed_rice_bowl": (8, 10, True),
        "rice_meat_bowl": (12, 14, True),
        "rice_fish_bowl": (10, 14, True),
        "rice_poultry_bowl": (12, 12, True),
        "mashed_potatoes_bowl": (4, 4, True),
        "mashed_potatoes_meat_bowl": (12, 14, True),
        "mashed_potatoes_fish_bowl": (10, 14, True),
        "mashed_potatoes_poultry_bowl": (12, 12, True),
    }
    assert "pl/sofron/terralab/food/FoodContent.class" in files
    assert "pl/sofron/terralab/soil/mixin/FoodCraftingMixin.class" in files
    mixins = json.loads(z.read("sofron-soil.mixins.json"))
    assert "FoodCraftingMixin" in mixins["mixins"]
    for name in foods:
        for path in (
            f"assets/sofronpack/items/{name}.json",
            f"assets/sofronpack/models/item/{name}.json",
            f"assets/sofronpack/textures/item/{name}.png",
        ):
            assert path in files, path
        texture = z.read(f"assets/sofronpack/textures/item/{name}.png")
        assert texture[:8] == b"\x89PNG\r\n\x1a\n", name
        assert texture[12:16] == b"IHDR", name
        assert (int.from_bytes(texture[16:20], "big"),
                int.from_bytes(texture[20:24], "big")) == (16, 16), name
        assert texture[24:26] == bytes((8, 6)), name
    pieces = {
        "meat": ["minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:rabbit"],
        "fish": ["minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish"],
        "poultry": ["minecraft:chicken"],
    }
    for kind, allowed in pieces.items():
        name = f"raw_{kind}_pieces"
        recipe = json.loads(z.read(f"data/sofronpack/recipe/{name}.json"))
        assert recipe["type"] == "minecraft:crafting_shaped"
        assert recipe["pattern"] == ["MMM", "MAM", "MMM"]
        assert recipe["key"] == {"M": allowed, "A": "#minecraft:axes"}
        assert recipe["result"] == {"id": "sofronpack:" + name, "count": 4}
        for method, ticks in (("smelting", 200), ("smoking", 100)):
            cooked = f"cooked_{kind}_pieces"
            heat = json.loads(z.read(f"data/sofronpack/recipe/{cooked}_from_{method}.json"))
            assert heat["ingredient"] == "sofronpack:" + name
            assert heat["result"]["id"] == "sofronpack:" + cooked
            assert heat["cookingtime"] == ticks
    output_ingredients = {
        "mixed_rice_bowl": ["sofronpack:cooked_rice_bowl", "sofronpack:cooked_wild_rice_bowl", "minecraft:bowl"],
        "mashed_potatoes_bowl": ["minecraft:baked_potato", "minecraft:milk_bucket", "minecraft:bowl"],
    }
    for kind in ("meat", "fish", "poultry"):
        pieces = ["sofronpack:cooked_" + kind + "_pieces"] * 2
        output_ingredients["rice_" + kind + "_bowl"] = ["sofronpack:cooked_rice_bowl"] + pieces
        output_ingredients["mashed_potatoes_" + kind + "_bowl"] = ["sofronpack:mashed_potatoes_bowl"] + pieces
    for result, ingredients in output_ingredients.items():
        recipe = json.loads(z.read(f"data/sofronpack/recipe/{result}.json"))
        assert recipe["type"] == "minecraft:crafting_shapeless"
        assert recipe["ingredients"] == ingredients
        assert recipe["result"]["id"] == "sofronpack:" + result
    assert foods["mashed_potatoes_fish_bowl"] == (10, 14, True)


    for rice in ("rice", "wild_rice"):
        raw = f"{rice}_bowl"
        cooked = f"cooked_{raw}"
        for item in (raw, cooked):
            assert f"assets/sofronpack/items/{item}.json" in files
            assert f"assets/sofronpack/models/item/{item}.json" in files
            assert f"assets/sofronpack/textures/item/{item}.png" in files
        craft = json.loads(z.read(f"data/sofronpack/recipe/{raw}.json"))
        assert craft["type"] == "minecraft:crafting_shaped"
        assert craft["pattern"] == ["RRR", "RBR", "RRR"]
        assert craft["key"] == {"R": f"sofronpack:{rice}", "B": "minecraft:bowl"}
        assert craft["result"]["id"] == f"sofronpack:{raw}"
        for method, duration in (("smelting", 300 if rice == "wild_rice" else 200), ("smoking", 150 if rice == "wild_rice" else 100)):
            recipe = json.loads(z.read(f"data/sofronpack/recipe/{cooked}_from_{method}.json"))
            assert recipe["type"] == f"minecraft:{method}"
            assert recipe["ingredient"] == f"sofronpack:{raw}"
            assert recipe["result"]["id"] == f"sofronpack:{cooked}"
            assert recipe["cookingtime"] == duration

    wild_loot = json.loads(z.read("data/sofronpack/loot_table/blocks/wild_rice_crop.json"))
    assert len(wild_loot["pools"]) == 2
    bonus = wild_loot["pools"][1]
    assert bonus["entries"] == [{"type": "minecraft:item", "name": "sofronpack:rice"}]
    terms = bonus["condition"]["terms"]
    assert bonus["condition"]["type"] == "minecraft:all_of"
    assert {"type": "minecraft:match_tool", "predicate": {"items": "#minecraft:hoes"}} in terms
    assert {"type": "minecraft:random_chance", "chance": 0.2} in terms
    assert {"type": "minecraft:match_block", "blocks": "sofronpack:wild_rice_crop", "state": {"age": "7"}} in terms


pathlib.Path("dist").mkdir(exist_ok=True)
shutil.copy2(jar, pathlib.Path("dist") / jar.name)
print("PASS: one SofronPack Fabric JAR, both mod components and all main resources", jar)
