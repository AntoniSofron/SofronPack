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
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "SofronPack-LOD-Encode-" + ENCODER_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final TicketType GENERATION_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.parse("sofronpack:distant_generation"), new TicketType(0L, TicketType.FLAG_LOADING));
    private static final LinkedHashSet<Long> recentlyLoaded = new LinkedHashSet<>();
    private static long epoch;
    private static int ticks, cursor, nearbyCursor, stageIndex;
    private static int pending, nearbyPending;
    // Prevent duplicate FULL requests while an older snapshot is being
    // generated or compressed. All accesses occur on the server thread.
    private static final Set<Long> IN_FLIGHT = new HashSet<>();
    private static int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    // Anchor of the current staged scan, NOT the most recent player chunk.
    // Continuous flight must eventually restart the local 8-chunk priority.
    private static int scanAnchorX = Integer.MIN_VALUE, scanAnchorZ = Integer.MIN_VALUE;
    private static ServerLevel lastLevel;

    private ExtendedWorldPrefetch() {}

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (level == lastLevel && ExtendedWorldConfig.get().enabled) {
                if (recentlyLoaded.size() >= 2048) recentlyLoaded.removeFirst();
                recentlyLoaded.add(pack(chunk.getPos().x(), chunk.getPos().z()));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(ExtendedWorldPrefetch::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExtendedVoxelStore.close();
            pending = nearbyPending = 0;
            lastLevel = null;
            cursor = nearbyCursor = ticks = stageIndex = 0;
            IN_FLIGHT.clear();
            recentlyLoaded.clear();
            epoch++;
            centerX = centerZ = scanAnchorX = scanAnchorZ = Integer.MIN_VALUE;
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
            ExtendedVoxelStore.open(server.getWorldPath(LevelResource.ROOT), level.dimension().identifier().toString());
            lastLevel = level;
            centerX = centerZ = scanAnchorX = scanAnchorZ = Integer.MIN_VALUE;
            cursor = nearbyCursor = stageIndex = 0;
            pending = nearbyPending = 0;
            IN_FLIGHT.clear();
            recentlyLoaded.clear();
            epoch++;
        }

        int cx = player.chunkPosition().x(), cz = player.chunkPosition().z();
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
            refreshNearby(server, level, cx, cz, normal, busyServer ? 1 : 2);
        }

        if ((++ticks % 40) == 0) ExtendedVoxelStore.flush();
        // Prioritize response time rather than saturating the integrated server:
        // keeping too many FULL futures inflight can starve nearby rendering.
        // This is a bounded throughput improvement, not a 100x worldgen claim.
        // Throttle hard as soon as server tick duration becomes critical.
        if (busyServer && ticks % 4 != 0) return;
        int submitBudget = busyServer ? 1 : stageIndex == 0 ? 8 : stageIndex == 1 ? 6 : 4;
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
            IN_FLIGHT.add(key);
            pending++;
            submitted++;
            var loaded = level.getChunkSource().getChunkNow(x, z);
            if (loaded != null) {
                try {
                    encodeAndPublish(server, level, loaded, key);
                } catch (RuntimeException failure) {
                    finishRequest(level, key);
                    System.err.println("[SofronPack] Distant chunk capture: " + failure.getMessage());
                }
                continue;
            }
            storage.addTicket(new Ticket(GENERATION_TICKET, ticketLevel), new ChunkPos(x, z));
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
                            encodeAndPublish(server, level, chunk, key);
                            queued[0] = true;
                        });
                    } finally {
                        storage.removeTicket(new Ticket(GENERATION_TICKET, ticketLevel), pos);
                        if (requestEpoch == epoch && !queued[0]) finishRequest(level, key);
                    }
                }));
            } catch (RuntimeException failure) {
                storage.removeTicket(new Ticket(GENERATION_TICKET, ticketLevel), pos);
                finishRequest(level, key);
                System.err.println("[SofronPack] Distant chunk generation: " + failure.getMessage());
            }
        }
    }

    private static void finishRequest(ServerLevel expected, long key) {
        if (expected != lastLevel) return;
        IN_FLIGHT.remove(key);
        pending = Math.max(0, pending - 1);
    }

    private static void encodeAndPublish(MinecraftServer server, ServerLevel level,
                                         net.minecraft.world.level.chunk.ChunkAccess chunk, long key) {
        long requestEpoch = epoch;
        var captured = ExtendedVoxelStore.captureSections(level, chunk);
        CompletableFuture.supplyAsync(captured, ENCODERS)
                .whenComplete((encoded, error) -> server.execute(() -> {
                    try {
                        if (requestEpoch != epoch) return;
                        if (error != null) {
                            System.err.println("[SofronPack] Distant voxel encode: " + error.getMessage());
                        } else if (encoded != null) {
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
        for (int i = 0; i < budget && nearbyPending < 4; i++) {
            int x, z;
            if (!recentlyLoaded.isEmpty()) {
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
                var captured = ExtendedVoxelStore.captureSections(level, chunk);
                CompletableFuture.supplyAsync(captured, ENCODERS)
                        .whenComplete((encoded, failure) -> server.execute(() -> {
                            try {
                                if (requestEpoch != epoch) return;
                                if (failure != null) {
                                    System.err.println("[SofronPack] Nearby voxel encode: "
                                            + failure.getMessage());
                                } else if (encoded != null) {
                                    ExtendedVoxelStore.put(encoded);
                                }
                            } finally {
                                if (requestEpoch == epoch) {
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
