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
import pl.sofron.optimization.scheduler.SofronChunkWorkBudget;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.ArrayDeque;
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
    private static final int MAX_PENDING = Math.clamp(Runtime.getRuntime().availableProcessors() * 2, 8, 32);
    private static final AtomicInteger ENCODER_THREADS = new AtomicInteger();
    // SofronPack adaptive cost model. World access still
    // occurs exclusively on the integrated server thread.
    private static SofronChunkWorkBudget captureCost = new SofronChunkWorkBudget(250_000);
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "SofronPack-LOD-Encode-" + ENCODER_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static TicketType generationTicket;
    private static final LinkedHashSet<Long> recentlyLoaded = new LinkedHashSet<>();
    // Dirty live chunks take priority over background regeneration. An unload can
    // commit the last edit while an older compression task is still in flight.
    private static final Set<Long> finalOnUnload = new HashSet<>();
    private static final LinkedHashSet<Long> edited = new LinkedHashSet<>();
    private static long epoch;
    private static int ticks, cursor, nearbyCursor, stageIndex;
    private static int pending, nearbyPending;
    private static final int MAX_CAPTURES_PER_TICK = 2;
    private static final long CAPTURE_TICK_NS = 1_500_000L;
    private static int capturesThisTick;
    private static long captureTickStart;

    // Completed generation futures may arrive in a burst on the server
    // thread. Defer their immutable snapshots and process at most two per
    // tick rather than copying a large batch in one frame.
    private record ReadyCapture(ServerLevel level,
                                net.minecraft.world.level.chunk.ChunkAccess chunk,
                                long key, long epoch) {}
    private static final ArrayDeque<ReadyCapture> READY_CAPTURES = new ArrayDeque<>();

    private static boolean canCapture() {
        return capturesThisTick < MAX_CAPTURES_PER_TICK
                && (capturesThisTick == 0
                    || System.nanoTime() - captureTickStart < CAPTURE_TICK_NS);
    }

    private static void drainReadyCaptures(MinecraftServer server) {
        // Reserve capacity for nearby edits: distant generation futures
        // cannot monopolize both snapshot slots indefinitely.
        int processed = 0;
        while (processed < 1 && !READY_CAPTURES.isEmpty() && canCapture()) {
            ReadyCapture ready = READY_CAPTURES.removeFirst();
            if (ready.epoch() != epoch || ready.level() != lastLevel) continue;
            processed++;
            try {
                encodeAndPublish(server, ready.level(), ready.chunk(), ready.key());
            } catch (RuntimeException problem) {
                finishRequest(ready.level(), ready.key());
                System.err.println("[SofronPack] Deferred chunk capture: " + problem.getMessage());
            }
        }
    }
    // Prevent duplicate FULL requests while an older snapshot is being
    // generated or compressed. All accesses occur on the server thread.
    private static final Set<Long> IN_FLIGHT = new HashSet<>();
    // Unload compression must finish before a world archive is closed.
    private static final Set<CompletableFuture<?>> UNLOAD_TASKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void finishUnloadEncoding() {
        // The previous synchronous unload path also completed before world
        // shutdown. This preserves that durability without blocking ticks.
        while (!UNLOAD_TASKS.isEmpty()) {
            CompletableFuture<?>[] running =
                    UNLOAD_TASKS.toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(running).exceptionally(error -> null).join();
            UNLOAD_TASKS.removeIf(CompletableFuture::isDone);
        }
    }
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    // Anchor of the current staged scan, NOT the most recent player chunk.
    // Continuous flight must eventually restart the local 8-chunk priority.
    private static int scanAnchorX = Integer.MIN_VALUE, scanAnchorZ = Integer.MIN_VALUE;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void blockChanged(LevelChunk chunk) {
        if (chunk.getLevel() == lastLevel && ExtendedWorldConfig.get().enabled)
            edited.add(pack(chunk.getPos().x(), chunk.getPos().z()));
    }

    public static void register() {
        generationTicket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.parse("sofronpack:distant_generation"), new TicketType(0L, TicketType.FLAG_LOADING));
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (level == lastLevel && ExtendedWorldConfig.get().enabled) {
                if (recentlyLoaded.size() >= 2048) recentlyLoaded.removeFirst();
                recentlyLoaded.add(pack(chunk.getPos().x(), chunk.getPos().z()));
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level != lastLevel || !ExtendedWorldConfig.get().enabled) return;
            long key = pack(chunk.getPos().x(), chunk.getPos().z());
            boolean dirty = edited.remove(key);
            boolean awaitingCapture = recentlyLoaded.remove(key);
            if (!dirty && !awaitingCapture && !IN_FLIGHT.contains(key)
                    && ExtendedVoxelStore.has(chunk.getPos().x(), chunk.getPos().z())) return;
            // A complete compressed snapshot + disk write used to happen
            // inside the chunk-unload callback on the server thread. Capture
            // immutable section copies here, then compress and persist on the
            // bounded encoder workers without blocking the world tick.
            var captured = ExtendedVoxelStore.captureSections(level, chunk);
            if (IN_FLIGHT.contains(key)) finalOnUnload.add(key);
            long unloadEpoch = epoch;
            var task = CompletableFuture.runAsync(() -> {
                var voxel = captured.get();
                if (unloadEpoch == epoch && level == lastLevel)
                    ExtendedVoxelStore.put(voxel);
            }, ENCODERS);
            UNLOAD_TASKS.add(task);
            task.whenComplete((ignored, error) -> {
                UNLOAD_TASKS.remove(task);
                if (error != null)
                    System.err.println("[SofronPack] Background unload snapshot: "
                            + error.getMessage());
            });
        });
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedTerrainPreview.clear();
            finishUnloadEncoding();
            ExtendedLodMeshCache.awaitPendingSaves();
            ExtendedVoxelStore.close();
            pending = nearbyPending = 0;
            READY_CAPTURES.clear();
            lastLevel = null;
            cursor = nearbyCursor = ticks = stageIndex = 0;
            IN_FLIGHT.clear();
            recentlyLoaded.clear();
            edited.clear();
            finalOnUnload.clear();
            epoch++;
            captureCost = new SofronChunkWorkBudget(250_000);
            centerX = centerZ = scanAnchorX = scanAnchorZ = Integer.MIN_VALUE;
        });
    }

    private static void tick(MinecraftServer server) {
        long start = System.nanoTime();
        try {
            tickInternal(server);
        } finally {
            pl.sofron.optimization.SofronFrameTiming.reportIfSlow(
                    "LOD server tick", start, 20_000_000L);
        }
    }

    private static void tickInternal(MinecraftServer server) {
        // The integrated server shares the database with its own client.
        // Never generate chunks without authority on an external server.
        if (!server.isSingleplayer() || !ExtendedWorldConfig.get().enabled) {
            ExtendedTerrainPreview.clear();
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();

        if (level != lastLevel) {
            finishUnloadEncoding();
            ExtendedVoxelStore.open(server.getWorldPath(LevelResource.ROOT), level.dimension().identifier().toString());
            lastLevel = level;
            centerX = centerZ = scanAnchorX = scanAnchorZ = Integer.MIN_VALUE;
            cursor = nearbyCursor = stageIndex = 0;
            pending = nearbyPending = 0;
            READY_CAPTURES.clear();
            IN_FLIGHT.clear();
            recentlyLoaded.clear();
            edited.clear();
            finalOnUnload.clear();
            epoch++;
            captureCost = new SofronChunkWorkBudget(250_000);
        }

        captureTickStart = System.nanoTime();
        capturesThisTick = 0;
        drainReadyCaptures(server);
        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
        ExtendedTerrainPreview.move(level, cx, cz);
        if (cx != centerX || cz != centerZ) {
            // Recenter the ordered ring scheduler only after 8 chunks of
            // accumulated travel, not after each adjacent-chunk movement.
            // Otherwise sustained flight never generates distant bands.
            if (ExtendedLodStages.shouldRecenter(
                    cx, cz, scanAnchorX, scanAnchorZ)) {
                stageIndex = cursor = 0;
                scanAnchorX = cx;
                scanAnchorZ = cz;
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
            int nearLimit = busyServer ? 1 : captureCost.budget(4, 2_000_000L);
            refreshNearby(server, level, cx, cz, normal, nearLimit);
        }

        if ((++ticks % 40) == 0) ExtendedVoxelStore.flush();
        // Prioritize response time rather than saturating the integrated server:
        // keeping too many FULL futures inflight can starve nearby rendering.
        // This is a bounded throughput improvement, not a 100x worldgen claim.
        // Throttle hard as soon as server tick duration becomes critical.
        if (!ExtendedWorldConfig.get().pregenerateDistantChunks || pending >= MAX_PENDING
                || (busyServer && ticks % 4 != 0)) return;
        int submitBudget = busyServer ? 1 : stageIndex == 0 ? 8 : stageIndex == 1 ? 6 : 4;
        if (!busyServer) {
            submitBudget = Math.min(submitBudget, captureCost.budget(8, 3_000_000L));
        }
        long requestEpoch = epoch;
        long[] requests = new long[submitBudget];
        int submitted = 0, requestCount = 0, inspected = 0;
        var storage = level.getDataStorage().computeIfAbsent(TicketStorage.TYPE);
        int ticketLevel = ChunkLevel.byStatus(ChunkStatus.FEATURES);
        // Submit near to far without blocking all later rings on one slow future.
        while (submitted < submitBudget && pending < MAX_PENDING && inspected < 4096) {
            if (stageIndex >= ExtendedLodStages.count()) {
                // All seven bands have been processed. Do not spin through
                // the 128-chunk world every tick once the cache is complete.
                if (ticks % 20 != 0) break;
                stageIndex = cursor = 0;
            }

            int first = ExtendedLodStages.startInclusive(stageIndex);
            int last = ExtendedLodStages.endExclusive(stageIndex);
            if (cursor < first) cursor = first;
            if (cursor >= last) {
                stageIndex++;
                if (stageIndex < ExtendedLodStages.count())
                    cursor = ExtendedLodStages.startInclusive(stageIndex);
                continue;
            }

            long offset = OFFSETS[cursor++];
            inspected++;
            int dx = (int)(offset >> 32), dz = (int)offset;
            int x = scanAnchorX + dx, z = scanAnchorZ + dz;
            long key = pack(x, z);
            if (ExtendedVoxelStore.has(x, z) || IN_FLIGHT.contains(key)) continue;
            var loaded = level.getChunkSource().getChunkNow(x, z);
            if (loaded != null && !canCapture()) {
                cursor--; // Resume this already-loaded chunk next tick.
                break;
            }
            IN_FLIGHT.add(key);
            pending++;
            submitted++;
            if (loaded != null) {
                try {
                    encodeAndPublish(server, level, loaded, key);
                } catch (RuntimeException failure) {
                    finishRequest(level, key);
                    System.err.println("[SofronPack] Distant chunk capture: " + failure.getMessage());
                }
                continue;
            }
            storage.addTicket(new Ticket(generationTicket, ticketLevel), new ChunkPos(x, z));
            requests[requestCount++] = key;
        }
        // Submit the batch after installing all tickets. Vanilla's temporary
        // UNKNOWN ticket may expire after one tick, cancelling unfinished work.
        for (int i = 0; i < requestCount; i++) {
            long key = requests[i];
            int x = (int)(key >> 32), z = (int)key;
            ChunkPos pos = new ChunkPos(x, z);
            try {
                var future = ((ExtendedChunkFutureAccess)level.getChunkSource())
                        .sofron$getChunkFuture(x, z, ChunkStatus.FEATURES, true);
                future.whenComplete((result, error) -> server.execute(() -> {
                    boolean[] queued = {false};
                    try {
                        if (requestEpoch != epoch || error != null || result == null) return;
                        result.ifSuccess(chunk -> {
                            READY_CAPTURES.addLast(new ReadyCapture(level, chunk, key, requestEpoch));
                            queued[0] = true;
                        });
                    } finally {
                        storage.removeTicket(new Ticket(generationTicket, ticketLevel), pos);
                        if (requestEpoch == epoch && !queued[0]) finishRequest(level, key);
                    }
                }));
            } catch (RuntimeException failure) {
                storage.removeTicket(new Ticket(generationTicket, ticketLevel), pos);
                finishRequest(level, key);
                System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
            }
        }
    }

    private static void finishRequest(ServerLevel expected, long key) {
        if (expected != lastLevel) return;
        IN_FLIGHT.remove(key);
        finalOnUnload.remove(key);
        pending = Math.max(0, pending - 1);
    }

    private static void encodeAndPublish(MinecraftServer server, ServerLevel level,
                                         net.minecraft.world.level.chunk.ChunkAccess chunk, long key) {
        long requestEpoch = epoch;
        var captured = captureTimed(level, chunk);
        CompletableFuture.supplyAsync(captured, ENCODERS)
                .whenComplete((encoded, error) -> server.execute(() -> {
                    try {
                        if (requestEpoch != epoch) return;
                        if (error != null) {
                            System.err.println("[SofronPack] Distant voxel encode: " + error.getMessage());
                        } else if (encoded != null && !finalOnUnload.contains(key)) {
                            ExtendedVoxelStore.put(encoded);
                        }
                    } finally {
                        if (requestEpoch == epoch) finishRequest(level, key);
                    }
                }));
    }

    private static void refreshNearby(MinecraftServer server, ServerLevel level,
                                      int cx, int cz, int normal, int budget) {
        int size = normal * 2 + 1;
        int total = size * size;
        for (int i = 0; i < budget && nearbyPending < 4 && canCapture(); i++) {
            int x, z;
            if (!edited.isEmpty()) {
                long changed = edited.removeFirst();
                if (IN_FLIGHT.contains(changed)) { edited.add(changed); break; }
                x = (int)(changed >> 32);
                z = (int)changed;
            } else if (!recentlyLoaded.isEmpty()) {
                long loaded = recentlyLoaded.removeFirst();
                x = (int)(loaded >> 32);
                z = (int)loaded;
            } else {
                int slot = Math.floorMod(nearbyCursor++, total);
                x = cx + slot % size - normal;
                z = cz + slot / size - normal;
                if (ExtendedVoxelStore.has(x, z) && (nearbyCursor & 15) != 0) continue;
            }
            long key = pack(x, z);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null || !IN_FLIGHT.add(key)) continue;
            long requestEpoch = epoch;
            nearbyPending++;
            try {
                var captured = captureTimed(level, chunk);
                CompletableFuture.supplyAsync(captured, ENCODERS)
                        .whenComplete((encoded, failure) -> server.execute(() -> {
                            try {
                                if (requestEpoch != epoch) return;
                                if (failure != null) {
                                    System.err.println("[SofronPack] Nearby voxel encode: "
                                            + failure.getMessage());
                                } else if (encoded != null && !finalOnUnload.contains(key)) {
                                    ExtendedVoxelStore.put(encoded);
                                }
                            } finally {
                                if (requestEpoch == epoch) {
                                    finalOnUnload.remove(key);
                                    IN_FLIGHT.remove(key);
                                    nearbyPending = Math.max(0, nearbyPending - 1);
                                }
                            }
                        }));
            } catch (RuntimeException rejected) {
                IN_FLIGHT.remove(key);
                nearbyPending = Math.max(0, nearbyPending - 1);
                System.err.println("[SofronPack] Nearby voxel queue: " + rejected.getMessage());
            }
        }
    }

    /** Timing only; keep all block/biome reads on the owning server thread. */
    private static java.util.function.Supplier<ExtendedVoxelStore.VoxelChunk> captureTimed(
            ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        capturesThisTick++;
        long start = System.nanoTime();
        try {
            return ExtendedVoxelStore.captureSections(level, chunk);
        } finally {
            captureCost.sample(System.nanoTime() - start);
        }
    }

    static long pack(int x, int z) { return ExtendedVoxelStore.pack(x, z); }

    public static long[] spiralOffsets(int radius) {
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
