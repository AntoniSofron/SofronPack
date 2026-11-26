# RGB lighting hot paths (0.1.101)

Equal dynamic emitter profiles share one multi-source breadth-first propagation. Per-channel maxima and the existing 33-cubed field, source limits and attenuation are unchanged. Neighbor indices use a precomputed boundary mask. A bounded world-coordinate collision cache retains overlapping sections across player movement; block and chunk edits invalidate affected sections and a periodic refresh protects against missed changes. Dynamic GPU updates transfer only changed pages when that is cheaper.

Static propagation stops when all lattice nodes with nonzero radial weight are reachable. Source and opacity scanning share one pass through mixed sections. The atmospheric eye probe avoids copying out-of-range stationary source references. No new visual quality or view-distance limits are introduced. Runtime performance and shader/backend behavior require in-game validation.
