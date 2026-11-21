package pl.sofron.extended;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.RenderShape;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Block-material voxel data for the 64-chunk LOD. The data format is independent
 * of worldgen, resource packs and GPU backends, so existing world saves are safe.
 * A sampled block is an axis-aligned cube, never a triangulated height field.
 */
public final class ExtendedVoxelStore {
    public static final int CELL = 2;
    public static final int GRID = 16 / CELL;
    private static final int MAGIC = 0x53465632; // SFV2
    private static final int VERSION = 2;
    private static final int MAX_RECORD = 1_048_576;
    private static volatile Session current;
    private static volatile long generation;

    private ExtendedVoxelStore() {}

    /** A compressed immutable 8 x (height/2) x 8 material-palette grid. */
    public record VoxelChunk(int chunkX, int chunkZ, int minY, int yCount,
                             String[] palette, byte[] deflated) {
        public VoxelChunk {
            if (yCount < 1 || yCount > 1024 || palette.length == 0
                    || palette.length > 32767 || deflated.length > MAX_RECORD) {
                throw new IllegalArgumentException("Invalid voxel snapshot dimensions");
            }
            palette = palette.clone();
            deflated = deflated.clone();
        }

        public short[] unpack() {
            int expected = GRID * GRID * yCount * Short.BYTES;
            byte[] bytes = new byte[expected];
            Inflater inflater = new Inflater();
            inflater.setInput(deflated);
            try {
                int got = inflater.inflate(bytes);
                if (got != expected || !inflater.finished()) {
                    throw new IllegalArgumentException("Corrupted block LOD data");
                }
            } catch (DataFormatException ex) {
                throw new IllegalArgumentException("Corrupted block LOD data", ex);
            } finally {
                inflater.end();
            }
            short[] decoded = new short[expected / 2];
            for (int i = 0; i < decoded.length; i++) {
                decoded[i] = (short) ((bytes[i * 2] & 255) | ((bytes[i * 2 + 1] & 255) << 8));
            }
            return decoded;
        }

        public boolean same(VoxelChunk other) {
            return other != null && minY == other.minY && yCount == other.yCount
                    && Arrays.equals(palette, other.palette)
                    && Arrays.equals(deflated, other.deflated);
        }
    }

    private static final class Session {
        final Path file;
        final ConcurrentHashMap<Long, VoxelChunk> chunks = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
        DataOutputStream output;
        int unflushed;
        Session(Path file) { this.file = file; }

