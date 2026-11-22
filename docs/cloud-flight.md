# SofronPack — altitude flight and cloud air resistance

Three existing visually rendered Minecraft Overworld cloud decks remain at
Y=256, 384 and 512, with eight-block vertical thickness.

- Powered flight (creative, spectator and ridden happy ghast), and elytra:
  +25% above the first deck, +50% above the second, +75% above the third
  (relative to its original movement in the same mode). Below Y=256:
  normal speed. Normal walking and other dimensions are unchanged.
- In an actual cloud cell, the horizontal and powered vertical flight
  displacement is halved, relative to the current altitude bonus.
- Falling players (including a descending glider) cross a cloud cell with
  30% less downward displacement; this is a soft downward air brake, not a
  hard stop or a reduction in horizontal survival walking.
- The existing three cloud meshes, shaders, colours and parallax are not
  modified. Each cloud cell covers 12 × 12 blocks. The cloud pattern moves
  at the vanilla rate of 0.03 blocks/tick, and the upper two decks retain
  their unique +23×400 and +47×400 tick phase offsets.
- The same world-time formula runs on server and client. With the default
  Minecraft cloud texture available in the Java resource path, the air
  resistance uses its actual cloud alpha mask; transparent gaps have no
  resistance. Client resource packs that alter clouds.png may cause visual
  differences from the server-authoritative default.
- On dedicated servers missing the client-only cloud texture, SofronPack
  falls back to a deterministic whole-band wind zone (8 blocks thick) rather
  than trusting mismatched client images. No extra random cloud positions
  or per-player seeds are introduced.
- This multiplies each individual `Entity.move(SELF, ...)` displacement
  rather than multiplying `setDeltaMovement` every tick; altitude bonuses
  cannot compound into unbounded acceleration.
- Do not alter vanilla movement modes, ordinary chunk simulation distances,
  any of the four LOD distances, or the world's saved data. OpenGL and
  Vulkan share the same movement code; no graphics driver dependence.
