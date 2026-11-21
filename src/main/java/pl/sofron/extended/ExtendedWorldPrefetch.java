package pl.sofron.extended;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import pl.sofron.extended.mixin.ExtendedChunkFutureAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only producer of previously unseen distant terrain. Minecraft generates
 * real chunks once through its own asynchronous pipeline; SofronPack stores a
 * compressed 3D block-material LOD snapshot and relinquishes the chunk immediately.
 */
public final class ExtendedWorldPrefetch {
    private static final int RADIUS = ExtendedWorldConfig.DISTANT_RADIUS_CHUNKS;
    private static final long[] OFFSETS = spiralOffsets(RADIUS);
    private static final int MAX_PENDING = 12;
    private static final AtomicInteger ENCODER_THREADS = new AtomicInteger();
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "SofronPack-LOD-Encode-" + ENCODER_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static int ticks, cursor, nearbyCursor;
    private static int pending, nearbyPending;
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedVoxelStore.close();
            pending = nearbyPending = 0;
            lastLevel = null;
            cursor = nearbyCursor = ticks = 0;
            centerX = centerZ = Integer.MIN_VALUE;
        });
    }

    private static void tick(MinecraftServer server) {
        // The integrated server shares the database with its own client.
        // Never generate chunks without authority on an external server.
        if (!server.isSingleplayer() || !ExtendedWorldConfig.get().enabled) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();

        if (level != lastLevel) {
            lastLevel = level;
            centerX = centerZ = Integer.MIN_VALUE;
            cursor = nearbyCursor = 0;
            pending = nearbyPending = 0;
        }
        ExtendedVoxelStore.open(server.getWorldPath(LevelResource.ROOT),
                level.dimension().identifier().toString());
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        if (cx != centerX || cz != centerZ) {
            // Do not restart the expensive 128-chunk scan every time the
            // player crosses a chunk boundary. Continuous flight otherwise
            // prevents the cursor from ever reaching the distant rings.
            if (centerX != Integer.MIN_VALUE &&
                    Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) > 24) {
                cursor = 0; // Actual teleport / dimension repositioning.
            }
            centerX = cx;
            centerZ = cz;
        }

        // Fast-fill real, ALREADY-LOADED near chunks. Before this change one
        // chunk every five ticks meant at most 4 snapshots/second. On an idle
        // server we now take two captures per tick (up to 40/second); actual
        // zlib encoding happens on bounded daemon workers.
        int normal = Math.max(2, server.getPlayerList().getViewDistance());
        boolean busyServer = server.getAverageTickTimeNanos() > 35_000_000L;
        if (!busyServer || ticks % 5 == 0) {
            refreshNearby(server, level, cx, cz, normal, busyServer ? 1 : 2);
        }

        if ((++ticks % 40) == 0) ExtendedVoxelStore.flush();
        // Prioritize response time rather than saturating the integrated server:
        // keeping too many FULL futures inflight can starve nearby rendering.
        // This is a bounded throughput improvement, not a 100x worldgen claim.
        // Throttle hard as soon as server tick duration becomes critical.
        if (busyServer && ticks % 4 != 0) return;
        int submitBudget = busyServer ? 1 : 4;
        for (int submitted = 0; submitted < submitBudget && pending < MAX_PENDING; ) {
            int inspected = 0;
            boolean launched = false;
            while (inspected++ < 2048) {
                if (cursor == OFFSETS.length) cursor = 0;
                long offset = OFFSETS[cursor++];
                int dx = (int)(offset >> 32), dz = (int)offset;
                if (Math.max(Math.abs(dx), Math.abs(dz)) <= normal) continue;
                int x = cx + dx, z = cz + dz;
                if (ExtendedVoxelStore.has(x, z)) continue;

                final ServerLevel expectedLevel = level;
                pending++;
                submitted++;
                launched = true;
                try {
                    var future = ((ExtendedChunkFutureAccess)level.getChunkSource())
                            .sofron$getChunkFuture(x, z, ChunkStatus.FULL, true);
                    future.whenComplete((result, error) -> server.execute(() -> {
                        // Keep the pending slot until the *entire* snapshot is
                        // compressed and published. This bounds memory and
                        // worker queue depth even during rapid camera motion.
                        final boolean[] encoding = {false};
                        try {
                            if (expectedLevel != lastLevel || error != null || result == null) return;
                            result.ifSuccess(chunk -> {
                                if (chunk instanceof LevelChunk full) {
                                    var captured = ExtendedVoxelStore.capture(expectedLevel, full);
                                    CompletableFuture.supplyAsync(captured::encode, ENCODERS)
                                            .whenComplete((encoded, failure) -> server.execute(() -> {
                                                try {
                                                    if (expectedLevel != lastLevel) return;
                                                    if (failure != null) {
                                                        System.err.println("[SofronPack] Distant voxel encode: "
                                                                + failure.getMessage());
                                                    } else if (encoded != null) {
                                                        ExtendedVoxelStore.put(encoded);
                                                    }
                                                } finally {
                                                    if (expectedLevel == lastLevel)
                                                        pending = Math.max(0, pending - 1);
                                                }
                                            }));
                                    encoding[0] = true;
                                }
                            });
                        } finally {
                            if (!encoding[0] && expectedLevel == lastLevel)
                                pending = Math.max(0, pending - 1);
                        }
                    }));
                } catch (RuntimeException failure) {
                    pending = Math.max(0, pending - 1);
                    System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
                }
                break;
            }
            if (!launched) break;
        }
    }

    private static void refreshNearby(MinecraftServer server, ServerLevel level,
                                      int cx, int cz, int normal, int budget) {
        int size = normal * 2 + 1;
        int total = size * size;
        for (int i = 0; i < budget && nearbyPending < 4; i++) {
            int slot = nearbyCursor++ % total;
            int dx = slot % size - normal, dz = slot / size - normal;
            int x = cx + dx, z = cz + dz;
            // Prioritise holes in the persistent cache; periodically refresh
            // known chunks to capture player edits without resampling the same
            // unchanged 3D volume on every pass of the nearby scanner.
            if (ExtendedVoxelStore.has(x, z) && (nearbyCursor & 15) != 0) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            var captured = ExtendedVoxelStore.capture(level, chunk);
            nearbyPending++;
            try {
                CompletableFuture.supplyAsync(captured::encode, ENCODERS)
                        .whenComplete((encoded, failure) -> server.execute(() -> {
                            try {
                                if (level != lastLevel) return;
                                if (failure != null) {
                                    System.err.println("[SofronPack] Nearby voxel encode: "
                                            + failure.getMessage());
                                } else if (encoded != null) {
                                    ExtendedVoxelStore.put(encoded);
                                }
                            } finally {
                                if (level == lastLevel)
                                    nearbyPending = Math.max(0, nearbyPending - 1);
                            }
                        }));
            } catch (RuntimeException rejected) {
                nearbyPending = Math.max(0, nearbyPending - 1);
                System.err.println("[SofronPack] Nearby voxel queue: " + rejected.getMessage());
            }
        }
    }

    static long pack(int x, int z) { return ExtendedVoxelStore.pack(x, z); }

    static long[] spiralOffsets(int radius) {
        if (radius < 0 || radius > 256) throw new IllegalArgumentException("radius");
        int side = radius * 2 + 1;
        long[] result = new long[side * side];
        int i = 0;
        result[i++] = pack(0, 0);
        for (int ring = 1; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        result[i++] = pack(dx, dz);
                    }
                }
            }
        }
        if (i != result.length) throw new IllegalStateException("Invalid LOD ring traversal");
        return result;
    }
}
