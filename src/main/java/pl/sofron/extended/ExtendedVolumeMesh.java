package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import pl.sofron.extended.mesh.SofronScanMesher2D;
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
    private record ChunkMesh(VoxelChunk own, VoxelChunk west, VoxelChunk east, VoxelChunk north, VoxelChunk south,
                             int cx, int cz, byte[] bytes) {}
    private final LinkedHashMap<Long, ChunkMesh> recent = new LinkedHashMap<>(128, .75f, true);
    private int recentBytes;
    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    private ByteBuffer staging = ByteBuffer.allocate(ExtendedVoxelMesher.MAX_QUADS
            * 4 * ExtendedVoxelMesher.VERTEX_BYTES).order(ByteOrder.nativeOrder());

    private static final class Volume {
        final VoxelChunk source;
        // 1/2/4/8 XZ samples per generated voxel footprint.
        final short[][] cells = new short[4][];
        final int[] opacity;
        final ExtendedVoxelMesher.ModelFace[][] shapes;
        final int top;
        Volume(VoxelChunk source, MaterialResolver materials) {
            this.source = source;
            cells[0] = source.unpack();
            opacity = new int[source.palette().length];
            shapes = new ExtendedVoxelMesher.ModelFace[opacity.length][];
            for (int i = 1; i < opacity.length; i++) {
                MaterialUV uv = materials.face(source.palette()[i], Direction.UP);
                shapes[i] = materials.model(source.palette()[i]);
                opacity[i] = shapes[i] != null ? 1 : uv == null ? 0 : uv.translucent() ? 1 : 2;
            }
            int end = cells[0].length;
            while (end > 0 && cells[0][end - 1] == 0) end--;
            top = (end + 255) >> 8;
        }

        void prepare(int level) {
            if (cells[level] != null) return;
            prepare(level - 1);
            int w = 16 >> level, previous = w * 2;
            int height = top;
            short[] small = new short[w * w * height], large = cells[level - 1];
            for (int y = 0; y < height; y++) for (int z = 0; z < w; z++) for (int x = 0; x < w; x++) {
                int base = (y * previous + z * 2) * previous + x * 2;
                int rank = 0;
                short representative = 0;
                // Reduce the horizontal footprint only. Fluid boundaries retain their exact Y.
                for (int child = 0; child < 4; child++) {
                    int at = base + (child & 1) + (child >> 1) * previous;
                    if (at >= large.length) continue;
                    short id = large[at];
                    int score = (opacity[id] << 4) + child;
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
            if (y < 0 || y >= top) return 0;
            int w = 16 >> level;
            return cells[level][(y * w + ((z & 15) >> level)) * w + ((x & 15) >> level)];
        }

        // Only opaque SIDE walls can be merged vertically at distance.
        // Water, cutouts, foliage and all UP/DOWN faces stay precisely at
        // their original block Y, including the 62/63 sea-level boundary.
        short opaqueSlab(int x, int y, int z, int level, int yStep) {
            short candidate = 0;
            for (int dy = 0; dy < yStep && y + dy < top; dy++) {
                short id = get(x, y + dy, z, level);
                if (id != 0 && opacity[id] == 2) candidate = id;
            }
            return candidate;
        }
    }

    private void reserveQuad() {
        reserve(4 * ExtendedVoxelMesher.VERTEX_BYTES);
    }

    private void reserve(int count) {
        if (staging.remaining() >= count) return;
        var larger = ByteBuffer.allocate(Math.max(staging.position() + count, Math.multiplyExact(staging.capacity(), 2))).order(ByteOrder.nativeOrder());
        larger.put(staging.flip());
        staging = larger;
    }

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
            int stride = TILE + 2;
            boolean exact = level == 0 && levels[owner-1] == 0 && levels[owner+1] == 0
                    && levels[owner-stride] == 0 && levels[owner+stride] == 0;
            long key = ExtendedVoxelStore.pack(v.source.chunkX(), v.source.chunkZ());
            ChunkMesh old = exact ? recent.get(key) : null;
            if (old != null && old.own == v.source && old.cx == cx && old.cz == cz
                    && old.west == chunks[owner-1] && old.east == chunks[owner+1]
                    && old.north == chunks[owner-stride] && old.south == chunks[owner+stride]) {
                reserve(old.bytes.length);
                staging.put(old.bytes);
                continue;
            }
            int start = staging.position();
            int height = v.top;
            for (int face = 0; face < 6; face++) {
                int planes = face < 2 ? height : width;
                // Nearby chunks remain exact in XYZ. Farther away only
                // opaque side walls are combined into 2/4-high slabs.
                // The translucent/cutout pass remains one Y per block.
                int passes = face >= 2 && level > 0 ? 2 : 1;
                for (int pass = 0; pass < passes; pass++) {
                    int yStep = face < 2 || level == 0 || pass == 1 ? 1 : 1 << level;
                    boolean cutoutPass = face >= 2 && level > 0 && pass == 1;
                    int rows = face < 2 ? width : (height + yStep - 1) / yStep;
                    var scan = new Plane(v, materials, face, cx * 16, cz * 16, step, yStep);
                    for (int slice = 0; slice < planes; slice++) {
                        scan.slice = face < 2 ? slice : slice * step;
                        for (int row = 0; row < rows; row++) {
                            for (int col = 0; col < width; col++) {
                                int x = (face < 4 ? col : slice) * step;
                                int y = face < 2 ? slice : row * yStep;
                                int z = (face < 2 ? row : face < 4 ? slice : col) * step;
                                short id = yStep > 1 ? v.opaqueSlab(x, y, z, level, yStep)
                                        : v.get(x, y, z, level);
                                if (id == 0 || v.opacity[id] == 0
                                        || (cutoutPass && v.opacity[id] != 1)
                                        || (!cutoutPass && passes > 1 && yStep == 1
                                            && v.opacity[id] != 2)) {
                                    scan.putNext(0);
                                    continue;
                                }
                                if (level == 0 && v.shapes[id] != null) {
                                    if (face == 0) emitModel(data, owner, cx, cz, x, y, z, id);
                                    scan.putNext(0);
                                    continue;
                                }
                                int nx = x, ny = y, nz = z;
                                switch (face) {
                                    case 0 -> ny++;
                                    case 1 -> ny--;
                                    case 2 -> nz--;
                                    case 3 -> nz += step;
                                    case 4 -> nx--;
                                    case 5 -> nx += step;
                                }
                                int neighbor = owner + (nx >> 4) + (nz >> 4) * (TILE + 2);
                                Volume other = data[neighbor];
                                boolean visible = other == null;
                                if (other != null) {
                                    int adjacent = levels[neighbor];
                                    if (face >= 2 && adjacent < level) {
                                        int fine = 1 << adjacent;
                                        for (int t = 0; !visible && t < step; t += fine) {
                                            int sx = nx + (face < 4 ? t : 0);
                                            int sz = nz + (face >= 4 ? t : 0);
                                            short neighborId = yStep > 1
                                                    ? other.opaqueSlab(sx, ny, sz, adjacent, yStep)
                                                    : other.get(sx, ny, sz, adjacent);
                                            visible = exposed(v, id, other, neighborId);
                                        }
                                    } else {
                                        short neighborId = yStep > 1
                                                ? other.opaqueSlab(nx, ny, nz, adjacent, yStep)
                                                : other.get(nx, ny, nz, adjacent);
                                        visible = exposed(v, id, other, neighborId);
                                    }
                                }
                                scan.putNext(visible ? id : 0);
                            }
                            scan.endRow();
                        }
                        scan.finish();
                    }
                }
            }
            int size = staging.position() - start;
            if (exact && size <= CACHE_BYTES / 4) {
                if (old != null) { recent.remove(key); recentBytes -= old.bytes.length; }
                while (!recent.isEmpty() && (recentBytes + size > CACHE_BYTES || recent.size() >= 128))
                    recentBytes -= recent.pollFirstEntry().getValue().bytes.length;
                byte[] copy = new byte[size];
                staging.get(start, copy);
                recent.put(key, new ChunkMesh(v.source, chunks[owner-1], chunks[owner+1],
                        chunks[owner-stride], chunks[owner+stride], cx, cz, copy));
                recentBytes += size;
            }
        }
        staging.flip();
        var vertices = ByteBuffer.allocateDirect(staging.remaining()).order(ByteOrder.nativeOrder());
        vertices.put(staging).flip();
        return new Mesh(vertices, vertices.remaining() / (4 * ExtendedVoxelMesher.VERTEX_BYTES));
    }

    private void emitModel(Volume[] data, int owner, int cx, int cz, int x, int y, int z, short id) {
        Volume v = data[owner];
        for (var face : v.shapes[id]) {
            var dir = face.cull();
            if (dir != null) {
                int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
                Volume next = data[owner + (nx >> 4) + (nz >> 4) * (TILE + 2)];
                if (next != null && next.opacity[next.get(nx, y + dir.getStepY(), nz, 0)] == 2) continue;
            }
            reserveQuad();
            int rgb = ExtendedSurfaceMesh.color(v.source.grassTint(), v.source.foliageTint(),
                    v.source.waterTint(), v.source.palette()[id], face.tint());
            var p = face.vertices();
            for (int i = 0; i < 20; i += 5) {
                staging.putFloat(cx * 16 + x + p[i]).putFloat(v.source.minY() + y + p[i+1])
                        .putFloat(cz * 16 + z + p[i+2]).putFloat(p[i+3]).putFloat(p[i+4])
                        .put((byte)((rgb >> 16 & 255) * face.shade() / 255))
                        .put((byte)((rgb >> 8 & 255) * face.shade() / 255))
                        .put((byte)((rgb & 255) * face.shade() / 255)).put((byte)(128 + face.emission()));
            }
        }
    }

    private static boolean exposed(Volume own, short id, Volume next, short other) {
        return other == 0 || next.opacity[other] == 0 || (next.opacity[other] == 1
                && !(own == next ? id == other : own.source.palette()[id].equals(next.source.palette()[other])));
    }

    private final class Plane extends SofronScanMesher2D {
        final Volume v;
        final MaterialResolver materials;
        final int face, ox, oz, step, yStep;
        int slice;

        Plane(Volume v, MaterialResolver materials, int face, int ox, int oz,
              int step, int yStep) {
            this.v = v; this.materials = materials; this.face = face;
            this.ox = ox; this.oz = oz; this.step = step; this.yStep = yStep;
        }

        @Override protected void emitQuad(int col, int row, int width, int height, long key) {
            String name = v.source.palette()[(int)key];
            var uv = materials.face(name, FACES[face]);
            if (uv == null) return;
            reserveQuad();
            int tint = ExtendedSurfaceMesh.color(v.source.grassTint(), v.source.foliageTint(), v.source.waterTint(), name, uv.tint());
            int shade = SHADE[face];
            float u = ExtendedSurfaceMesh.packUv(uv.u0(), uv.u1()), t = ExtendedSurfaceMesh.packUv(uv.v0(), uv.v1());
            int plane = slice + (face == 0 ? 1 : face == 3 || face == 5 ? step : 0);
            for (int corner = 0; corner < 4; corner++) {
                int a = (col + 1 - (corner < 2 ? width : 0)) * step;
                int b = (row + 1 - (corner == 0 || corner == 3 ? height : 0))
                        * (face < 2 ? step : yStep);
                if (face >= 2) b = Math.min(v.top, b);
                staging.putFloat(ox + (face < 4 ? a : plane))
                        .putFloat(v.source.minY() + (face < 2 ? plane : b) - (face == 0 ? ExtendedSurfaceMesh.surfaceInset(name) : 0f))
                        .putFloat(oz + (face < 2 ? b : face < 4 ? plane : a))
                        .putFloat(u).putFloat(t)
                        .put((byte)((tint >> 16 & 255) * shade / 255))
                        .put((byte)((tint >> 8 & 255) * shade / 255))
                        .put((byte)((tint & 255) * shade / 255))
                        .put((byte)(254 - face / 2 - 3 * uv.emission()));
            }
        }
    }
}
