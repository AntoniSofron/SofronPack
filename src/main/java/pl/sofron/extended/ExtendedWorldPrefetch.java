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

import java.util.HashSet;
import java.util.Set;
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
    private static int ticks, cursor, nearbyCursor, stageIndex;
    private static int pending, nearbyPending;
    // Prevent duplicate FULL requests while an older snapshot is being
    // generated or compressed. All accesses occur on the server thread.
    private static final Set<Long> IN_FLIGHT = new HashSet<>();
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedVoxelStore.close();
            pending = nearbyPending = 0;
            lastLevel = null;
            cursor = nearbyCursor = ticks = stageIndex = 0;
            IN_FLIGHT.clear();
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
            cursor = nearbyCursor = stageIndex = 0;
            pending = nearbyPending = 0;
            IN_FLIGHT.clear();
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
                stageIndex = cursor = 0; // Refill nearest 8-chunk stage first.
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
        int submitBudget = busyServer ? 1 : stageIndex == 0 ? 8 : stageIndex == 1 ? 6 : 4;
        int submitted = 0, inspected = 0;
        // Strict near-first stage boundaries. A later band cannot be queued
        // before the preceding band has finished persisting its snapshots.
        while (submitted < submitBudget && pending < MAX_PENDING && inspected < 4096) {
            if (stageIndex >= ExtendedLodStages.count()) {
                // Fully generated horizon: periodically revisit the nearest
                // region to fill new gaps from ordinary player movement.
                stageIndex = cursor = 0;
            }

            int first = ExtendedLodStages.startInclusive(stageIndex);
            int last = ExtendedLodStages.endExclusive(stageIndex);
            if (cursor < first) cursor = first;
            if (cursor >= last) {
                if (pending > 0) {
                    // Wait for this band to finish rather than dispatching the
                    // next one and starving the closest visible terrain.
                    cursor = first;
                    break;
                }
                int missing = firstMissing(level, cx, cz, first, last);
                if (missing >= 0) {
                    cursor = missing; // Retry failed/previously pending cells.
                    break;
                }
                stageIndex++;
                if (stageIndex < ExtendedLodStages.count())
                    cursor = ExtendedLodStages.startInclusive(stageIndex);
                continue;
            }

            long offset = OFFSETS[cursor++];
            inspected++;
            int dx = (int)(offset >> 32), dz = (int)offset;
            int x = cx + dx, z = cz + dz;
            long key = pack(x, z);
            if (ExtendedVoxelStore.has(x, z) || IN_FLIGHT.contains(key)) continue;
            IN_FLIGHT.add(key);
            pending++;
            submitted++;
            final ServerLevel expectedLevel = level;
            try {
                // Capture already-live terrain immediately, without asking
                // worldgen to build the same chunk twice.
                LevelChunk loaded = level.getChunkSource().getChunkNow(x, z);
                if (loaded != null) {
                    encodeAndPublish(server, expectedLevel, loaded, key);
                    continue;
                }
                var future = ((ExtendedChunkFutureAccess)level.getChunkSource())
                        .sofron$getChunkFuture(x, z, ChunkStatus.FULL, true);
                future.whenComplete((result, error) -> server.execute(() -> {
                    boolean queued = false;
                    try {
                        if (expectedLevel != lastLevel || error != null || result == null) return;
                        // Minecraft may return success or failure; queue only
                        // fully generated source chunks for async compression.
                        final boolean[] scheduled = {false};
                        result.ifSuccess(chunk -> {
                            if (chunk instanceof LevelChunk full) {
                                encodeAndPublish(server, expectedLevel, full, key);
                                scheduled[0] = true;
                            }
                        });
                        queued = scheduled[0];
                    } catch (RuntimeException failure) {
                        System.err.println("[SofronPack] Distant chunk capture: " + failure.getMessage());
                    } finally {
                        if (!queued) finishRequest(expectedLevel, key);
                    }
                }));
            } catch (RuntimeException failure) {
                finishRequest(expectedLevel, key);
                System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
            }
        }
    }

    private static int firstMissing(ServerLevel level, int cx, int cz, int begin, int end) {
        for (int i = begin; i < end; i++) {
            long offset = OFFSETS[i];
            int x = cx + (int)(offset >> 32);
            int z = cz + (int)offset;
            if (!ExtendedVoxelStore.has(x, z) && !IN_FLIGHT.contains(pack(x, z)))
                return i;
        }
        return -1;
    }

    private static void finishRequest(ServerLevel expected, long key) {
        if (expected != lastLevel) return;
        IN_FLIGHT.remove(key);
        pending = Math.max(0, pending - 1);
    }

    private static void encodeAndPublish(MinecraftServer server, ServerLevel level,
                                         LevelChunk chunk, long key) {
        var captured = ExtendedVoxelStore.capture(level, chunk);
        CompletableFuture.supplyAsync(captured::encode, ENCODERS)
                .whenComplete((encoded, error) -> server.execute(() -> {
                    try {
                        if (level != lastLevel) return;
                        if (error != null) {
                            System.err.println("[SofronPack] Distant voxel encode: " + error.getMessage());
                        } else if (encoded != null) {
                            ExtendedVoxelStore.put(encoded);
                        }
                    } finally {
                        finishRequest(level, key);
                    }
                }));
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
