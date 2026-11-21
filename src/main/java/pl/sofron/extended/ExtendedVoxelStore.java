package pl.sofron.extended;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
    public static final int CELL = 1;
    public static final int GRID = 16 / CELL;
    private static final int MAGIC = 0x53465634; // SFV4
    private static final int VERSION = 4;
    private static final int MAX_RECORD = 1_048_576;
    private static volatile Session current;
    private static volatile long generation;

    private ExtendedVoxelStore() {}

    /** A compressed immutable 16 x full-height x 16 material-palette grid. */
    public record VoxelChunk(int chunkX, int chunkZ, int minY, int yCount,
                             int grassTint, int foliageTint, int waterTint,
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
                int got = 0;
                while (got < expected && !inflater.finished()) {
                    int count = inflater.inflate(bytes, got, expected - got);
                    if (count == 0) throw new IllegalArgumentException("Incomplete block LOD data");
                    got += count;
                }
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
                    && grassTint == other.grassTint && foliageTint == other.foliageTint
                    && waterTint == other.waterTint && Arrays.equals(palette, other.palette)
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
                .resolve(name + ".voxel.v4.bin").toAbsolutePath().normalize();
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

    /**
     * Immutable-by-ownership capture of a live chunk. Block states and biome
     * tints are read ONLY on the server thread; pure zlib compression of the
     * copied primitive volume can safely run on a bounded worker pool.
     */
    public record CapturedVoxel(int chunkX, int chunkZ, int minY, int yCount,
                                int grassTint, int foliageTint, int waterTint,
                                String[] palette, short[] cells) {
        public VoxelChunk encode() {
            return ExtendedVoxelStore.encode(chunkX, chunkZ, minY, yCount,
                    grassTint, foliageTint, waterTint, palette, cells);
        }
    }

    public static VoxelChunk sample(ServerLevel level, LevelChunk chunk) {
        return captureSections(level, chunk).get();
    }

    /** Copy compact section palettes on the server thread. The encoder owns every copy. */
    public static java.util.function.Supplier<VoxelChunk> captureSections(ServerLevel level, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x(), chunkZ = chunk.getPos().z(), minY = level.getMinY();
        var live = chunk.getSections();
        var sections = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++)
            if (!live[i].hasOnlyAir()) sections[i] = live[i].copy();
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 8, 8);
        var biome = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value();
        int grass = biome.getGrassColor(x, z), foliage = biome.getFoliageColor(), water = biome.getWaterColor();
        return () -> encodeSections(chunkX, chunkZ, minY, grass, foliage, water, sections);
    }

    private static VoxelChunk encodeSections(int chunkX, int chunkZ, int minimum,
            int grass, int foliage, int water, LevelChunkSection[] sections) {
        int count = sections.length * 16;
        short[] cells = new short[GRID * GRID * count];
        IdentityHashMap<BlockState, Short> mapping = new IdentityHashMap<>();
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        int index = 0;
        for (int yi = 0; yi < count; yi++) {
            // Read directly from the chunk's 16x16x16 palette instead of
            // repeating BlockPos -> world chunk/section lookups 100k times.
            var section = sections[(yi * CELL) / 16];
            if (section == null) {
                index += GRID * GRID;
                continue;
            }
            int localY = (yi * CELL) & 15;
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) {
                    // Store the actual Minecraft block at exact integer
                    // coordinates: no 2-block averaging, synthetic colours
                    // or diagonally interpolated heightfield.
                    BlockState state = section.getBlockState(x, localY, z);
                    Short material = mapping.get(state);
                    if (material == null) {
                        material = 0;
                        if (!state.isAir() && (state.getRenderShape() != RenderShape.INVISIBLE
                                || !state.getFluidState().isEmpty())) {
                            material = (short) palette.size();
                            palette.add(serializeState(state));
                        }
                        mapping.put(state, material);
                    }
                    cells[index++] = material;
                }
            }
        }
        return encode(chunkX, chunkZ, minimum, count, grass, foliage, water,
                palette.toArray(String[]::new), cells);
    }

    /**
     * The block registry name alone loses log axis, slabs, snow, foliage
     * persistence and all other block-state variants. Preserve serialized
     * properties so the distant texture lookup selects the correct baked
     * model instead of rendering every block with its default texture.
     */
    static String serializeState(BlockState state) {
        StringBuilder key = new StringBuilder(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        // Minecraft 26.3 exposes properties as a Stream<Property.Value<?>>.
        // valueName() uses the game's canonical encoding, including modded
        // enum values, so these survive reloads correctly.
        var values = state.getValues().toList();
        if (!values.isEmpty()) {
            key.append('[');
            for (int i = 0; i < values.size(); i++) {
                var value = values.get(i);
                if (i != 0) key.append(',');
                key.append(value.property().getName()).append('=')
                        .append(value.valueName());
            }
            key.append(']');
        }
        return key.toString();
    }

    public static VoxelChunk encode(int x, int z, int minY, int yCount,
                                    String[] palette, short[] raw) {
        return encode(x, z, minY, yCount, 0x7aaf57, 0x6ea951, 0x3f76e4,
                palette, raw);
    }

    public static VoxelChunk encode(int x, int z, int minY, int yCount,
                                    int grassTint, int foliageTint, int waterTint,
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
        return new VoxelChunk(x, z, minY, yCount, grassTint, foliageTint, waterTint, palette, compressed);
    }

    static byte[] payload(VoxelChunk chunk) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(chunk.chunkX());
            out.writeInt(chunk.chunkZ());
            out.writeInt(chunk.minY());
            out.writeShort(chunk.yCount());
            out.writeInt(chunk.grassTint());
            out.writeInt(chunk.foliageTint());
            out.writeInt(chunk.waterTint());
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
            int count = in.readUnsignedShort();
            int grassTint = in.readInt(), foliageTint = in.readInt(), waterTint = in.readInt();
            int paletteSize = in.readUnsignedShort();
            if (count < 1 || count > 1024 || paletteSize < 1 || paletteSize > 32767)
                throw new IOException("Invalid cache record");
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) palette[i] = in.readUTF();
            int bytes = in.readInt();
            if (bytes < 0 || bytes > MAX_RECORD || bytes > in.available()) throw new IOException("Invalid compressed length");
            return new VoxelChunk(x, z, y, count, grassTint, foliageTint, waterTint, palette, in.readNBytes(bytes));
        }
    }
    public static long pack(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
}
