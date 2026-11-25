#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path("src/main/resources/data")
settings = Path("config/approved-worldgen-spec.json")
assert settings.exists(), "Approved settings not committed"
cfg = json.loads(settings.read_text(encoding="utf-8"))
catalog = json.loads(Path("design/catalog.json").read_text(encoding="utf-8"))
assert cfg["schema"] == "sofron-terra-lab-v1" and cfg["minecraft"] == "26.3"
assert cfg["requirements"] == {
    "vanillaBiomesOnly": True, "vanillaStructuresOnly": True,
    "noLegacyCode": True, "previewsAreIllustrative": True,
}
approved = cfg["parameters"]
questions = catalog["questions"]
assert len(questions) == len(approved) == 84, (len(questions), len(approved))
assert set(approved) == {q["key"] for q in questions}
for q in questions:
    value = approved[q["key"]]
    if q["type"] == "range":
        assert isinstance(value, (int, float)) and not isinstance(value, bool)
        assert q["min"] <= value <= q["max"], (q["key"], value)
    elif q["type"] == "choice":
        assert value in [v for v, _ in q["options"]], (q["key"], value)
    else:
        assert q["type"] == "bool" and isinstance(value, bool)
assert approved["vanillaBiomesOnly"] and approved["vanillaStructuresOnly"]
assert approved["seaLevel"] == 63 and approved["worldHeight"] == "512"

data = {}
for path in ROOT.rglob("*.json"):
    data[path] = json.loads(path.read_text(encoding="utf-8"))
def check_ref(identifier, kind, path):
    name, key = identifier.split(":", 1)
    target = ROOT / name / "worldgen" / kind / (key + ".json")
    assert target in data, f"{path}: missing {kind} {identifier}"

def walk(node, path):
    if isinstance(node, dict):
        if node.get("type") == "minecraft:noise" and isinstance(node.get("noise"), str):
            if node["noise"].startswith("sofronpack:"):
                check_ref(node["noise"], "noise", path)
        if node.get("type") == "minecraft:spline":
            pts = node["spline"]["points"]
            assert all(a["location"] < b["location"] for a, b in zip(pts, pts[1:])), path
        for key, value in node.items():
            if key == 'noise' and node.get('type') == 'minecraft:noise':
                continue
            walk(value, path)
    elif isinstance(node, list):
        for value in node: walk(value, path)
    elif isinstance(node, str) and node.startswith("sofronpack:"):
        if "worldgen/density_function" in path.as_posix() or "worldgen/noise_settings" in path.as_posix():
            check_ref(node, "density_function", path)
for path, obj in data.items():
    walk(obj, path)

