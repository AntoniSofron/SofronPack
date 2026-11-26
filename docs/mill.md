# Mill (v0.1.89)

Place a mill, a contiguous vertical column of 1–32 mill shafts directly
above it, and windmill sails directly above the final shaft. Gaps, other
blocks and 33+ shafts do not transmit power. Sails can face any horizontal
direction; wind and rotation animation are not simulated in this release.

Right-click the mill with wheat to insert up to eight. An operational
mill converts one wheat to one flour every 80 game ticks (four seconds at
20 TPS). Right-click with an empty hand to collect finished flour; when
there is no output, sneak-right-click empty-handed to retrieve unprocessed
wheat. The machine pauses if disconnected or the output is full, and
resumes automatically. The buffer is stored in block states and loot
preserves its contents when broken normally. No extra GUI or hopper
automation; no flour food effect or vanilla bread recipe changes.

The three blocks are craftable and appear in Functional Blocks; flour
appears in Ingredients. The initial sails model is a single block with
static cloth blades; the shaft is a narrow wooden post with metal bands.
