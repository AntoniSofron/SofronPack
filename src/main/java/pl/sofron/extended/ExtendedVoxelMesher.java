package pl.sofron.extended;

import net.minecraft.core.Direction;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Chunk-voxel surface extraction: emits axis-aligned, opaque Minecraft-style
 * block faces, not sloping terrain triangles. A 2-block voxel is used near the
 * normal view radius, 4 and 8 blocks farther away.
 */
public final class ExtendedVoxelMesher {
    public static final int TILE_CHUNKS = 4;
    public static final int VERTEX_BYTES = 24; // xyz float, uv float, rgba bytes
    public static final int MAX_QUADS = 16_384;

    public record MaterialUV(float u0, float v0, float u1, float v1,
                             int tint, boolean translucent) {}
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
     * @param detail 1 for 2-block cubes, 2 for 4-block cubes, 4 for 8-block cubes
     */
    public static Mesh build(int tileX, int tileZ, int cx, int cz, int near, int far,
                             int detail, ChunkLookup lookup, MaterialResolver materials) {
        if (detail != 1 && detail != 2 && detail != 4)
            throw new IllegalArgumentException("LOD resolution");
        if (far < 1 || far > 256) throw new IllegalArgumentException("radius");
        Decoded[] chunks = new Decoded[TILE_CHUNKS * TILE_CHUNKS];
        int minY = 0, yCount = 0;
        for (int z = 0; z < TILE_CHUNKS; z++) {
            for (int x = 0; x < TILE_CHUNKS; x++) {
                var chunk = lookup.get(tileX * TILE_CHUNKS + x, tileZ * TILE_CHUNKS + z);
                if (chunk != null) {
                    chunks[z * TILE_CHUNKS + x] = new Decoded(chunk);
                    minY = chunk.minY();
                    yCount = chunk.yCount();
                }
            }
        }
        if (yCount == 0) {
            return new Mesh(ByteBuffer.allocate(0), 0);
        }

        int side = TILE_CHUNKS * ExtendedVoxelStore.GRID;
        ByteBuffer data = ByteBuffer.allocateDirect(MAX_QUADS * 4 * VERTEX_BYTES)
                .order(ByteOrder.nativeOrder());
        int rendered = 0;
        int voxelUnit = ExtendedVoxelStore.CELL * detail;

        // No arbitrary diagonal interpolation. Each cell contributes at most
        // the six square faces of a crisp, flat-topped voxel.
        for (int y = 0; y < yCount; y += detail) {
            for (int z = 0; z < side; z += detail) {
                for (int x = 0; x < side; x += detail) {
                    int chunkX = tileX * TILE_CHUNKS + x / ExtendedVoxelStore.GRID;
                    int chunkZ = tileZ * TILE_CHUNKS + z / ExtendedVoxelStore.GRID;
                    int radius = Math.max(Math.abs(chunkX - cx), Math.abs(chunkZ - cz));
                    if (radius <= near || radius > far) continue;

                    String id = block(chunks, x, y, z, yCount, side);
                    if (id == null || id.equals("minecraft:air")) continue;

                    boolean[] visible = new boolean[6];
                    visible[0] = !solid(chunks, x, y + detail, z, yCount, side, id);
                    visible[1] = !solid(chunks, x, y - detail, z, yCount, side, id);
                    visible[2] = !solid(chunks, x, y, z - detail, yCount, side, id);
                    visible[3] = !solid(chunks, x, y, z + detail, yCount, side, id);
                    visible[4] = !solid(chunks, x - detail, y, z, yCount, side, id);
                    visible[5] = !solid(chunks, x + detail, y, z, yCount, side, id);

                    float bx = x * ExtendedVoxelStore.CELL;
                    float by = minY + y * ExtendedVoxelStore.CELL;
                    float bz = z * ExtendedVoxelStore.CELL;
                    for (int face = 0; face < 6; face++) {
                        if (!visible[face]) continue;
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
                        if (++rendered > MAX_QUADS) {
                            data.flip();
                            return new Mesh(data.limit(MAX_QUADS * 4 * VERTEX_BYTES), MAX_QUADS);
                        }
                        emit(data, bx, by, bz, voxelUnit, face, material);
                    }
                }
            }
        }
        data.flip();
        return new Mesh(data, rendered);
    }

    /** Test presence of a block; water and leaves remain separate textured voxels. */
    private static boolean solid(Decoded[] chunks, int x, int y, int z, int height,
                                 int side, String ownMaterial) {
        String id = block(chunks, x, y, z, height, side);
        return id != null && !id.equals("minecraft:air")
                && !id.contains("water") && !id.contains("lava")
                && !(ownMaterial.contains("leaves") && !id.contains("leaves"));
    }

    private static String block(Decoded[] chunks, int x, int y, int z, int height, int side) {
        if (x < 0 || z < 0 || y < 0 || x >= side || z >= side || y >= height)
            return null;
        int chunkX = x / ExtendedVoxelStore.GRID;
        int chunkZ = z / ExtendedVoxelStore.GRID;
        Decoded chunk = chunks[chunkZ * TILE_CHUNKS + chunkX];
        if (chunk == null) return null;
        int ax = x % ExtendedVoxelStore.GRID, az = z % ExtendedVoxelStore.GRID;
        int index = (y * ExtendedVoxelStore.GRID + az) * ExtendedVoxelStore.GRID + ax;
        if (index >= chunk.cells.length) return null;
        int id = Short.toUnsignedInt(chunk.cells[index]);
        return id < chunk.source.palette().length ? chunk.source.palette()[id] : null;
    }

    private static void emit(ByteBuffer out, float x, float y, float z, float n,
                             int face, MaterialUV uv) {
        float[][] p = switch (face) {
            case 0 -> new float[][] {{x,y+n,z},{x,y+n,z+n},{x+n,y+n,z+n},{x+n,y+n,z}};
            case 1 -> new float[][] {{x,y,z+n},{x,y,z},{x+n,y,z},{x+n,y,z+n}};
            case 2 -> new float[][] {{x+n,y,z},{x+n,y+n,z},{x,y+n,z},{x,y,z}};
            case 3 -> new float[][] {{x,y,z+n},{x,y+n,z+n},{x+n,y+n,z+n},{x+n,y,z+n}};
            case 4 -> new float[][] {{x,y,z},{x,y+n,z},{x,y+n,z+n},{x,y,z+n}};
            default -> new float[][] {{x+n,y,z+n},{x+n,y+n,z+n},{x+n,y+n,z},{x+n,y,z}};
        };
        float shade = switch (face) {
            case 0 -> 1.0f;
            case 1 -> 0.55f;
            case 2, 3 -> 0.82f;
            default -> 0.7f;
        };
        int tint = uv.tint();
        int r = Math.clamp(Math.round((tint >> 16 & 255) * shade), 0, 255);
        int g = Math.clamp(Math.round((tint >> 8 & 255) * shade), 0, 255);
        int b = Math.clamp(Math.round((tint & 255) * shade), 0, 255);
        float[] uu = {uv.u0(),uv.u0(),uv.u1(),uv.u1()};
        float[] vv = {uv.v0(),uv.v1(),uv.v1(),uv.v0()};
        for (int i = 0; i < 4; i++) {
            out.putFloat(p[i][0]).putFloat(p[i][1]).putFloat(p[i][2]);
            out.putFloat(uu[i]).putFloat(vv[i]);
            out.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        }
    }
}