s = data[ROOT / "minecraft/worldgen/noise_settings/overworld.json"]
assert s["sea_level"] == 63
assert s["noise"] == {"min_y": -128, "height": 640}
# Match the generation envelope to the buildable dimension. Mountain
# density keeps its existing slope, rather than being stretched upward.
dimension = data[ROOT / "minecraft/dimension_type/overworld.json"]
assert dimension["min_y"] == -128
assert dimension["height"] == dimension["logical_height"] == 640
assert dimension["min_y"] + dimension["height"] == 512
assert dimension["height"] % 16 == 0
assert dimension["has_skylight"] and not dimension["has_ceiling"]
assert s["noise"]["min_y"] + s["noise"]["height"] == dimension["min_y"] + dimension["height"]
assert s["noise"]["min_y"] == dimension["min_y"]
assert data[ROOT / "minecraft/worldgen/density_function/overworld/depth.json"]["left"]["from_coordinate"] == -128
cave_band = data[ROOT / "sofronpack/worldgen/density_function/terrain/cave_band.json"]
assert cave_band["left"]["from_coordinate"] == -64
assert cave_band["right"]["to_coordinate"] == 16
lava_lake = data[ROOT / "minecraft/worldgen/placed_feature/lake_lava_underground.json"]
assert lava_lake["placement"][0]["chance"] == 7
assert lava_lake["placement"][2]["height"]["min_inclusive"]["absolute"] == -120
assert s["default_fluid"] == "minecraft:water"
assert s["material_rule"] == "minecraft:overworld"
assert s["noise_router"]["final_density"] == "sofronpack:terrain/final_density"
assert s["noise_router"]["depth"] == "minecraft:overworld/depth"
assert s["noise_router"]["continents"] == "minecraft:overworld/continents"
assert s["noise_router"]["chunk_surface_level"] == "minecraft:overworld/chunk_surface_level"
assert s["aquifers"]["surface_level"] == "minecraft:overworld/chunk_surface_level"
final = data[ROOT / "sofronpack/worldgen/density_function/terrain/final_density.json"]
assert final["type"] == "minecraft:max"
assert final["left"]["type"] == "minecraft:add"
assert final["left"]["left"] == "minecraft:overworld/final_density"
assert final["left"]["right"] == "sofronpack:terrain/sculpted_relief"
assert final["right"] == "sofronpack:terrain/mountain_ranges"
land_gate = data[ROOT / "sofronpack/worldgen/density_function/terrain/land_gate.json"]
assert all(p["value"] == 0 for p in land_gate["input"]["spline"]["points"] if p["location"] <= -0.12)
ocean = data[ROOT / "sofronpack/worldgen/density_function/terrain/ocean_trenches.json"]
assert ocean["type"] == "minecraft:mul" and ocean["left"] < 0
core = data[ROOT / "sofronpack/worldgen/density_function/terrain/mountain_ranges.json"]
assert core["type"] == "minecraft:min"
alt = core["left"]
assert alt["type"] == "minecraft:add"
assert alt["left"]["type"] == "minecraft:gradient" and alt["left"]["axis"] == "y"
assert alt["left"]["from_value"] > alt["left"]["to_value"], "Mountain mass must descend with Y"
assert alt["left"]["to_coordinate"] == 512
expected_slope = 0.005 * approved["mountainSteepness"] / 70.0
assert abs((alt["left"]["from_value"] - alt["left"]["to_value"]) / (alt["left"]["to_coordinate"] - alt["left"]["from_coordinate"]) - expected_slope) < 1e-9
assert alt["right"]["right"] == "sofronpack:terrain/mountain_strength"
# No min(raw, ceiling) plateau. A C1 continuous, strictly increasing
# strength transfer leaves the original low mountains intact.
strength = data[ROOT / "sofronpack/worldgen/density_function/terrain/mountain_strength.json"]
potential = data[ROOT / "sofronpack/worldgen/density_function/terrain/mountain_potential.json"]
shoulder = data[ROOT / "sofronpack/worldgen/density_function/terrain/mountain_shoulder.json"]
assert strength["type"] == "minecraft:cache"
transfer = strength["input"]
assert transfer["type"] == "minecraft:add"
assert transfer["left"] == "sofronpack:terrain/mountain_shoulder"
curve = transfer["right"]["spline"]
assert curve["coordinate"] == {"type": "minecraft:sub", "left": "sofronpack:terrain/mountain_potential", "right": "sofronpack:terrain/mountain_shoulder"}
pts = curve["points"]
assert pts[0] == {"location": 0, "value": 0, "derivative": 1}
assert all(p["derivative"] > 0 for p in pts)
assert potential["input"]["type"] == "minecraft:mul"
assert shoulder["input"]["type"] == "minecraft:add"
def transfer_sample(x):
    if x <= pts[0]["location"]:
        p = pts[0]
        return p["value"] + p["derivative"] * (x - p["location"])
    if x >= pts[-1]["location"]:
        p = pts[-1]
        return p["value"] + p["derivative"] * (x - p["location"])
    for a, b in zip(pts, pts[1:]):
        if a["location"] <= x <= b["location"]:
            width = b["location"] - a["location"]
            t = (x - a["location"]) / width
            return ((2*t**3-3*t*t+1)*a["value"] + (t**3-2*t*t+t)*width*a["derivative"]
                    + (-2*t**3+3*t*t)*b["value"] + (t**3-t*t)*width*b["derivative"])
samples = [transfer_sample(i / 1000) for i in range(-2000, 4001)]
assert all(a < b for a, b in zip(samples, samples[1:])), "Mountain strength must not saturate"
assert all(transfer_sample(i / 100) == i / 100 for i in range(-200, 1))
# Current approved settings must not produce a second plateau at Y512.
upper_shoulder = shoulder["input"]["left"] + shoulder["input"]["right"]["left"]
raw_max = potential["input"]["left"] # land, profile, and region factors <=1 at approved settings
max_strength = upper_shoulder + transfer_sample(raw_max - upper_shoulder)
max_peak = alt["left"]["from_coordinate"] + (alt["left"]["from_value"] + alt["right"]["left"] * max_strength) / expected_slope
assert max_peak < dimension["min_y"] + dimension["height"], max_peak
assert core["right"]["type"] == "minecraft:gradient" and core["right"]["to_coordinate"] <= 64
for key in ("surface_envelope", "beta_overhangs", "karst_spires", "upland_lift"):
    assert key in {p.stem for p in data}, key
biome_files = {p.relative_to(ROOT).as_posix() for p in data
               if p.relative_to(ROOT).parts[1:-1] == ('worldgen', 'biome')}
