package pl.sofron.extended;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * So-called "far tiles" are 16x16 Minecraft chunks, with four surface quads
 * per chunk. Position is local to the tile (and therefore float-precise even
 * millions of blocks from spawn). The GPU uses Minecraft's position/color
 * vertex layout: xyz-float32 and rgba-ubyte.
 */
public final class ExtendedWorldMeshBuilder {
    public static final int TILE_CHUNKS = 16;
    public static final int VERTEX_BYTES = 16;
    public static final int MAX_QUADS = TILE_CHUNKS * TILE_CHUNKS * 4;

    public record Mesh(ByteBuffer vertices, int quadCount) {
        public Mesh {
            if (vertices.remaining() != quadCount * 4 * VERTEX_BYTES) {
                throw new IllegalArgumentException("Invalid far terrain vertex count");
            }
        }
    }

    @FunctionalInterface
    public interface Lookup {
        ExtendedWorldStorage.SurfaceChunk get(int chunkX, int chunkZ);
    }

    private ExtendedWorldMeshBuilder() {}

    public static Mesh build(int tileX, int tileZ, int centerX, int centerZ,
                             int ordinaryRenderDistance, int farRadius, Lookup lookup) {
        if (farRadius < 1 || farRadius > 256) throw new IllegalArgumentException("radius");
        ByteBuffer data = ByteBuffer.allocateDirect(MAX_QUADS * 4 * VERTEX_BYTES)
                .order(ByteOrder.nativeOrder());
        int quads = 0;
        int tileOriginX = tileX * TILE_CHUNKS, tileOriginZ = tileZ * TILE_CHUNKS;
        for (int dz = 0; dz < TILE_CHUNKS; dz++) {
            for (int dx = 0; dx < TILE_CHUNKS; dx++) {
                int cx = tileOriginX + dx, cz = tileOriginZ + dz;
                int distance = Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ));
                if (distance <= ordinaryRenderDistance || distance > farRadius) continue;
                var chunk = lookup.get(cx, cz);
                if (chunk == null) continue;

                short[] heights = chunk.heights();
                int[] colors = chunk.colors();
                int bx = dx * 16, bz = dz * 16;
                for (int z = 0; z < 2; z++) {
                    for (int x = 0; x < 2; x++) {
                        int gx = bx + x * 8, gz = bz + z * 8;
                        float y00 = heights[z * 3 + x] - .02f;
                        float y10 = heights[z * 3 + x + 1] - .02f;
                        float y01 = heights[(z + 1) * 3 + x] - .02f;
                        float y11 = heights[(z + 1) * 3 + x + 1] - .02f;
                        int color = colors[z * 2 + x];
                        float gradient = Math.abs(y01 - y00) + Math.abs(y10 - y00);
                        float shade = Math.max(.67f, 1.0f - gradient / 180f);
                        rgba(data, gx, y00, gz, color, shade);
                        rgba(data, gx, y01, gz + 8, color, shade);
                        rgba(data, gx + 8, y11, gz + 8, color, shade);
                        rgba(data, gx + 8, y10, gz, color, shade);
                        quads++;
                    }
                }
            }
        }
        data.flip();
        return new Mesh(data, quads);
    }

    private static void rgba(ByteBuffer out, float x, float y, float z, int rgb, float shade) {
        int r = Math.clamp(Math.round(((rgb >>> 16) & 255) * shade), 0, 255);
        int g = Math.clamp(Math.round(((rgb >>> 8) & 255) * shade), 0, 255);
        int b = Math.clamp(Math.round((rgb & 255) * shade), 0, 255);
        out.putFloat(x).putFloat(y).putFloat(z)
                .put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
    }
}
