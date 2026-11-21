package pl.sofron.extended;

import net.minecraft.core.Direction;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Chunk-voxel surface extraction: emits axis-aligned, opaque Minecraft-style
 * block faces, not sloping terrain triangles. One-block cubes are preserved near the normal view radius;\n * 2- and 4-block cubes are used farther away.
 */
public final class ExtendedVoxelMesher {
    public static final int TILE_CHUNKS = 4;
    public static final int VERTEX_BYTES = 24; // xyz float, uv float, rgba bytes
    public static final int MAX_QUADS = 32_768;

    public record MaterialUV(float u0, float v0, float u1, float v1,
                             int tint, boolean translucent, int emission) {
        public MaterialUV(float u0, float v0, float u1, float v1, int tint, boolean translucent) {
            this(u0, v0, u1, v1, tint, translucent, 0);
        }
    }
    public record Mesh(ByteBuffer vertices, int quads) {
        public Mesh {
            if (vertices.remaining() != quads * 4 * VERTEX_BYTES)
                throw new IllegalArgumentException("Unexpected textured mesh size");
        }
    }

    @FunctionalInterface
    public interface MaterialResolver {
        MaterialUV face(String blockId, Direction direction);
    }

    @FunctionalInterface
    public interface ChunkLookup {
        ExtendedVoxelStore.VoxelChunk get(int x, int z);
    }

    // Flags for each quad corner: x=1, y=2, z=4. No per-quad allocations.
    private static final int[][] FACE_CORNERS = {
            {2, 6, 7, 3}, {4, 0, 1, 5}, {1, 3, 2, 0},
            {4, 6, 7, 5}, {0, 2, 6, 4}, {5, 7, 3, 1}
    };
    private static final int DECODED_CACHE_LIMIT = 256;
    // One per client render thread, not one decompressed volume per quad.
    // 256 x 16*16*worldHeight*2bytes ~= 50 MB at Minecraft's 384-block height.
    private static final LinkedHashMap<Long, Decoded> DECODED_CACHE =
            new LinkedHashMap<>(256, 0.75f, true);

