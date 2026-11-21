package pl.sofron.extended;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Single-JAR distant-world surface database. Owns snapshot extraction, an
 * append-only versioned disk cache, and cross-thread publication to the client.
 * No external LOD mod or additional simulation tickets are required.
 */
public final class ExtendedWorldStorage {
    private static final int MAGIC = 0x53465731; // SFW1
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 8;
    private static final int RECORD_BYTES = 42;
    private static volatile Session session;
    private static volatile long generation;

    private ExtendedWorldStorage() {}

    /** Nine corner heights (3x3), four 8x8 surface colours. */
    public record SurfaceChunk(int chunkX, int chunkZ, short[] heights, int[] colors) {
        public SurfaceChunk {
            if (heights.length != 9 || colors.length != 4) {
                throw new IllegalArgumentException("Invalid 8-block LOD grid");
            }
            heights = heights.clone();
            colors = colors.clone();
        }
        public boolean sameSurface(SurfaceChunk other) {
            return other != null && Arrays.equals(heights, other.heights) && Arrays.equals(colors, other.colors);
        }
    }

    private static final class Session {
        final Path file;
        final Map<Long, SurfaceChunk> chunks = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
        DataOutputStream writer;
        int unflushed;

        Session(Path file) { this.file = file; }

        void open() throws IOException {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                // A sudden power loss can truncate the final append. Never
                // append behind a torn record or future reloads will be
                // misaligned. Preserve every previously completed record.
                long size = Files.size(file);
                if (size < HEADER_BYTES) {
                    throw new IOException("Distant-world cache header is incomplete: " + file);
                }
                long complete = HEADER_BYTES
                        + ((size - HEADER_BYTES) / RECORD_BYTES) * RECORD_BYTES;
                if (complete != size) {
                    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        channel.truncate(complete);
                    }
                }
                try (DataInputStream in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(file)))) {
                    if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                        throw new IOException("Unsupported distant-world database format: " + file);
                    }
                    while (true) {
                        try {
                            SurfaceChunk chunk = readChunk(in);
                            chunks.put(pack(chunk.chunkX(), chunk.chunkZ()), chunk);
                        } catch (EOFException end) { break; }
                    }
                }
            } else {
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE_NEW))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                }
            }
            writer = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(file, StandardOpenOption.APPEND), 32768));
        }

        void write(SurfaceChunk snapshot) throws IOException {
            writeChunk(writer, snapshot);
            if (++unflushed >= 32) flush();
        }

        void flush() throws IOException {
            if (writer != null) writer.flush();
            unflushed = 0;
        }

        void close() {
            if (writer == null) return;
            try {
                writer.close();
                writer = null;
                // Updates to nearby chunks append new revisions. Compact only
                // on world close, and only when the log has grown substantially
                // beyond its unique set of surfaces.
                long minimum = HEADER_BYTES + (long)chunks.size() * RECORD_BYTES;
                if (Files.size(file) > Math.max(minimum * 3L, minimum + 4_194_304L)) {
                    compact();
                }
            } catch (IOException e) {
                System.err.println("[SofronPack] Extended World save failed: " + e.getMessage());
            }
        }

        void compact() throws IOException {
            Path temporary = file.resolveSibling(file.getFileName() + ".compact");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING), 32768))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                for (SurfaceChunk chunk : chunks.values()) writeChunk(out, chunk);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Server-thread only. The path includes the dimension and world save. */
    public static synchronized void open(Path worldDirectory, String dimension) {
        String safe = dimension.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path file = worldDirectory.resolve("sofronpack-extended-world")
                .resolve(safe + ".surface.bin").toAbsolutePath().normalize();
        if (session != null && session.file.equals(file)) return;
        close();
        Session next = new Session(file);
        try {
            next.open();
            session = next;
            generation++;
            System.out.println("[SofronPack] Extended World: " + next.chunks.size()
                    + " cached surfaces from " + file);
        } catch (IOException problem) {
            next.close();
            System.err.println("[SofronPack] Extended World storage error: " + problem.getMessage());
        }
    }

    public static synchronized void close() {
        Session old = session;
        session = null;
        if (old != null) old.close();
        generation++;
    }

    public static synchronized void flush() {
        try {
            if (session != null) session.flush();
        } catch (IOException problem) {
            System.err.println("[SofronPack] Extended World flush error: " + problem.getMessage());
        }
    }

    public static long generation() { return generation; }
    public static int count() { Session s = session; return s == null ? 0 : s.chunks.size(); }
    public static boolean has(int x, int z) {
        Session s = session;
        return s != null && s.chunks.containsKey(pack(x, z));
    }
    public static SurfaceChunk get(int x, int z) {
        Session s = session;
        return s == null ? null : s.chunks.get(pack(x, z));
    }
    public static Long pollChanged() {
        Session s = session;
        return s == null ? null : s.changed.poll();
    }

    /** Called on the server thread, never on the rendering thread. */
    public static synchronized boolean put(SurfaceChunk snapshot) {
        Session s = session;
        if (s == null) return false;
        long id = pack(snapshot.chunkX(), snapshot.chunkZ());
        SurfaceChunk old = s.chunks.get(id);
        if (snapshot.sameSurface(old)) return false;
        try {
            s.write(snapshot); // Persist first; publish only after a successful append.
            s.chunks.put(id, snapshot);
            s.changed.offer(id);
            return true;
        } catch (IOException problem) {
            System.err.println("[SofronPack] Extended World write error: " + problem.getMessage());
            return false;
        }
    }

    public static SurfaceChunk sample(ServerLevel level, LevelChunk chunk) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        short[] heights = new short[9];
        int[] colors = new int[4];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
            int px = x == 2 ? 15 : x * 8;
            int pz = z == 2 ? 15 : z * 8;
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz);
            heights[z * 3 + x] = (short) Math.clamp(surface, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            int px = x * 8 + 4, pz = z * 8 + 4;
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz);
            cursor.set((cx << 4) + px, Math.max(level.getMinY(), y - 1), (cz << 4) + pz);
            BlockState surface = chunk.getBlockState(cursor);
            int rgb = surface.getMapColor(level, cursor).col & 0xFFFFFF;
            colors[z * 2 + x] = rgb != 0 ? rgb : 0x708777;
        }
        return new SurfaceChunk(cx, cz, heights, colors);
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static void writeChunk(DataOutputStream out, SurfaceChunk snapshot) throws IOException {
        out.writeInt(snapshot.chunkX());
        out.writeInt(snapshot.chunkZ());
        for (short height : snapshot.heights()) out.writeShort(height);
        for (int color : snapshot.colors()) out.writeInt(color);
    }

    static SurfaceChunk readChunk(DataInputStream in) throws IOException {
        int x = in.readInt(), z = in.readInt();
        short[] heights = new short[9];
        int[] colors = new int[4];
        for (int i = 0; i < heights.length; i++) heights[i] = in.readShort();
        for (int i = 0; i < colors.length; i++) colors[i] = in.readInt();
        return new SurfaceChunk(x, z, heights, colors);
    }
}
