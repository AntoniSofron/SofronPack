# Near-leaf geometry (SofronPack 26.3)

The **original vanilla leaf cube** is always present. The archive 1.rar
provided four extra thin, tilted planes per leaf block (two bottom and two
top) and 26 corresponding 32×32 RGBA textures. The extra planes are added
only for normal Minecraft/Sodium leaf geometry.

* Up to 8 horizontal chunks (128 blocks): the extra top/bottom planes are
  visible, using the supplied PNG sprites. Vanilla cube and its texture stay.
* Beyond 8 chunks: the existing GPU shader removes the thin planes
  immediately; distant section rebuilds also skip their quads. Moving back
  closer causes the omitted sections to rebuild, restoring the near model.
* Every distant-world LOD mesh is **vanilla-cube only**. The
  ExtendedVoxelAtlas never includes non-cube geometry for a block whose
  registry name ends with `_leaves`. Its cube faces use original vanilla
  textures, even though the close model has supplemental plates.
* Leaves remain cutout, biome-tinted where appropriate. No new blocks,
  blockstates, worldgen rules, or gameplay state are introduced.
* Original textures are bundled under `assets/sofronpack/textures/block`
  and do not replace `assets/minecraft/textures/block/*_leaves.png`.
  The corresponding near models extend Minecraft's existing model file path.
* The 11 vanilla species model definitions are active immediately. The
  three supplied poplar variants are packaged in the sofronpack namespace
  for use by a future registered poplar block; they do not create new trees
  or override another mod's poplar blocks.

This keeps all pre-existing SofronPack terrain, sky, lighting, food, and
streaming functionality unchanged.
