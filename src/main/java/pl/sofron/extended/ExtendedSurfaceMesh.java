package pl.sofron.extended;

import me.cortex.voxy.client.core.util.ScanMesher2D;
import java.nio.ByteBuffer;
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
    private final ByteBuffer staging = ByteBuffer.allocate(SIDE * SIDE * 5 * 4 * ExtendedVoxelMesher.VERTEX_BYTES)
            .order(ByteOrder.nativeOrder());

    private static final class Columns {
        final VoxelChunk source;
        // Store the winning full-resolution column index for each coarser footprint.
        final short[][] winner = {new short[256], new short[64], new short[16]};
        final int[] height = new int[256];
        final short[] top = new short[256], side = new short[256];

        Columns(VoxelChunk source) {
            this.source = source;
            short[] cells = source.unpack();
            for (int column = 0; column < 256; column++) {
                winner[0][column] = (short) column;
                height[column] = Integer.MIN_VALUE;
                for (int y = source.yCount() - 1; y >= 0; y--) {
                    int at = y * 256 + column;
                    if (cells[at] == 0) continue;
                    height[column] = source.minY() + y + 1;
                    top[column] = cells[at];
                    side[column] = y > 0 && cells[at - 256] != 0 ? cells[at - 256] : cells[at];
                    break;
                }
            }
            // Occupied-child reduction follows Voxy's hierarchical mip selection.
            // At the surface, retain the highest occupied child through both levels.
            for (int level = 1; level <= 2; level++) {
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
        Columns[] data = new Columns[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            VoxelChunk chunk = chunks[i];
            if (chunk == null) continue;
            long key = ExtendedVoxelStore.pack(chunk.chunkX(), chunk.chunkZ());
            Columns columns = cache.get(key);
            if (columns == null || columns.source != chunk) {
                columns = new Columns(chunk);
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
            if (own == null) continue;
            var tops = new ScanMesher2D() {
                @Override protected void emitQuad(int x, int z, int length, int width, long key) {
                    int column = own.column(x * detail, z * detail, detail);
                    String name = own.source.palette()[own.top[column]];
                    var uv = materials.face(name, Direction.UP);
                    if (uv == null) return;
                    int tint = color(own.source, name, uv.tint());
                    int x0 = ox + (x + 1 - length) * detail, x1 = ox + (x + 1) * detail;
                    int z0 = oz + (z + 1 - width) * detail, z1 = oz + (z + 1) * detail;
                    float u = packUv(uv.u0(), uv.u1()), v = packUv(uv.v0(), uv.v1());
                    for (int corner = 0; corner < 4; corner++) {
                        staging.putFloat(corner < 2 ? x0 : x1).putFloat(own.height[column])
                                .putFloat(corner == 0 || corner == 3 ? z0 : z1)
                                .putFloat(u).putFloat(v).put((byte)(tint >> 16))
                                .put((byte)(tint >> 8)).put((byte)tint).put((byte)254);
                    }
                }
            };
            for (int z = oz; z < oz + 16; z += detail) {
                for (int x = ox; x < ox + 16; x += detail) {
                    int column = own.column(x, z, detail);
                    int h = own.height[column];
                    tops.putNext(h == Integer.MIN_VALUE ? 0 : ((long)h << 16) | own.top[column]);
                    if (h == Integer.MIN_VALUE) continue;
                    for (int f = 2; f < 6; f++) {
                        int xx = x + (f == 4 ? -detail : f == 5 ? detail : 0);
                        int zz = z + (f == 2 ? -detail : f == 3 ? detail : 0);
                        Columns next = owner(data, xx, zz);
                        if (next == null) continue;
                        int bottom = next.height[next.column(xx, zz, detail)];
                        // At a mixed-resolution boundary the neighbouring tile may use a
                        // lower column. Close the edge against its minimum covered height.
                        if ((xx >> 4) != cx || (zz >> 4) != cz) {
                            for (int j = 0; j < detail; j++) {
                                int ex = f < 4 ? x + j : f == 4 ? x - 1 : x + detail;
                                int ez = f >= 4 ? z + j : f == 2 ? z - 1 : z + detail;
                                bottom = Math.min(bottom, next.height[next.column(ex, ez, 1)]);
                            }
                        }
                        bottom = Math.max(own.source.minY(), bottom);
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
        String name = own.source.palette()[id];
        var uv = materials.face(name, DIRECTIONS[face]);
        if (uv == null) return;
        int tint = color(own.source, name, uv.tint());
        ExtendedVoxelMesher.emit(staging, x, y, z, width, height, face, uv, tint);
    }
    static int color(VoxelChunk own, String name, int tint) {
        if (tint != -1) return tint;
        if (name.contains("water")) return own.waterTint();
        if (name.contains("leaves") || name.contains("vine")) return own.foliageTint();
        return own.grassTint();
    }

    // Two 15-bit atlas coordinates in a finite, normal float vertex attribute.
    static float packUv(float low, float high) {
        int bits = Math.round(low * 32767f) | (Math.round(high * 32767f) << 15);
        return Float.intBitsToFloat(bits + 0x00800000);
    }

}
