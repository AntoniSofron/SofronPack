# Pine and apple trees (v0.1.90)

Two independently registered wood families, native leaf decay and saplings.
All five existing pine NBT templates retain their exact geometry; their spruce
palette is remapped on placement. Pine saplings use native 1x1 and 2x2 pine
features, including podzol decoration for the latter. Apple saplings grow
oak-shaped trees with apple logs and leaves. Apple leaves alone drop apples
from trees (including Fortune); oak and dark oak leaf loot no longer drops apples.
Other apple sources such as chest loot and trading are untouched.
Apple trees replace nominal 0.75% of regular forest selector calls, 15% plains,
7.5% flower forest and 1.2% cherry grove calls; placement success varies.
No swamp or shrub apple trees. No conversion of existing chunks.

Building blocks, stripped logs, doors, trapdoors, fences, gates, stairs, slabs,
buttons, pressure plates, shelves, signs and hanging signs are included.
Complex door/sign/shelf textures initially reuse spruce visuals; logs/planks
have unique colors. Both families also include flower-pot saplings, normal and chest boats. Boats initially use spruce/oak models and textures, with independent entities, recipes and drops.

## Fir and chestnut (0.1.91)

Fir uses the five existing spruce and two giant spruce templates with fir blocks in the new Old Growth Fir Taiga. Single and 2x2 saplings use native fir features; mega fir uses podzol. Chestnut reuses apple shapes and has a separate chestnut item (0.5% base leaf drop, Fortune increases chance). Generation selectors use 0.5% of mixed forest attempts, 10% of plains attempts, and 35% of the oak branch in shrubland and savanna. Existing chunks are not regenerated. Textures are initially shared with spruce/apple. Chestnuts were initially non-food items.

## Ground decorations (0.1.92)

Apple and fancy apple trees use a small native place_on_ground decorator to scatter white petals; chestnut and fancy chestnut trees scatter fallen chestnuts. Each ground block has 1–4 segments, and breaking a fallen chestnut patch yields the corresponding number of chestnuts (subject to explosion decay). Chestnut items restore 1 hunger point and 1 saturation point (modifier 0.5), in-game only. A chestnut can also be crafted into a fallen chestnut decoration for placement.
