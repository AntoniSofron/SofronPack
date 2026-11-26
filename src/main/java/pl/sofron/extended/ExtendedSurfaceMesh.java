package pl.sofron.extended;

import pl.sofron.extended.mesh.SofronScanMesher2D;
import java.nio.ByteBuffer;
import pl.sofron.terralab.trees.SnowLeafGradient;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import net.minecraft.core.Direction;
import pl.sofron.extended.ExtendedVoxelMesher.MaterialResolver;
import pl.sofron.extended.ExtendedVoxelMesher.Mesh;
import pl.sofron.extended.ExtendedVoxelStore.VoxelChunk;

/** Exterior terrain for the distant pass. All decode and extraction run on one worker. */
public final class ExtendedSurfaceMesh {
    private static final int TILE = ExtendedVoxelMesher.TILE_CHUNKS;
    private static final int SIDE = TILE * 16;
    private static final Direction[] DIRECTIONS = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private final LinkedHashMap<Long, Columns> cache = new LinkedHashMap<>(512, .75f, true);
    private final ByteBuffer staging = ByteBuffer.allocate(SIDE * SIDE * 10 * 4 * ExtendedVoxelMesher.VERTEX_BYTES)
            .order(ByteOrder.nativeOrder());

    private static final class Columns {
        final VoxelChunk source;
        final ExtendedTerrainPreview.Tile preview;
        final String[] palette;
        final int minimum, grass, foliage, water;
        // Store the winning full-resolution column index for each coarser footprint.
        final short[][] winner = {new short[256], new short[64], new short[16], new short[4]};
        final int[] height = new int[256];
        final short[] top = new short[256], side = new short[256];
        final byte[] topFrost = new byte[256], sideFrost = new byte[256];

        final short[] backing = new short[256];
        final int[] backingHeight = new int[256];

        Columns(VoxelChunk source, MaterialResolver materials) {
            this.source = source;
            preview = null;
            palette = source.palette();
            minimum = source.minY(); grass = source.grassTint(); foliage = source.foliageTint(); water = source.waterTint();
            boolean[] solid = new boolean[palette.length];
            for (int i = 1; i < palette.length; i++) {
                var topFace = materials.face(palette[i], Direction.UP);
                solid[i] = topFace != null && (!topFace.translucent() || water(palette[i]));
            }
            short[] cells = source.unpack();
            byte[] frost = SnowLeafGradient.voxelSteps(palette, cells);
            for (int column = 0; column < 256; column++) {
                winner[0][column] = (short) column;
                height[column] = Integer.MIN_VALUE;
                for (int y = source.yCount() - 1; y >= 0; y--) {
                    int at = y * 256 + column;
                    if (cells[at] == 0) continue;
                    height[column] = source.minY() + y + 1;
                    top[column] = cells[at];
                    side[column] = y > 0 && cells[at - 256] != 0 ? cells[at - 256] : cells[at];
                    if (frost != null) {
                        topFrost[column] = frost[at];
                        sideFrost[column] = frost[y > 0 && cells[at - 256] != 0 ? at - 256 : at];
                    }
                    if (!solid[cells[at]]) {
                        for (int by = y - 1; by >= 0; by--) {
                            short id = cells[by * 256 + column];
                            if (!solid[id]) continue;
                            backing[column] = id;
                            backingHeight[column] = minimum + by + 1;
                            break;
                        }
                    }
                    break;
                }
            }
            reduce();
        }

        Columns(ExtendedTerrainPreview.Tile tile, int chunkX, int chunkZ) {
            source = null;
            preview = tile;
            palette = ExtendedTerrainPreview.MATERIALS;
            int ox = (chunkX & 3) * 16, oz = (chunkZ & 3) * 16;
            minimum = tile.minimum;
            grass = foliage = tile.grass(ox + 8, oz + 8);
            water = tile.water(ox + 8, oz + 8);
            for (int i = 0; i < 256; i++) {
                winner[0][i] = (short)i;
                int x = ox + (i & 15), z = oz + (i >> 4);
                int ground = tile.ground(x, z);
                height[i] = Math.max(ground, tile.sea);
                side[i] = top[i] = (short)tile.material(x, z, ground);
            }
            reduce();
        }

        int ground(int column) {
            return backing[column] == 0 ? height[column] : backingHeight[column];
        }

        private void reduce() {
            // Occupied-child reduction follows SofronPack's hierarchical mip selection.
            // Keep the highest occupied child through all three reduction levels,
            // including the 8x8 farthest ring; never sample an empty corner.
            for (int level = 1; level <= 3; level++) {
                int width = 16 >> level;
                for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                    int child = (z * width * 2 + x) * 2;
                    short best = winner[level - 1][child];
                    for (int i = 1; i < 4; i++) {
                        short next = winner[level - 1][child + (i & 1) + (i >> 1) * width * 2];
                        if (height[next] >= height[best]) best = next;
                    }
                    winner[level][z * width + x] = best;
                }
            }
        }

