from pathlib import Path
from PIL import Image
from statistics import mean

def color(torch):
    image = Image.open(Path("run/screenshots") / (torch + ".png")).convert("RGB")
    w, h = image.size
    # Same neutral floor in every frame. Exclude the flame, crosshair and HUD.
    patch = image.crop((int(w*.5+h*.12), int(h*.48), int(w*.5+h*.18), int(h*.52)))
    return tuple(round(mean(c.getdata()), 2) for c in patch.split())

soul, copper, red = (color(t) for t in ("soul_torch","copper_torch","redstone_torch"))
print("Floor RGB for soul, copper, redstone:", soul, copper, red, flush=True)
assert soul[2] > soul[0]*1.12 and copper[1] > max(copper[0],copper[2])*1.10 and red[0] > max(red[1:])*1.12