        void load() throws IOException {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                try (DataOutputStream header = new DataOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE_NEW))) {
                    header.writeInt(MAGIC);
                    header.writeInt(VERSION);
                }
            }
            long lastGood = 8L;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    Files.newInputStream(file), 65536))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    throw new IOException("Unsupported voxel cache: " + file);
                }
                while (true) {
                    int size;
                    try { size = in.readInt(); }
                    catch (EOFException eof) { break; }
                    if (size < 20 || size > MAX_RECORD) break;
                    int crc;
                    try { crc = in.readInt(); }
                    catch (EOFException eof) { break; }
                    byte[] payload = in.readNBytes(size);
                    if (payload.length != size) break;
                    CRC32 checksum = new CRC32();
                    checksum.update(payload);
                    if ((int)checksum.getValue() != crc) break;
                    VoxelChunk chunk;
                    try { chunk = readPayload(payload); }
                    catch (IOException | IllegalArgumentException ex) { break; }
                    chunks.put(pack(chunk.chunkX(), chunk.chunkZ()), chunk);
                    lastGood += 8L + size;
                }
            }
            if (Files.size(file) != lastGood) {
                try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(lastGood); // interrupted last write, never lose earlier records
                }
            }
            output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(file, StandardOpenOption.APPEND), 65536));
        }

        void add(VoxelChunk chunk) throws IOException {
            byte[] payload = payload(chunk);
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            output.writeInt(payload.length);
            output.writeInt((int)checksum.getValue());
            output.write(payload);
            if (++unflushed >= 16) flush();
        }

        void flush() throws IOException {
            if (output != null) output.flush();
            unflushed = 0;
        }

        void close() {
            try {
                if (output != null) output.close();
                output = null;
                long unique = Math.max(8L, chunks.size() * 1024L);
                if (Files.size(file) > Math.max(unique * 3, unique + 8_388_608)) {
                    Path compact = file.resolveSibling(file.getFileName() + ".compact");
                    try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(
                            Files.newOutputStream(compact, StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING), 65536))) {
                        stream.writeInt(MAGIC);
                        stream.writeInt(VERSION);
                        for (VoxelChunk chunk : chunks.values()) {
                            byte[] data = payload(chunk);
                            CRC32 checksum = new CRC32();
                            checksum.update(data);
                            stream.writeInt(data.length);
                            stream.writeInt((int)checksum.getValue());
                            stream.write(data);
                        }
                    }
                    Files.move(compact, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                System.err.println("[SofronPack] Voxel cache close: " + ex.getMessage());
            }
        }
    }

    public static synchronized void open(Path save, String dimension) {
        String name = dimension.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path path = save.resolve("sofronpack-extended-world")
                .resolve(name + ".voxel.v2.bin").toAbsolutePath().normalize();
        Session old = current;
        if (old != null && old.file.equals(path)) return;
        close();
        Session next = new Session(path);
        try {
            next.load();
            current = next;
            generation++;
            System.out.println("[SofronPack] Voxel LOD: restored " + next.chunks.size() + " chunks");
        } catch (IOException exception) {
            next.close();
            System.err.println("[SofronPack] Voxel LOD cache: " + exception.getMessage());
        }
    }

    public static synchronized void close() {
        Session old = current;
        current = null;
        if (old != null) old.close();
        generation++;
    }

    public static synchronized void flush() {
        Session session = current;
        if (session != null) {
            try { session.flush(); }
            catch (IOException ex) {
                System.err.println("[SofronPack] Voxel LOD flush: " + ex.getMessage());
            }
        }
    }

    public static long generation() { return generation; }
    public static int count() { Session s = current; return s == null ? 0 : s.chunks.size(); }
    public static boolean has(int x, int z) {
        Session s = current;
        return s != null && s.chunks.containsKey(pack(x, z));
    }
    public static VoxelChunk get(int x, int z) {
        Session s = current;
        return s == null ? null : s.chunks.get(pack(x, z));
    }
    public static Long pollChanged() {
        Session s = current;
        return s == null ? null : s.changed.poll();
    }
    public static synchronized boolean put(VoxelChunk voxel) {
        Session s = current;
        if (s == null) return false;
        long id = pack(voxel.chunkX(), voxel.chunkZ());
        VoxelChunk old = s.chunks.get(id);
        if (voxel.same(old)) return false;
        try {
            s.add(voxel);
            s.chunks.put(id, voxel);
            s.changed.add(id);
            return true;
        } catch (IOException ex) {
            System.err.println("[SofronPack] Voxel LOD write: " + ex.getMessage());
            return false;
        }
    }

    public static VoxelChunk sample(ServerLevel level, LevelChunk chunk) {
        int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z();
        int minimum = level.getMinY();
        int count = chunk.getSections().length * 16 / CELL;
        short[] cells = new short[GRID * GRID * count];
        Map<String, Integer> mapping = new HashMap<>();
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        mapping.put("minecraft:air", 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int yi = 0; yi < count; yi++) {
            int y = minimum + yi * CELL;
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) {
                    pos.set(chunkX * 16 + x * CELL + 1, y + 1, chunkZ * 16 + z * CELL + 1);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) {
                        pos.set(chunkX * 16 + x * CELL, y, chunkZ * 16 + z * CELL);
                        state = chunk.getBlockState(pos);
                    }
                    int material = 0;
                    if (!state.isAir() && state.getRenderShape() != RenderShape.INVISIBLE) {
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        Integer mapped = mapping.get(id);
                        if (mapped == null) {
                            mapped = palette.size();
                            mapping.put(id, mapped);
                            palette.add(id);
                        }
                        material = mapped;
                    }
                    cells[index++] = (short) material;
                }
            }
        }
        return encode(chunkX, chunkZ, minimum, count, palette.toArray(String[]::new), cells);
    }

    public static VoxelChunk encode(int x, int z, int minY, int yCount,
                                    String[] palette, short[] raw) {
        if (raw.length != GRID * GRID * yCount) throw new IllegalArgumentException("Voxel volume");
        byte[] bytes = new byte[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            bytes[2 * i] = (byte)raw[i];
            bytes[2 * i + 1] = (byte)(raw[i] >>> 8);
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(bytes);
        deflater.finish();
        byte[] compressed;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                if (n <= 0) throw new IllegalStateException("Zlib made no progress");
                output.write(buffer, 0, n);
            }
            compressed = output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        } finally { deflater.end(); }
        return new VoxelChunk(x, z, minY, yCount, palette, compressed);
    }

    static byte[] payload(VoxelChunk chunk) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(chunk.chunkX());
            out.writeInt(chunk.chunkZ());
            out.writeInt(chunk.minY());
            out.writeShort(chunk.yCount());
            out.writeShort(chunk.palette().length);
            for (String identifier : chunk.palette()) out.writeUTF(identifier);
            out.writeInt(chunk.deflated().length);
            out.write(chunk.deflated());
            out.flush();
            if (bytes.size() > MAX_RECORD) throw new IOException("Oversized voxel record");
            return bytes.toByteArray();
        }
    }

    static VoxelChunk readPayload(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int x = in.readInt(), z = in.readInt(), y = in.readInt();
            int count = in.readUnsignedShort(), paletteSize = in.readUnsignedShort();
            if (count < 1 || count > 1024 || paletteSize < 1 || paletteSize > 32767)
                throw new IOException("Invalid cache record");
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) palette[i] = in.readUTF();
            int bytes = in.readInt();
            if (bytes < 0 || bytes > MAX_RECORD || bytes > in.available()) throw new IOException("Invalid compressed length");
            return new VoxelChunk(x, z, y, count, palette, in.readNBytes(bytes));
        }
    }
    public static long pack(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
}