winter_biomes = set(data[ROOT / 'sofronpack/tags/worldgen/biome/winter_palette.json']['values'])
assert len(winter_biomes) == 11
winter_files = {f"minecraft/worldgen/biome/{name.split(':')[1]}.json" for name in winter_biomes}
taiga = data[ROOT / 'minecraft/worldgen/biome/taiga.json']
assert taiga['effects']['grass_color'] == '#408240'
for old_growth in ('old_growth_spruce_taiga', 'old_growth_pine_taiga'):
    assert data[ROOT / f'minecraft/worldgen/biome/{old_growth}.json']['effects']['grass_color'] == '#B28035'
for name in winter_files:
    winter = data[ROOT / name]
    assert winter['effects']['grass_color'] == '#B28035'
    assert winter['effects']['water_color'] == taiga['effects']['water_color']
    assert winter['attributes']['minecraft:visual/sky_color'].startswith('#')
assert biome_files == winter_files | {f'minecraft/worldgen/biome/{name}.json' for name in ('swamp', 'birch_forest', 'old_growth_birch_forest', 'taiga', 'old_growth_spruce_taiga', 'old_growth_pine_taiga', 'dark_forest', 'savanna', 'savanna_plateau', 'windswept_savanna', 'jungle', 'sparse_jungle', 'bamboo_jungle')}, biome_files
swamp = data[ROOT / 'minecraft/worldgen/biome/swamp.json']
assert swamp['effects']['grass_color'] == '#688D46'
assert swamp['effects']['foliage_color'] == '#688D46'
assert swamp['effects']['water_color'] == '#68834D'
assert swamp['effects']['grass_color_modifier'] == 'none'
assert swamp['attributes']['minecraft:visual/sky_color'] == '#78a7ff'
assert len(swamp['features']) == 11
vegetation = swamp['features'][9]
assert vegetation.count('minecraft:trees_swamp') == 1
assert vegetation.count('minecraft:patch_waterlily') == 1
assert vegetation.count('sofronpack:swamp_willows') == 1
assert vegetation.index('sofronpack:swamp_willows') == vegetation.index('minecraft:trees_swamp') + 1
placed = data[ROOT / 'sofronpack/worldgen/placed_feature/swamp_willows.json']
assert placed['feature'] == 'sofronpack:swamp_willows'
assert placed['placement'] == [
    {'type': 'minecraft:count', 'count': 5},
    {'type': 'minecraft:in_square'},
    {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 2},
    {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR'},
    {'type': 'minecraft:biome'},
    {'type': 'minecraft:block_predicate_filter', 'predicate': {
        'type': 'minecraft:would_survive', 'state': 'minecraft:oak_sapling'}},
]
feature = data[ROOT / 'sofronpack/worldgen/feature/swamp_willows.json']
assert feature['type'] == 'sofronpack:grounded_willow'
for i in range(1, 6):
    assert (ROOT / f'sofronpack/structure/swamp_willow_{i}.nbt').exists()

assert swamp['carvers'] == ['minecraft:cave','minecraft:cave_extra_underground','minecraft:canyon']
assert swamp['temperature'] == 0.8 and swamp['downfall'] == 0.9

village_types = {f'village_{biome}' for biome in ('plains','desert','taiga','savanna','snowy')}
structure_files = {p for p in data if p.parts[-3:-1] in {('worldgen','structure'), ('worldgen','structure_set')}}
village_set = ROOT / 'minecraft/worldgen/structure_set/villages.json'
assert structure_files == {ROOT / f'minecraft/worldgen/structure/{name}.json' for name in village_types} | {village_set}
for name in village_types:
    structure = data[ROOT / f'minecraft/worldgen/structure/{name}.json']
    assert structure['type'] == 'sofronpack:village' and structure['biomes'] == f'#minecraft:has_structure/{name}'
assert data[village_set]['placement']['frequency'] == 0.2
assert not any("sofron_pack" in p.parts for p in data), "Legacy namespace prohibited"
vanilla_sky = {
    'swamp': '#78a7ff',
    'taiga': '#7da3ff',
    'savanna': '#6eb1ff',
    'jungle': '#77a8ff',
    'frozen_peaks': '#859dff',
    'birch_forest': '#7aa5ff',
}
for name, color in vanilla_sky.items():
    biome = data[ROOT / f'minecraft/worldgen/biome/{name}.json']
    assert biome['attributes']['minecraft:visual/sky_color'] == color, name
for name in biome_files:
    biome = data[ROOT / name]
    assert 'minecraft:visual/fog_color' not in biome['attributes'], name

print(f"PASS: 84 approved choices, {len(data)} parsed worldgen files, vanilla biome/aquifer safeguards")
