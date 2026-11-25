#!/usr/bin/env python3
import json
from pathlib import Path

root = Path("src/main/resources/data")
terrain = root / "sofronpack/worldgen/density_function/terrain"
load = lambda path: json.loads(path.read_text(encoding="utf-8"))
j = lambda name: load(terrain / (name + ".json"))

spec = load(Path("config/approved-worldgen-spec.json"))["parameters"]
settings = load(root / "minecraft/worldgen/noise_settings/overworld.json")
assert spec["seaLevel"] == settings["sea_level"] == 63
dimension = load(root / "minecraft/dimension_type/overworld.json")
minimum = dimension["min_y"]
top = minimum + dimension["height"]
assert top == int(spec["worldHeight"])
assert settings["noise"] == {"min_y": minimum, "height": dimension["height"]}

final = j("final_density")
assert final["type"] == "minecraft:max"
assert final["right"] == "sofronpack:terrain/mountain_ranges"

mass = j("mountain_ranges")
assert mass["type"] == "minecraft:min"
alt = mass["left"]
assert alt["type"] == "minecraft:add"
g = alt["left"]
amp = alt["right"]
anchor = mass["right"]
assert g["type"] == "minecraft:gradient" and g["axis"] == "y"
assert g["from_coordinate"] == 80 and g["to_coordinate"] == top
assert g["from_value"] > g["to_value"]
assert amp["type"] == "minecraft:mul"
assert amp["right"] == "sofronpack:terrain/mountain_strength"
assert anchor["type"] == "minecraft:gradient"
assert anchor["from_coordinate"] == 24 and anchor["to_coordinate"] == 64
assert anchor["from_value"] < 0 < anchor["to_value"]

def gradient(node, y):
    low, high = node["from_coordinate"], node["to_coordinate"]
    t = min(1.0, max(0.0, (y-low)/(high-low)))
    return node["from_value"] + t * (node["to_value"]-node["from_value"])

def mountain(s, y):
    return min(gradient(g, y) + amp["left"] * s, gradient(anchor, y))

strength = j("mountain_strength")["input"]
assert strength["type"] == "minecraft:add"
assert strength["left"] == "sofronpack:terrain/mountain_shoulder"
raw = j("mountain_potential")["input"]
shoulder = j("mountain_shoulder")["input"]
assert raw["type"] == "minecraft:mul" and raw["left"] > 0
assert shoulder["type"] == "minecraft:add" and shoulder["left"] == 1
extension = shoulder["right"]
assert extension["type"] == "minecraft:mul"
curve = extension["right"]["spline"]
assert curve["coordinate"] == "sofronpack:terrain/local_relief_noise"
transfer = strength["right"]["spline"]
assert transfer["coordinate"] == {
    "type": "minecraft:sub",
    "left": "sofronpack:terrain/mountain_potential",
    "right": "sofronpack:terrain/mountain_shoulder",
}
assert j("local_relief_noise")["input"]["y_scale"] == 0

def spline(curve, x):
    points = curve["points"]
    if x <= points[0]["location"] or x >= points[-1]["location"]:
        p = points[0 if x <= points[0]["location"] else -1]
        return p["value"] + (x - p["location"]) * p["derivative"]
    for a, b in zip(points, points[1:]):
        if a["location"] <= x <= b["location"]:
            width = b["location"] - a["location"]
            t = (x - a["location"]) / width
            return ((1+2*t)*(1-t)**2*a["value"] + t*(1-t)**2*width*a["derivative"]
                    + t*t*(3-2*t)*b["value"] - t*t*(1-t)*width*b["derivative"])
    raise AssertionError("Invalid spline")

# Every stronger input must yield a stronger mountain, rather than a plateau.
samples = [spline(transfer, i/1000) for i in range(-2000, 4001)]
assert all(a < b for a, b in zip(samples, samples[1:]))
assert all(abs(spline(transfer, i/100)-i/100) < 1e-9 for i in range(-200, 1))

tops = set()
for i in range(-200, 201):
    detail = spline(curve, i/100)
    assert 0 <= detail <= 1
    reference = 1 + extension["left"] * detail
    previous = -1
    for raw_strength in (0, .1, .4, .9, 1, 1.01, 1.1, 1.2, 1.5, 2, 2.5, raw["left"]):
        s = reference + spline(transfer, raw_strength-reference)
        if raw_strength <= reference:
            assert abs(s - raw_strength) < 1e-9
        assert s > previous
        previous = s
        ys = [y for y in range(minimum, top) if mountain(s, y) > 1e-6]
        if ys:
            assert ys == list(range(ys[0], ys[-1] + 1)) and ys[0] <= 40
            assert ys[-1] < top-1
        if raw_strength == raw["left"]:
            tops.add(ys[-1])
assert len(tops) > 1, tops
assert min(tops) > spec["peakHeight"], tops
assert max(tops) < top-1, tops

beta = j("beta_overhangs")
assert beta["right"]["right"]["right"]["left"]["right"]["to_coordinate"] <= 190
karst = j("karst_spires")
assert karst["right"]["right"]["right"]["left"]["right"]["to_coordinate"] <= 205
surface = j("surface_envelope")
assert surface["right"]["to_coordinate"] <= 224
upland = j("upland_lift")
assert upland["right"]["right"]["right"]["to_coordinate"] <= 224
relief = j("sculpted_relief")
def references(obj):
    if isinstance(obj, str):
        return {obj}
    if isinstance(obj, dict):
        return set().union(*(references(v) for v in obj.values()))
    if isinstance(obj, list):
        return set().union(*(references(v) for v in obj))
    return set()
assert "sofronpack:terrain/mountain_ranges" not in references(relief), "Mountain mass may not be added as an altitude pulse"
assert "sofronpack:terrain/upland_lift" in references(relief)
print(f"PASS: grounded mountains remain connected, uncapped peak samples Y{min(tops)}..{max(tops)}, world top Y{top}, sea Y63")
