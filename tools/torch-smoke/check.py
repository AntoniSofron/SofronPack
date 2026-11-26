from pathlib import Path
from PIL import Image, ImageStat

def color(torch):
    image = Image.open(Path("run/screenshots") / (torch + ".png")).convert("RGB")
    # Gradle can reuse a daemon attached to a larger virtual display. Locate
    # the 960x540 game window before converting viewport fractions to pixels.
    bounds = image.getbbox()
    assert bounds is not None, "Captured an empty frame"
    image = image.crop(bounds)
    w, h = image.size
    assert (w, h) == (960, 540), f"Unexpected game viewport {w}x{h}"
    # Same neutral floor in every frame. Exclude the flame, crosshair and HUD.
    patch = image.crop((int(w*.5+h*.12), int(h*.48), int(w*.5+h*.18), int(h*.52)))
    return tuple(round(value, 2) for value in ImageStat.Stat(patch).mean)

soul, copper, red = (color(t) for t in ("soul_torch","copper_torch","redstone_torch"))
print("Floor RGB for soul, copper, redstone:", soul, copper, red, flush=True)
assert soul[2] > soul[0] * 1.20, soul
assert copper[1] > max(copper[0], copper[2]) * 1.25, copper
assert red[0] > max(red[1:]) * 1.22, red
