package pl.sofron.extended;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Durable per-world packed LOD geometry. Each generated four-by-four-chunk
 * tile AND each displayed level-map gets its own compressed disk entry.
 *
 * The voxel source remains the authority. A SHA-256 fingerprint of the
 * source chunks (including neighbours), atlas UVs and baked model faces
 * invalidates stale geometry after edits, re-generation or pack changes.
 *
 * No OpenGL/Vulkan objects are written: only portable 48-byte quad instances.
 * All I/O runs on the existing LOD meshing worker, never on the render thread.
 */
public final class ExtendedLodMeshCache {
    private static final int MAGIC = 0x534C4D31; // SLM1
    private static final int VERSION = 1;
    private static final int HASH_BYTES = 32;
    private static final int MAX_PACKED_BYTES = 96 * 1024 * 1024;
    private static final int HEADER_BYTES = 4 * 6 + 8 + HASH_BYTES + 4 * 2;
    private static final AtomicBoolean warned = new AtomicBoolean();
    // Render-thread filesystem probes caused visible frame-time spikes when
    // the world-save SSD was busy. Build the file index on an I/O worker.
    private static final ExecutorService INDEX_WORKER =
            Executors.newSingleThreadExecutor(job -> {
                Thread worker = new Thread(job, "SofronPack-LOD-Index");
                worker.setDaemon(true);
                worker.setPriority(Thread.MIN_PRIORITY);
                return worker;
            });
    private static final AtomicLong INDEX_COUNTER = new AtomicLong();
    private static volatile DirectoryIndex currentIndex;

    private static final class DirectoryIndex {
        final Path root;
        final long generation = INDEX_COUNTER.incrementAndGet();
        final Set<Path> paths = ConcurrentHashMap.newKeySet();
        volatile boolean available;
        volatile boolean complete;
        DirectoryIndex(Path root) { this.root = root; }
    }

    private static DirectoryIndex index(Path root) {
        if (root == null) return null;
        DirectoryIndex active = currentIndex;
        if (active != null && active.root.equals(root)) return active;
        synchronized (ExtendedLodMeshCache.class) {
            active = currentIndex;
            if (active != null && active.root.equals(root)) return active;
            DirectoryIndex fresh = new DirectoryIndex(root);
            currentIndex = fresh;
            INDEX_WORKER.execute(() -> scan(fresh));
            return fresh;
        }
    }

    private static void scan(DirectoryIndex index) {
        try {
            if (!Files.isDirectory(index.root)) return;
            index.available = true;
            // World root / r.X.Z / tile.slm. Only metadata is inspected.
            try (Stream<Path> files = Files.walk(index.root, 2)) {
                files.filter(path -> path.getFileName().toString().endsWith(".slm"))
                        .forEach(index.paths::add);
            }
        } catch (IOException error) {
            warn("index", error);
        } finally {
            index.complete = true;
        }
    }

    /** Changes once when the background directory scan finishes. */
    public static long indexGeneration(Path root) {
        DirectoryIndex found = index(root);
        return found != null && found.complete ? found.generation : Long.MIN_VALUE;
    }

    public record Packed(ByteBuffer vertices, int quads, boolean fromDisk) {
        public Packed(ByteBuffer vertices, int quads) {
            this(vertices, quads, false);
        }

        public Packed {
            if (quads < 0 || vertices.remaining() != (long) quads * ExtendedPackedQuads.QUAD_BYTES)
                throw new IllegalArgumentException("Invalid packed LOD geometry");
        }
    }

    private ExtendedLodMeshCache() {}

    private static Path fileFor(Path root, int tileX, int tileZ, long detail) {
        return root.resolve("r." + Math.floorDiv(tileX, 16) + "." + Math.floorDiv(tileZ, 16))
                .resolve("t." + tileX + "." + tileZ + "." + Long.toUnsignedString(detail, 16) + ".slm");
    }

    /** Lock-free, disk-I/O-free test on the render thread. */
    public static boolean hasSaved(Path root, int tx, int tz, long detail) {
        DirectoryIndex found = index(root);
        return found != null && found.paths.contains(fileFor(root, tx, tz, detail));
    }

