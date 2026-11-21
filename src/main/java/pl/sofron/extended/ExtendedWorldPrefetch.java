package pl.sofron.extended;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import pl.sofron.extended.mixin.ExtendedChunkFutureAccess;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Incremental single-player pregeneration. No additional simulation tickets
 * are held after a chunk is generated, and Minecraft's ordinary view/simulation
 * distances remain unchanged. Only one async request may be in flight.
 */
public final class ExtendedWorldPrefetch {
    private static final int RADIUS = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final long[] OFFSETS = spiralOffsets(RADIUS);
    private static final Map<String, Progress> PROGRESS = new HashMap<>();
    private static int tick;
    private static boolean pending;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE, cursor;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PROGRESS.clear();
            pending = false;
            cursor = tick = 0;
            centerX = centerZ = Integer.MIN_VALUE;
        });
    }

    private static void tick(MinecraftServer server) {
        // Do not schedule unauthorized generation on external multiplayer servers.
        if (!server.isSingleplayer() || !ExtendedWorldConfig.get().enabled ||
                !ExtendedWorldVoxyBridge.ready() || pending ||
                (++tick % 4) != 0) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        if (level == null) return;

        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        if (centerX != cx || centerZ != cz) {
            centerX = cx;
            centerZ = cz;
            cursor = 0;
        }

        String dimension = level.dimension().identifier().toString();
        Progress progress = PROGRESS.computeIfAbsent(dimension,
                key -> Progress.load(server, dimension));
        // Only generate beyond the normal view distance; its chunks are
        // simulated/rendered by vanilla, never by this prefetch worker.
        int near = Math.max(2, server.getPlayerList().getViewDistance());

        int inspected = 0;
        while (inspected++ < 512) {
            if (cursor >= OFFSETS.length) cursor = 0;
            long offset = OFFSETS[cursor++];
            int dx = (int)(offset >> 32), dz = (int)offset;
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= near) continue;
            int x = cx + dx, z = cz + dz;
            long packed = pack(x, z);
            if (progress.visited.contains(packed)) continue;

            pending = true;
            var request = ((ExtendedChunkFutureAccess)level.getChunkSource())
                    .sofron$getChunkFuture(x, z, ChunkStatus.FULL, true);
            request.whenComplete((result, error) -> server.execute(() -> {
                try {
                    if (error != null || result == null) return;
                    result.ifSuccess(chunk -> {
                        if (chunk instanceof LevelChunk full &&
                                ExtendedWorldVoxyBridge.submitGeneratedChunk(full)) {
                            progress.record(packed);
                        }
                    });
                } finally {
                    pending = false;
                }
            }));
            return;
        }
    }

    static long pack(int x, int z) {
        return ((long)x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Center-outwards Chebyshev rings, matching Minecraft's chunk distance. */
    static long[] spiralOffsets(int radius) {
        if (radius < 0 || radius > 256) throw new IllegalArgumentException("radius");
        int side = 2 * radius + 1;
        long[] offsets = new long[side * side];
        int i = 0;
        offsets[i++] = pack(0, 0);
        for (int r = 1; r <= radius; r++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.abs(x) == r || Math.abs(z) == r) offsets[i++] = pack(x, z);
                }
            }
        }
        if (i != offsets.length) throw new IllegalStateException("Incorrect ring traversal");
        return offsets;
    }

    private static final class Progress {
        private final Path file;
        private final LongOpenHashSet visited = new LongOpenHashSet();

        private Progress(Path file) { this.file = file; }

        static Progress load(MinecraftServer server, String dimension) {
            String safe = dimension.replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path file = server.getWorldPath(LevelResource.ROOT)
                    .resolve("sofronpack-extended-world").resolve(safe + ".bin");
            Progress progress = new Progress(file);
            if (!Files.isRegularFile(file)) return progress;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                while (true) {
                    progress.visited.add(in.readLong());
                }
            } catch (EOFException end) {
                // Expected end of append-only completed-chunk log.
            } catch (IOException problem) {
                System.err.println("[SofronPack] Distant chunk cache read error: " + problem.getMessage());
            }
            return progress;
        }

        void record(long location) {
            if (!visited.add(location)) return;
            try {
                Files.createDirectories(file.getParent());
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                    out.writeLong(location);
                }
            } catch (IOException problem) {
                System.err.println("[SofronPack] Distant chunk cache write error: " + problem.getMessage());
            }
        }
    }
}