        int column(int x, int z, int detail) {
            return winner[Integer.numberOfTrailingZeros(detail)][(z & 15) / detail * (16 / detail) + (x & 15) / detail];
        }
    }

    public Mesh build(long detailMap, VoxelChunk[] chunks, MaterialResolver materials) {
        return build(detailMap, chunks, materials, 0, 0, null);
    }

    public Mesh build(long detailMap, VoxelChunk[] chunks, MaterialResolver materials,
                      int tx, int tz, ExtendedTerrainPreview.Tile[] previews) {
        return build(detailMap, chunks, materials, tx, tz, previews, false);
    }

    public Mesh buildMissing(long detailMap, VoxelChunk[] chunks, MaterialResolver materials,
                             int tx, int tz, ExtendedTerrainPreview.Tile[] previews) {
        return build(detailMap, chunks, materials, tx, tz, previews, true);
    }

    private Mesh build(long detailMap, VoxelChunk[] chunks, MaterialResolver materials,
                       int tx, int tz, ExtendedTerrainPreview.Tile[] previews, boolean missingOnly) {
        Columns[] data = new Columns[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            VoxelChunk chunk = chunks[i];
            var preview = chunk == null && previews != null ? previews[i] : null;
            if (chunk == null && preview == null) continue;
            int chunkX = chunk == null ? tx * TILE + i % (TILE + 2) - 1 : chunk.chunkX();
            int chunkZ = chunk == null ? tz * TILE + i / (TILE + 2) - 1 : chunk.chunkZ();
            long key = ExtendedVoxelStore.pack(chunkX, chunkZ);
            Columns columns = cache.get(key);
            if (columns == null || columns.source != chunk || columns.preview != preview) {
                columns = chunk == null ? new Columns(preview, chunkX, chunkZ) : new Columns(chunk, materials);
                cache.put(key, columns);
                if (cache.size() > 2048) cache.pollFirstEntry();
            }
            data[i] = columns;
        }
        staging.clear();
        for (int cz = 0; cz < TILE; cz++) for (int cx = 0; cx < TILE; cx++) {
            int detail = 1 << (int)((detailMap >>> (2 * (cz * TILE + cx))) & 3);
            int ox = cx * 16, oz = cz * 16;
            Columns own = owner(data, ox, oz);
            if (own == null || (missingOnly && own.source != null)) continue;
            var tops = new SofronScanMesher2D() {
                @Override protected void emitQuad(int x, int z, int length, int width, long key) {
                    int column = own.column(x * detail, z * detail, detail);
                    String name = own.palette[own.top[column]];
                    var uv = materials.face(name, Direction.UP);
                    if (uv == null) return;
                    int tint = SnowLeafGradient.blend(color(own.grass, own.foliage, own.water, name, uv.tint()), own.topFrost[column]);
                    int x0 = ox + (x + 1 - length) * detail, x1 = ox + (x + 1) * detail;
                    int z0 = oz + (z + 1 - width) * detail, z1 = oz + (z + 1) * detail;
                    float u = packUv(uv.u0(), uv.u1()), v = packUv(uv.v0(), uv.v1());
                    for (int corner = 0; corner < 4; corner++) {
                        staging.putFloat(corner < 2 ? x0 : x1).putFloat(own.height[column] - surfaceInset(name))
                                .putFloat(corner == 0 || corner == 3 ? z0 : z1)
                                .putFloat(u).putFloat(v).put((byte)(tint >> 16))
                                .put((byte)(tint >> 8)).put((byte)tint).put((byte)(254 - 3 * uv.emission()));
                    }
                }
            };
            for (int z = oz; z < oz + 16; z += detail) {
                for (int x = ox; x < ox + 16; x += detail) {
                    int column = own.column(x, z, detail);
                    int h = own.height[column];
                    tops.putNext(h == Integer.MIN_VALUE ? 0 : ((long)h << 20) | ((long)own.topFrost[column] << 16) | own.top[column]);
                    if (h == Integer.MIN_VALUE) continue;
                    if (own.backing[column] != 0)
                        face(own, own.backing[column], x, own.backingHeight[column] - detail,
                                z, detail, detail, 0, materials);
                    for (int f = 2; f < 6; f++) {
                        int xx = x + (f == 4 ? -detail : f == 5 ? detail : 0);
                        int zz = z + (f == 2 ? -detail : f == 3 ? detail : 0);
                        Columns next = owner(data, xx, zz);
                        if (next == null) {
                            if (water(own.palette[own.top[column]]))
                                face(own, own.top[column], x, h - 1, z, detail, 1, f, materials);
                            continue;
                        }
                        int bottom = next.ground(next.column(xx, zz, detail));
                        // At a mixed-resolution boundary the neighbouring tile may use a
                        // lower column. Close the edge against its minimum covered height.
                        if ((xx >> 4) != cx || (zz >> 4) != cz) {
                            for (int j = 0; j < detail; j++) {
                                int ex = f < 4 ? x + j : f == 4 ? x - 1 : x + detail;
                                int ez = f >= 4 ? z + j : f == 2 ? z - 1 : z + detail;
                                bottom = Math.min(bottom, next.ground(next.column(ex, ez, 1)));
                            }
                        }
                        bottom = Math.max(own.minimum, bottom);
                        if (own.backing[column] != 0) {
                            int ground = own.backingHeight[column];
                            if (bottom < ground) face(own, own.backing[column], x, bottom, z,
                                    detail, ground - bottom, f, materials);
                            // Keep the canopy separate from the supporting hillside.
                            bottom = Math.max(ground, Math.max(bottom, h - detail));
                        }
                        if (bottom < h) face(own, h - bottom > 1 ? own.side[column] : own.top[column],
                                x, bottom, z, detail, h - bottom, f, materials);
                    }
                }
                tops.endRow();
            }
            tops.finish();
        }
        staging.flip();
        ByteBuffer vertices = ByteBuffer.allocateDirect(staging.remaining()).order(ByteOrder.nativeOrder());
        vertices.put(staging).flip();
        return new Mesh(vertices, vertices.remaining() / (4 * ExtendedVoxelMesher.VERTEX_BYTES));
    }

    private static Columns owner(Columns[] data, int x, int z) {
        return data[((z >> 4) + 1) * (TILE + 2) + (x >> 4) + 1];
    }

    private void face(Columns own, short id, int x, int y, int z, int width, int height, int face,
                      MaterialResolver materials) {
        String name = own.palette[id];
        var uv = materials.face(name, DIRECTIONS[face]);
        if (uv == null) return;
        int tint = color(own.grass, own.foliage, own.water, name, uv.tint());
        if (SnowLeafGradient.eligible(name)) {
            int column = own.column(x, z, width);
            tint = SnowLeafGradient.blend(tint, id == own.side[column] ? own.sideFrost[column] : own.topFrost[column]);
        }
        float inset = surfaceInset(name);
        int start = staging.position();
        ExtendedVoxelMesher.emit(staging, x, y, z, width, height - inset, face, uv, tint);
        float u = packUv(uv.u0(), uv.u1()), v = packUv(uv.v0(), uv.v1());
        for (int vertex = 0; vertex < 4; vertex++) {
            int at = start + vertex * ExtendedVoxelMesher.VERTEX_BYTES;
            staging.putFloat(at + 12, u).putFloat(at + 16, v);
            staging.put(at + 23, (byte)(254 - face / 2 - 3 * uv.emission()));
        }
    }
    static float surfaceInset(String name) {
        return water(name) ? 1f / 9f : 0f;
    }

    static boolean water(String state) {
        return state.equals("minecraft:water") || state.startsWith("minecraft:water[")
                || state.equals("minecraft:bubble_column") || state.startsWith("minecraft:bubble_column[");
    }

    static int color(int grass, int foliage, int water, String name, int tint) {
        if (tint != -1) return tint;
        if (water(name)) return water;
        if (name.contains("leaves")) {
            var palette = pl.sofron.terralab.BiomePalette.fromFoliage(foliage);
            if (name.startsWith("sofronpack:pine_leaves")) return palette == null ? pl.sofron.terralab.BiomePalette.DEFAULT_PINE : palette.pine();
            if (name.startsWith("sofronpack:fir_leaves")) return palette == null ? pl.sofron.terralab.BiomePalette.DEFAULT_FIR : palette.fir();
            if (name.startsWith("minecraft:spruce_leaves")) return palette == null ? 0x619961 : palette.spruce();
            if (name.startsWith("minecraft:pale_oak_leaves")) return 0xA0A69C;
            if (name.startsWith("minecraft:birch_leaves")) return palette == null ? 0x80A755 : palette.birch();
            if (name.startsWith("minecraft:oak_leaves") && palette != null && palette.oak() >= 0) return palette.oak();
            return foliage & 0xffffff;
        }
        if (name.contains("vine")) return foliage & 0xffffff;
        return grass;
    }

    // Two 15-bit atlas coordinates in a finite, normal float vertex attribute.
    public static float packUv(float low, float high) {
        int bits = Math.round(low * 32767f) | (Math.round(high * 32767f) << 15);
        return Float.intBitsToFloat(bits + 0x00800000);
    }

}
