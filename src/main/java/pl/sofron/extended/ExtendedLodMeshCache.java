package pl.sofron.extended;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
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
    private static final java.util.regex.Pattern TILE_NAME = java.util.regex.Pattern.compile("t\\.-?[0-9]+\\.-?[0-9]+\\.[0-9a-f]+\\.slm");
    private static final int MAGIC = 0x534C4D31; // SLM1
    private static final int VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final int SHUFFLE_QUADS = 1024;
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
    private static volatile DirectoryIndex previousIndex;

    private static final class DirectoryIndex {
        final Path root;
        final long generation = INDEX_COUNTER.incrementAndGet();
        // Mutation and pruning run under this index's lock on I/O workers.
        final LinkedHashMap<Path, Long> sizes = new LinkedHashMap<>();
        long bytes;
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
            DirectoryIndex previous = previousIndex;
            if (previous != null && previous.root.equals(root)) {
                previousIndex = active;
                currentIndex = previous;
                return previous;
            }
            DirectoryIndex fresh = new DirectoryIndex(root);
            previousIndex = active;
            currentIndex = fresh;
            INDEX_WORKER.execute(() -> scan(fresh));
            return fresh;
        }
    }

    private static void scan(DirectoryIndex index) {
        synchronized (index) {
            try {
                if (!Files.isDirectory(index.root, LinkOption.NOFOLLOW_LINKS)) return;
                index.available = true;
                record Entry(Path path, long bytes, long modified) {}
                var entries = new ArrayList<Entry>();
                try (Stream<Path> files = Files.walk(index.root, 2)) {
                    for (var it = files.iterator(); it.hasNext();) {
                        Path file = it.next();
                        if (!TILE_NAME.matcher(file.getFileName().toString()).matches()) continue;
                        var attr = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attr.isRegularFile()) entries.add(new Entry(file, attr.size(), attr.lastModifiedTime().toMillis()));
                    }
                }
                entries.sort(Comparator.comparingLong(Entry::modified));
                for (var entry : entries) remember(index, entry.path(), entry.bytes());
                prune(index);
            } catch (IOException | java.io.UncheckedIOException error) {
                warn("index", error);
            } finally {
                index.complete = true;
            }
        }
    }

    private static void remember(DirectoryIndex index, Path file, long bytes) {
        Long old = index.sizes.remove(file);
        index.bytes += bytes - (old == null ? 0 : old);
        index.sizes.put(file, bytes);
        index.paths.add(file);
    }

    private static void prune(DirectoryIndex index) throws IOException {
        var files = index.sizes.entrySet().iterator();
        while (index.bytes > 64L * 1024 * 1024 && files.hasNext()) {
            var oldest = files.next();
            Files.deleteIfExists(oldest.getKey());
            index.paths.remove(oldest.getKey());
            index.bytes -= oldest.getValue();
            files.remove();
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

    /**
     * Decode directly from a compressed file into a direct GPU-ready buffer.
     * Never allocate whole compressed AND raw copies in the Java heap.
     */
    public static Packed load(Path root, int tx, int tz, long detail, byte[] fingerprint) {
        if (root == null || fingerprint.length != HASH_BYTES) return null;
        Path file = fileFor(root, tx, tz, detail);
        if (!Files.isRegularFile(file)) return null;
        try {
            long length = Files.size(file);
            if (length < HEADER_BYTES || length > MAX_PACKED_BYTES + HEADER_BYTES) return null;
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file), 65536))) {
                int magic = in.readInt(), version = in.readInt();
                if (magic != MAGIC || (version != VERSION && version != LEGACY_VERSION)
                        || in.readInt() != tx || in.readInt() != tz
                        || in.readLong() != detail) return null;
                if (!Arrays.equals(fingerprint, in.readNBytes(HASH_BYTES))) return null;
                int quads = in.readInt(), rawBytes = in.readInt();
                int compressedBytes = in.readInt(), expectedCrc = in.readInt();
                if (quads <= 0 || rawBytes <= 0 || rawBytes > MAX_PACKED_BYTES
                        || rawBytes % ExtendedPackedQuads.QUAD_BYTES != 0
                        || (long) quads * ExtendedPackedQuads.QUAD_BYTES != rawBytes
                        || compressedBytes <= 0 || compressedBytes > MAX_PACKED_BYTES
                        || length != HEADER_BYTES + compressedBytes) return null;

                ByteBuffer result = ByteBuffer.allocateDirect(rawBytes).order(ByteOrder.nativeOrder());
                CRC32 checksum = new CRC32();
                byte[] staging = new byte[65536];
                Inflater inflater = new Inflater();
                try {
                    // All compressed bytes belong to this file, so the stream
                    // never reads data from the next LOD record.
                    java.util.zip.InflaterInputStream decoded =
                            new java.util.zip.InflaterInputStream(in, inflater, 65536);
                    int remaining = rawBytes;
                    byte[] original = version == VERSION ? new byte[SHUFFLE_QUADS * ExtendedPackedQuads.QUAD_BYTES] : null;
                    while (remaining > 0) {
                        int block = Math.min(remaining, version == VERSION
                                ? SHUFFLE_QUADS * ExtendedPackedQuads.QUAD_BYTES : staging.length);
                        int received = decoded.readNBytes(staging, 0, block);
                        if (received != block) return null;
                        if (version == VERSION) {
                            unshuffle(staging, original, block / ExtendedPackedQuads.QUAD_BYTES);
                            checksum.update(original, 0, block);
                            result.put(original, 0, block);
                        } else {
                            checksum.update(staging, 0, block);
                            result.put(staging, 0, block);
                        }
                        remaining -= block;
                    }
                    if (decoded.read() != -1 || !inflater.finished()
                            || inflater.getRemaining() != 0
                            || (int) checksum.getValue() != expectedCrc) return null;
                } catch (java.util.zip.ZipException invalid) {
                    return null;
                } finally {
                    inflater.end();
                }
                result.flip();
                return new Packed(result, quads, true);
            }
        } catch (IOException | IllegalArgumentException problem) {
            warn("read", problem);
            return null;
        }
    }

    /**
     * Persist compressed geometry with only two reusable 64 KiB staging
     * buffers, instead of two additional copies as large as the whole mesh.
     */
    public static void save(Path root, int tx, int tz, long detail, byte[] fingerprint, Packed packed) {
        if (root == null || fingerprint.length != HASH_BYTES) return;
        int size = packed.vertices().remaining();
        if (size <= 0 || size > MAX_PACKED_BYTES) return;
        Path target = fileFor(root, tx, tz, detail);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(temporary.toFile(), "rw")) {
                output.setLength(0);
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(tx);
                output.writeInt(tz);
                output.writeLong(detail);
                output.write(fingerprint);
                output.writeInt(packed.quads());
                output.writeInt(size);
                output.writeInt(0); // compressed length patched below
                output.writeInt(0); // CRC32 patched below
                long dataStart = output.getFilePointer();

                CRC32 checksum = new CRC32();
                Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
                byte[] input = new byte[SHUFFLE_QUADS * ExtendedPackedQuads.QUAD_BYTES];
                byte[] shuffled = new byte[input.length];
                try {
                    // Deflater may correctly consume input without producing
                    // bytes yet; let DeflaterOutputStream handle that state
                    // instead of treating a zero-length deflate as failure.
                    java.io.OutputStream sink = new java.io.OutputStream() {
                        @Override public void write(int b) throws IOException {
                            output.write(b);
                        }
                        @Override public void write(byte[] bytes, int off, int len) throws IOException {
                            output.write(bytes, off, len);
                        }
                        @Override public void close() {
                            // Keep RandomAccessFile open to patch CRC and size.
                        }
                    };
                    try (java.util.zip.DeflaterOutputStream compressor =
                                 new java.util.zip.DeflaterOutputStream(sink, deflater, 65536)) {
                        ByteBuffer vertices = packed.vertices().duplicate();
                        while (vertices.hasRemaining()) {
                            int count = Math.min(input.length, vertices.remaining());
                            vertices.get(input, 0, count);
                            checksum.update(input, 0, count);
                            shuffle(input, shuffled, count / ExtendedPackedQuads.QUAD_BYTES);
                            compressor.write(shuffled, 0, count);
                        }
                    }
                } finally {
                    deflater.end();
                }

                long compressedSize = output.getFilePointer() - dataStart;
                if (compressedSize <= 0 || compressedSize > MAX_PACKED_BYTES) return;
                output.seek(dataStart - 8);
                output.writeInt((int) compressedSize);
                output.writeInt((int) checksum.getValue());
            }
            DirectoryIndex found = index(root);
            synchronized (found) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                found.available = true;
                remember(found, target, Files.size(target));
                prune(found);
            }
        } catch (IOException problem) {
            warn("write", problem);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { }
        }
    }

    /** Byte-plane transpose exposes repeated UVs, colours, flags and high
     * coordinate bytes to zlib without changing a single GPU instance bit. */
    static void shuffle(byte[] input, byte[] output, int quads) {
        for (int b = 0; b < ExtendedPackedQuads.QUAD_BYTES; b++)
            for (int q = 0; q < quads; q++)
                output[b * quads + q] = input[q * ExtendedPackedQuads.QUAD_BYTES + b];
    }

    static void unshuffle(byte[] input, byte[] output, int quads) {
        for (int b = 0; b < ExtendedPackedQuads.QUAD_BYTES; b++)
            for (int q = 0; q < quads; q++)
                output[q * ExtendedPackedQuads.QUAD_BYTES + b] = input[b * quads + q];
    }

    // A small bounded save queue avoids retaining hundreds of large GPU
    // buffers in RAM. Under saturation the LOD meshing worker (never the
    // server or render thread) performs the write itself as backpressure.
    private static final java.util.concurrent.ThreadPoolExecutor SAVE_WORKER =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(4),
                    job -> {
                        Thread thread = new Thread(job, "SofronPack-LOD-Save");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    }, (task, executor) -> {
                        // Saturated cache I/O must backpressure the LOD
                        // meshing worker, never spawn concurrent writers
                        // targeting the same tile's temporary file.
                        try {
                            executor.getQueue().put(task);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            task.run(); // preserve the already-generated cache
                        }
                    });

    private static final java.util.concurrent.Phaser SAVES_IN_FLIGHT =
            new java.util.concurrent.Phaser(1);

    public static void saveDeferred(Path root, int tx, int tz, long detail,
                                    byte[] fingerprint, Packed packed) {
        if (root == null || packed.vertices().remaining() == 0) return;
        byte[] immutableFingerprint = fingerprint.clone();
        SAVES_IN_FLIGHT.register();
        SAVE_WORKER.execute(() -> {
            try {
                save(root, tx, tz, detail, immutableFingerprint, packed);
            } finally {
                SAVES_IN_FLIGHT.arriveAndDeregister();
            }
        });
    }

    /** Keep completed LODs durable when the integrated server closes. */
    public static void awaitPendingSaves() {
        int phase = SAVES_IN_FLIGHT.arrive();
        try {
            SAVES_IN_FLIGHT.awaitAdvanceInterruptibly(phase, 45,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("[SofronPack] LOD save interrupted during world shutdown");
        } catch (java.util.concurrent.TimeoutException timedOut) {
            System.err.println("[SofronPack] LOD save still finishing after shutdown timeout");
        }
    }

    private static void warn(String action, Exception problem) {
        if (warned.compareAndSet(false, true))
            System.err.println("[SofronPack] Persistent LOD mesh " + action
                    + " failed (will regenerate normally): " + problem.getMessage());
    }
}