    // Reuse the upload staging memory rather than creating ~3 MB of native
    // allocations on every LOD rebuild. The renderer consumes each Mesh
    // immediately before requesting another one.
    private static final ThreadLocal<ByteBuffer> VERTEX_STAGING =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(
                    MAX_QUADS * 4 * VERTEX_BYTES).order(ByteOrder.nativeOrder()));

    public static void clearCachedChunks() {
        DECODED_CACHE.clear();
        VERTEX_STAGING.remove();
    }

    private static Decoded decode(int x, int z, ChunkLookup lookup) {
        ExtendedVoxelStore.VoxelChunk source = lookup.get(x, z);
        if (source == null) return null;
        long key = ExtendedVoxelStore.pack(x, z);
        Decoded cached = DECODED_CACHE.get(key);
        if (cached != null && cached.source == source) return cached;
        Decoded fresh = new Decoded(source);
        DECODED_CACHE.put(key, fresh);
        if (DECODED_CACHE.size() > DECODED_CACHE_LIMIT) {
            Iterator<Long> eldest = DECODED_CACHE.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return fresh;
    }

    private static final class Decoded {
        final ExtendedVoxelStore.VoxelChunk source;
        final short[] cells;
        Decoded(ExtendedVoxelStore.VoxelChunk source) {
            this.source = source;
            cells = source.unpack();
        }
    }

    private ExtendedVoxelMesher() {}

    /**
     * @param detail 1 / 2 / 4 / 8 / 16 block voxels by distance
     */
    public static Mesh build(int tileX, int tileZ, int cx, int cz, int near, int far,
                             int detail, ChunkLookup lookup, MaterialResolver materials) {
        // Preserve backwards-compatible test behavior: normal near chunks are
        // assumed ready until a client-specific predicate is supplied.
        return build(tileX, tileZ, cx, cz, near, far, detail, lookup, materials,
                (chunkX, chunkZ) -> true);
    }

    public static Mesh build(int tileX, int tileZ, int cx, int cz, int near, int far,
                             int detail, ChunkLookup lookup, MaterialResolver materials,
                             BiPredicate<Integer, Integer> normalChunkReady) {
        if (detail != 1 && detail != 2 && detail != 4 && detail != 8 && detail != 16)
            throw new IllegalArgumentException("LOD resolution");
        if (far < 1 || far > 256) throw new IllegalArgumentException("radius");
        // Include one full neighbour-chunk halo to cull hidden faces at
        // tile boundaries. Otherwise each 4x4 tile exposes vertical curtains
        // of stone along its outer edge, even where a neighbouring tile exists.
        int stride = TILE_CHUNKS + 2;
        Decoded[] chunks = new Decoded[stride * stride];
        int minY = 0, yCount = 0;
        for (int z = -1; z <= TILE_CHUNKS; z++) {
            for (int x = -1; x <= TILE_CHUNKS; x++) {
                Decoded decoded = decode(tileX * TILE_CHUNKS + x,
                        tileZ * TILE_CHUNKS + z, lookup);
                if (decoded != null) {
                    chunks[(z + 1) * stride + (x + 1)] = decoded;
                    if (x >= 0 && x < TILE_CHUNKS && z >= 0 && z < TILE_CHUNKS) {
                        minY = decoded.source.minY();
                        yCount = decoded.source.yCount();
                    }
                }
            }
        }
        if (yCount == 0) {
            return new Mesh(ByteBuffer.allocate(0), 0);
        }

        int side = TILE_CHUNKS * ExtendedVoxelStore.GRID;
        // The client chunk lookup is comparatively expensive. Do it once
        // per source chunk, NOT once per voxel (formerly up to 1.5M calls).
        boolean[] skip = new boolean[TILE_CHUNKS * TILE_CHUNKS];
        for (int z = 0; z < TILE_CHUNKS; z++) {
            for (int x = 0; x < TILE_CHUNKS; x++) {
                int chunkX = tileX * TILE_CHUNKS + x;
                int chunkZ = tileZ * TILE_CHUNKS + z;
                int radius = Math.max(Math.abs(chunkX - cx), Math.abs(chunkZ - cz));
                // Absolute 7/8 handoff: near LOD vanishes even if its old
                // normal chunk isn't ready. The shader performs the same
                // clipping every frame for previously uploaded GPU buffers.
                // The shader uses Euclidean horizontal distance. Use the same
                // shape for CPU culling, including diagonal handoff areas.
                double horizontalChunks = Math.hypot(chunkX - cx, chunkZ - cz);
                skip[z * TILE_CHUNKS + x] = radius > far
                        || ExtendedLodHandoff.hidden(horizontalChunks, near)
                        || (radius <= near && normalChunkReady.test(chunkX, chunkZ));
            }
        }
        ByteBuffer data = VERTEX_STAGING.get();
        data.clear();
        int rendered = 0;
        int voxelUnit = ExtendedVoxelStore.CELL * detail;

        // No arbitrary diagonal interpolation. Each cell contributes at most
        // the six square faces of a crisp, flat-topped voxel.
        for (int y = 0; y < yCount; y += detail) {
            for (int z = 0; z < side; z += detail) {
                for (int x = 0; x < side; x += detail) {
                    // Retain cached LOD until the corresponding normal chunk
                    // is ready. Dynamic near/far transitions must not reveal gaps.
                    if (skip[(z / ExtendedVoxelStore.GRID) * TILE_CHUNKS
                            + x / ExtendedVoxelStore.GRID]) continue;

                    String id = block(chunks, x, y, z, yCount, side);
                    if (id == null || id.equals("minecraft:air")) continue;

                    // A bit mask replaces an allocated boolean[6] for every
                    // visible voxel, avoiding massive GC pressure.
                    int visible = 0;
                    if (!solid(chunks, x, y + detail, z, yCount, side, id)) visible |= 1;
                    if (!solid(chunks, x, y - detail, z, yCount, side, id)) visible |= 2;
                    if (!solid(chunks, x, y, z - detail, yCount, side, id)) visible |= 4;
                    if (!solid(chunks, x, y, z + detail, yCount, side, id)) visible |= 8;
                    if (!solid(chunks, x - detail, y, z, yCount, side, id)) visible |= 16;
                    if (!solid(chunks, x + detail, y, z, yCount, side, id)) visible |= 32;
                    if (visible == 0) continue;

                    float bx = x * ExtendedVoxelStore.CELL;
                    float by = minY + y * ExtendedVoxelStore.CELL;
                    float bz = z * ExtendedVoxelStore.CELL;
                    for (int face = 0; face < 6; face++) {
                        if ((visible & (1 << face)) == 0) continue;
                        // Unknown neighbours are NOT air. Otherwise missing
                        // distant snapshots paint huge vertical stone walls
                        // around every partial tile during streaming.
                        if (detail >= 4 && face >= 2 && unknownHorizontalNeighbour(
                                chunks, x, z, detail, face, side)) continue;
                        var dir = switch (face) {
                            case 0 -> Direction.UP;
                            case 1 -> Direction.DOWN;
                            case 2 -> Direction.NORTH;
                            case 3 -> Direction.SOUTH;
                            case 4 -> Direction.WEST;
                            default -> Direction.EAST;
                        };
                        MaterialUV material = materials.face(id, dir);
                        if (material == null) continue;
                        // Apply the source chunk's *real* biome tint to tinted
                        // block-atlas sprites, not an arbitrary palette colour.
                        int tint = material.tint();
                        if (tint == -1) {
                            Decoded owner = chunks[(z / ExtendedVoxelStore.GRID + 1) * stride
                                    + x / ExtendedVoxelStore.GRID + 1];
                            // Palette identifiers are stable and namespaced;
                            // avoid allocating a substring for every tinted face.
                            tint = id.startsWith("minecraft:water") || id.contains("_water")
                                    ? owner.source.waterTint()
                                    : id.contains("leaves") || id.contains("vine")
                                    ? owner.source.foliageTint() : owner.source.grassTint();
                        }
                        if (++rendered > MAX_QUADS) {
                            data.flip();
                            return new Mesh(data.limit(MAX_QUADS * 4 * VERTEX_BYTES).slice().order(ByteOrder.nativeOrder()), MAX_QUADS);
                        }
                        emit(data, bx, by, bz, voxelUnit, voxelUnit, face, material, tint);
                    }
                }
            }
        }
        data.flip();
        return new Mesh(data.slice().order(ByteOrder.nativeOrder()), rendered);
    }

    private static boolean unknownHorizontalNeighbour(
            Decoded[] chunks, int x, int z, int detail, int face, int side) {
        int xx = x + (face == 4 ? -detail : face == 5 ? detail : 0);
        int zz = z + (face == 2 ? -detail : face == 3 ? detail : 0);
        int grid = ExtendedVoxelStore.GRID;
        if (xx < -grid || zz < -grid || xx >= side + grid || zz >= side + grid)
            return true;
        int localX = Math.floorDiv(xx, grid) + 1;
        int localZ = Math.floorDiv(zz, grid) + 1;
        return chunks[localZ * (TILE_CHUNKS + 2) + localX] == null;
    }

    /** Test presence of a block; water and leaves remain separate textured voxels. */
    private static boolean solid(Decoded[] chunks, int x, int y, int z, int height,
                                 int side, String ownMaterial) {
        String id = block(chunks, x, y, z, height, side);
        if (id == null || id.equals("minecraft:air")) return false;
        boolean fluid = id.startsWith("minecraft:water") || id.startsWith("minecraft:lava");
        if (fluid) {
            // Compare the block name without allocating temporary substrings.
            int end = id.indexOf('[');
            int ownEnd = ownMaterial.indexOf('[');
            if (end < 0) end = id.length();
            if (ownEnd < 0) ownEnd = ownMaterial.length();
            return end == ownEnd && id.regionMatches(0, ownMaterial, 0, end);
        }
        return !(ownMaterial.contains("leaves") && !id.contains("leaves"));
    }

    private static String block(Decoded[] chunks, int x, int y, int z, int height, int side) {
        int grid = ExtendedVoxelStore.GRID;
        if (x < -grid || z < -grid || y < 0
                || x >= side + grid || z >= side + grid || y >= height)
            return null;
        int chunkX = Math.floorDiv(x, grid) + 1;
        int chunkZ = Math.floorDiv(z, grid) + 1;
        Decoded chunk = chunks[chunkZ * (TILE_CHUNKS + 2) + chunkX];
        if (chunk == null) return null;
        int ax = Math.floorMod(x, grid), az = Math.floorMod(z, grid);
        int index = (y * ExtendedVoxelStore.GRID + az) * ExtendedVoxelStore.GRID + ax;
        if (index >= chunk.cells.length) return null;
        int id = Short.toUnsignedInt(chunk.cells[index]);
        return id < chunk.source.palette().length ? chunk.source.palette()[id] : null;
    }

    static void emit(ByteBuffer out, float x, float y, float z, float n, float height,
                             int face, MaterialUV uv, int tint) {
        float shade = switch (face) {
            case 0 -> 1.0f;
            case 1 -> 0.55f;
            case 2, 3 -> 0.82f;
            default -> 0.7f;
        };
        int r = Math.clamp(Math.round((tint >> 16 & 255) * shade), 0, 255);
        int g = Math.clamp(Math.round((tint >> 8 & 255) * shade), 0, 255);
        int b = Math.clamp(Math.round((tint & 255) * shade), 0, 255);
        int[] corners = FACE_CORNERS[face];
        for (int i = 0; i < 4; i++) {
            int bits = corners[i];
            out.putFloat(x + ((bits & 1) != 0 ? n : 0f));
            out.putFloat(y + ((bits & 2) != 0 ? height : 0f));
            out.putFloat(z + ((bits & 4) != 0 ? n : 0f));
            out.putFloat(i < 2 ? uv.u0() : uv.u1());
            out.putFloat((i == 0 || i == 3) ? uv.v0() : uv.v1());
            out.put((byte)r).put((byte)g).put((byte)b).put((byte)255);
        }
    }
}
