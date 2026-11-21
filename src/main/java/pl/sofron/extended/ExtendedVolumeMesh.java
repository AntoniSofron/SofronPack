package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import net.minecraft.core.Direction;
import pl.sofron.extended.ExtendedVoxelMesher.MaterialResolver;
import pl.sofron.extended.ExtendedVoxelMesher.MaterialUV;
import pl.sofron.extended.ExtendedVoxelMesher.Mesh;
import pl.sofron.extended.ExtendedVoxelStore.VoxelChunk;

/** Worker-owned, paletted volume mips. Scan equal visible faces into textured rectangles. */
public final class ExtendedVolumeMesh {
    private static final int TILE = ExtendedVoxelMesher.TILE_CHUNKS;
    private static final Direction[] FACES = {Direction.UP, Direction.DOWN, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int[] SHADE = {255, 140, 209, 209, 179, 179};
    private final LinkedHashMap<Long, Volume> cache = new LinkedHashMap<>(128, .75f, true);
    private final ByteBuffer staging = ByteBuffer.allocate(ExtendedVoxelMesher.MAX_QUADS
            * 4 * ExtendedVoxelMesher.VERTEX_BYTES).order(ByteOrder.nativeOrder());

    private static final class Volume {
        final VoxelChunk source;
        final short[][] cells = new short[3][];
        final int[] opacity;
        final int top;
        Volume(VoxelChunk source, MaterialResolver materials) {
            this.source = source;
            cells[0] = source.unpack();
            opacity = new int[source.palette().length];
            for (int i = 1; i < opacity.length; i++) {
                MaterialUV uv = materials.face(source.palette()[i], Direction.UP);
                opacity[i] = uv == null ? 0 : uv.translucent() ? 1 : 2;
            }
            int end = cells[0].length;
            while (end > 0 && cells[0][end - 1] == 0) end--;
            top = (end + 255) >> 8;
        }

        void prepare(int level) {
            if (cells[level] != null) return;
            prepare(level - 1);
            int w = 16 >> level, previous = w * 2;
            int height = (top + (1 << level) - 1) >> level;
            short[] small = new short[w * w * height], large = cells[level - 1];
            for (int y = 0; y < height; y++) for (int z = 0; z < w; z++) for (int x = 0; x < w; x++) {
                int base = ((y * previous + z) * previous + x) * 2;
                int rank = 0;
                short representative = 0;
                // Voxy Mipper ranks non-air children by opacity then octant.
                for (int octant = 0; octant < 8; octant++) {
                    int at = base + (octant >> 2) + (octant & 1) * previous
                            + ((octant >> 1) & 1) * previous * previous;
                    if (at >= large.length) continue;
                    short id = large[at];
                    int score = (opacity[id] << 4) + octant;
                    if (id != 0 && opacity[id] != 0 && score >= rank) {
                        rank = score;
                        representative = id;
                    }
                }
                small[(y * w + z) * w + x] = representative;
            }
            cells[level] = small;
        }

        short get(int x, int y, int z, int level) {
            if (y < 0 || y >= ((top + (1 << level) - 1) & -(1 << level))) return 0;
            int w = 16 >> level;
            return cells[level][((y >> level) * w + ((z & 15) >> level)) * w + ((x & 15) >> level)];
        }
    }

    /** Null asks the caller to retain a complete surface mesh for exceptionally complex tiles. */
    public Mesh build(VoxelChunk[] chunks, int[] levels, MaterialResolver materials) {
        Volume[] data = new Volume[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            VoxelChunk source = chunks[i];
            if (source == null) continue;
            long key = ExtendedVoxelStore.pack(source.chunkX(), source.chunkZ());
            Volume volume = cache.get(key);
            if (volume == null || volume.source != source) {
                volume = new Volume(source, materials);
                cache.put(key, volume);
                if (cache.size() > 128) cache.pollFirstEntry();
            }
            volume.prepare(levels[i]);
            data[i] = volume;
        }
        staging.clear();
        for (int cz = 0; cz < TILE; cz++) for (int cx = 0; cx < TILE; cx++) {
            int owner = (cz + 1) * (TILE + 2) + cx + 1;
            Volume v = data[owner];
            if (v == null) continue;
            int level = levels[owner], step = 1 << level, width = 16 / step;
            int height = (v.top + step - 1) / step;
            for (int face = 0; face < 6; face++) {
                int planes = face < 2 ? height : width;
                int rows = face < 2 ? width : height;
                var scan = new Plane(v, materials, face, cx * 16, cz * 16, step);
                for (int slice = 0; slice < planes; slice++) {
                    scan.slice = slice * step;
                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < width; col++) {
                            int x = (face < 4 ? col : slice) * step;
                            int y = (face < 2 ? slice : row) * step;
                            int z = (face < 2 ? row : face < 4 ? slice : col) * step;
                            short id = v.get(x, y, z, level);
                            if (id == 0 || v.opacity[id] == 0) { scan.putNext(0); continue; }
                            int nx = x, ny = y, nz = z;
                            switch (face) {
                                case 0 -> ny += step;
                                case 1 -> ny--;
                                case 2 -> nz--;
                                case 3 -> nz += step;
                                case 4 -> nx--;
                                case 5 -> nx += step;
                            }
                            int neighbor = owner + (nx >> 4) + (nz >> 4) * (TILE + 2);
                            Volume other = data[neighbor];
                            boolean visible = false;
                            if (other != null) {
                                int adjacent = levels[neighbor];
                                if (face >= 2 && adjacent < level) {
                                    int fine = 1 << adjacent;
                                    for (int h = 0; !visible && h < step; h += fine)
                                        for (int t = 0; !visible && t < step; t += fine)
                                            visible = exposed(v, id, other, other.get(nx + (face < 4 ? t : 0),
                                                    ny + h, nz + (face >= 4 ? t : 0), adjacent));
                                } else {
                                    visible = exposed(v, id, other, other.get(nx, ny, nz, adjacent));
                                }
                            }
                            scan.putNext(visible ? id : 0);
                        }
                        scan.endRow();
                        if (scan.overflow) return null;
                    }
                    scan.finish();
                    if (scan.overflow) return null;
                }
            }
        }
        staging.flip();
        var vertices = ByteBuffer.allocateDirect(staging.remaining()).order(ByteOrder.nativeOrder());
        vertices.put(staging).flip();
        return new Mesh(vertices, vertices.remaining() / (4 * ExtendedVoxelMesher.VERTEX_BYTES));
    }

    private static boolean exposed(Volume own, short id, Volume next, short other) {
        return other == 0 || next.opacity[other] == 0 || (next.opacity[other] == 1
                && !(own == next ? id == other : own.source.palette()[id].equals(next.source.palette()[other])));
    }

    private final class Plane extends ScanMesher2D {
        final Volume v;
        final MaterialResolver materials;
        final int face, ox, oz, step;
        int slice;
        boolean overflow;

        Plane(Volume v, MaterialResolver materials, int face, int ox, int oz, int step) {
            this.v = v; this.materials = materials; this.face = face;
            this.ox = ox; this.oz = oz; this.step = step;
        }

        @Override protected void emitQuad(int col, int row, int width, int height, long key) {
            String name = v.source.palette()[(int)key];
            var uv = materials.face(name, FACES[face]);
            if (uv == null) return;
            if (staging.remaining() < 4 * ExtendedVoxelMesher.VERTEX_BYTES) { overflow = true; return; }
            int tint = ExtendedSurfaceMesh.color(v.source.grassTint(), v.source.foliageTint(), v.source.waterTint(), name, uv.tint());
            int shade = SHADE[face];
            float u = ExtendedSurfaceMesh.packUv(uv.u0(), uv.u1()), t = ExtendedSurfaceMesh.packUv(uv.v0(), uv.v1());
            int plane = slice + (face == 0 || face == 3 || face == 5 ? step : 0);
            for (int corner = 0; corner < 4; corner++) {
                int a = (col + 1 - (corner < 2 ? width : 0)) * step;
                int b = (row + 1 - (corner == 0 || corner == 3 ? height : 0)) * step;
                staging.putFloat(ox + (face < 4 ? a : plane))
                        .putFloat(v.source.minY() + (face < 2 ? plane : b))
                        .putFloat(oz + (face < 2 ? b : face < 4 ? plane : a))
                        .putFloat(u).putFloat(t)
                        .put((byte)((tint >> 16 & 255) * shade / 255))
                        .put((byte)((tint >> 8 & 255) * shade / 255))
                        .put((byte)((tint & 255) * shade / 255))
                        .put((byte)(254 - face / 2));
            }
        }
    }
}