    public static boolean exists(Path root) {
        DirectoryIndex found = index(root);
        return found != null && found.available;
    }

    public static Packed load(Path root, int tx, int tz, long detail, byte[] fingerprint) {
        if (root == null || fingerprint.length != HASH_BYTES) return null;
        Path file = fileFor(root, tx, tz, detail);
        if (!Files.isRegularFile(file)) return null;
        try {
            long length = Files.size(file);
            if (length < HEADER_BYTES || length > MAX_PACKED_BYTES + HEADER_BYTES) return null;
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file), 65536))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION
                        || in.readInt() != tx || in.readInt() != tz
                        || in.readLong() != detail) return null;
                byte[] storedHash = in.readNBytes(HASH_BYTES);
                if (!Arrays.equals(fingerprint, storedHash)) return null;
                int quads = in.readInt(), rawBytes = in.readInt();
                int compressedBytes = in.readInt(), crc = in.readInt();
                if (quads <= 0 || rawBytes <= 0 || rawBytes > MAX_PACKED_BYTES
                        || rawBytes % ExtendedPackedQuads.QUAD_BYTES != 0
                        || (long) quads * ExtendedPackedQuads.QUAD_BYTES != rawBytes
                        || compressedBytes <= 0 || compressedBytes > MAX_PACKED_BYTES
                        || length != HEADER_BYTES + compressedBytes) return null;
                byte[] encoded = in.readNBytes(compressedBytes);
                if (encoded.length != compressedBytes) return null;
                byte[] raw = new byte[rawBytes];
                Inflater inflater = new Inflater();
                int decoded = 0;
                try {
                    inflater.setInput(encoded);
                    while (decoded < rawBytes && !inflater.finished()) {
                        int n = inflater.inflate(raw, decoded, rawBytes - decoded);
                        if (n == 0) return null;
                        decoded += n;
                    }
                    if (decoded != rawBytes || !inflater.finished() || inflater.getRemaining() != 0)
                        return null;
                } catch (DataFormatException invalid) {
                    return null;
                } finally {
                    inflater.end();
                }
                CRC32 checksum = new CRC32();
                checksum.update(raw);
                if ((int) checksum.getValue() != crc) return null;
                ByteBuffer buffer = ByteBuffer.allocateDirect(rawBytes).order(ByteOrder.nativeOrder());
                buffer.put(raw).flip();
                return new Packed(buffer, quads, true);
            }
        } catch (IOException | IllegalArgumentException problem) {
            warn("read", problem);
            return null;
        }
    }

    public static void save(Path root, int tx, int tz, long detail, byte[] fingerprint, Packed packed) {
        if (root == null || fingerprint.length != HASH_BYTES) return;
        int size = packed.vertices().remaining();
        if (size <= 0 || size > MAX_PACKED_BYTES) return;
        Path target = fileFor(root, tx, tz, detail);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            byte[] raw = new byte[size];
            packed.vertices().duplicate().get(raw);
            CRC32 checksum = new CRC32();
            checksum.update(raw);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.min(size, 65536));
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try {
                try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed, deflater, 65536)) {
                    zlib.write(raw);
                }
            } finally {
                deflater.end();
            }
            if (compressed.size() > MAX_PACKED_BYTES) return;
            Files.createDirectories(target.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), 65536))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(tx);
                out.writeInt(tz);
                out.writeLong(detail);
                out.write(fingerprint);
                out.writeInt(packed.quads());
                out.writeInt(size);
                out.writeInt(compressed.size());
                out.writeInt((int) checksum.getValue());
                compressed.writeTo(out);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // Do not replace the current world's in-memory index if a
            // worker from an older world finishes its final disk write.
            DirectoryIndex found = currentIndex;
            if (found == null) found = index(root);
            if (found != null && found.root.equals(root)) {
                found.available = true;
                found.paths.add(target);
            }
        } catch (IOException problem) {
            warn("write", problem);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { }
        }
    }

    private static void warn(String action, Exception problem) {
        if (warned.compareAndSet(false, true))
            System.err.println("[SofronPack] Persistent LOD mesh " + action
                    + " failed (will regenerate normally): " + problem.getMessage());
    }
}
