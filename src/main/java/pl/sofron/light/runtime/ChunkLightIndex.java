package pl.sofron.light.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import pl.sofron.light.config.LightConfig;
import pl.sofron.light.engine.BlockLightRules;
import pl.sofron.light.engine.LightSource;


public final class ChunkLightIndex {
    private record ChunkKey(int x, int z) {}
    private record Entry(List<LightSource> lights, long scannedTick) {}
    private final Map<ChunkKey, Entry> indexed = new HashMap<>();
    private final ChunkLightBuckets buckets = new ChunkLightBuckets();
    private Object lastLevel;
    private long ticks;
    private int previousX, previousZ, previousRadius;
    private long nextDiscovery;
    private final ArrayDeque<ChunkKey> pending = new ArrayDeque<>();
    private long revision;
    private record Emission(float radius, BlockLightRules.Rule rule) {}
    private static final Emission UNLIT = new Emission(0, null);
    private final Map<BlockState, Emission> emissions = new IdentityHashMap<>();
    private float configuredRadius;
    private boolean configuredColor;
    private long lastRuleRevision = Long.MIN_VALUE;
    public long revision() { return revision; }
    private static final int SCANS_PER_TICK = 3;
    private static final int REFRESH_TICKS = 600;
    private final Set<ChunkKey> dirty = new HashSet<>();
    public void invalidate(int cx, int cz) {
        ChunkKey key = new ChunkKey(cx, cz);
        // Unscanned columns are already handled by distance-ordered discovery.
        if (indexed.containsKey(key)) dirty.add(key);
    }
    public void clear() {
        if (lastLevel != null || !indexed.isEmpty()) revision++;
        emissions.clear(); pending.clear(); indexed.clear(); buckets.clear(); dirty.clear(); lastLevel = null; ticks = 0;
    }
    public int indexedChunks() { return indexed.size(); }
    public int indexedLights() { return buckets.sourceCount(); }

    public void tick(Minecraft client, LightConfig cfg) {
        if (client.level == null || client.player == null) { clear(); return; }
        if (lastLevel != client.level) { clear(); lastLevel = client.level; revision++; }
        ticks++;
        long currentRuleRevision = BlockLightRules.revision();
        boolean rulesChanged = lastRuleRevision != currentRuleRevision
                || configuredRadius != (float)cfg.dynamicLightRadius || configuredColor != cfg.coloredLighting;
        if (rulesChanged) {
            indexed.clear(); buckets.clear(); revision++;
            emissions.clear();
            configuredRadius = (float)cfg.dynamicLightRadius;
            configuredColor = cfg.coloredLighting;
            lastRuleRevision = currentRuleRevision;
        }
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        int radius = Math.max(1, client.options.renderDistance().get());
        boolean moved = cx != previousX || cz != previousZ || radius != previousRadius;
        if (ticks == 1 || rulesChanged || moved || ticks >= nextDiscovery) {
            previousX = cx;
            previousZ = cz;
            previousRadius = radius;
            nextDiscovery = ticks + 20;
            indexed.keySet().removeIf(key -> {
                if (Math.abs(key.x - cx) <= radius + 1 && Math.abs(key.z - cz) <= radius + 1
                        && client.level.hasChunk(key.x, key.z)) return false;
                Entry entry = indexed.get(key);
                if (!entry.lights.isEmpty()) {
                    buckets.remove(key.x, key.z);
                    revision++;
                }
                return true;
            });
            List<ChunkKey> candidates = new ArrayList<>();
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int x = cx - radius; x <= cx + radius; x++) {
                    if (!client.level.hasChunk(x,z)) continue;
                    ChunkKey key = new ChunkKey(x,z);
                    Entry old = indexed.get(key);
                    if (old == null || ticks - old.scannedTick >= REFRESH_TICKS) candidates.add(key);
                }
            }
            candidates.sort(Comparator
                .comparingLong((ChunkKey k) -> indexed.containsKey(k) ? indexed.get(k).scannedTick : -1L)
                .thenComparingInt(k -> Math.max(Math.abs(k.x-cx), Math.abs(k.z-cz))));
            pending.clear();
            pending.addAll(candidates);
        }
        if (dirty.isEmpty() && pending.isEmpty()) return;
        var changes = dirty.iterator();
        int scans = 0;
        while (scans < SCANS_PER_TICK && (changes.hasNext() || !pending.isEmpty())) {
            ChunkKey key;
            if (changes.hasNext()) {
                key = changes.next();
                changes.remove();
                if (Math.abs(key.x - cx) > radius || Math.abs(key.z - cz) > radius) continue;
            } else {
                key = pending.removeFirst();
                Entry old = indexed.get(key);
                if (old != null && ticks - old.scannedTick < REFRESH_TICKS) continue;
            }
            if (!client.level.hasChunk(key.x, key.z)) continue;
            scans++;
            List<LightSource> lights = scanChunk(client, cfg, key.x, key.z);
            Entry old = indexed.put(key, new Entry(lights, ticks));
            if (old == null ? !lights.isEmpty() : !old.lights().equals(lights)) {
                buckets.replace(key.x, key.z, lights);
                revision++;
            }
        }
    }

    public List<LightSource> allLights() { return buckets.allLights(); }

    public List<LightSource> lightsAffectingChunk(int chunkX, int chunkZ) {
        return buckets.affecting(chunkX, chunkZ);
    }

    private Emission emission(BlockState state) {
        Emission cached = emissions.get(state);
        if (cached != null) return cached;
        int vanilla = state.getLightEmission();
        Emission result = UNLIT;
        if (vanilla > 0) {
            var rule = BlockLightRules.rule(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if (rule != null && rule.intensity() > 0) {
                int level = rule.emission() == null ? vanilla : rule.emission();
                float radius = BlockLightRules.tintRadius(rule, configuredRadius, level);
                if (level > 0 && radius > 0) result = new Emission(radius, rule);
            }
        }
        emissions.put(state, result);
        return result;
    }

    private List<LightSource> scanChunk(Minecraft client, LightConfig cfg, int cx, int cz) {
        var chunk = client.level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null || !cfg.coloredLighting) return List.of();
        List<LightSource> found = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
            var section = chunk.getSection(sectionIndex);
            if (section.hasOnlyAir() || !section.maybeHas(state -> emission(state) != UNLIT)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int dy=0; dy<16; dy++) for (int dx=0; dx<16; dx++) for (int dz=0; dz<16; dz++) {
                var state = section.getBlockState(dx, dy, dz);
                if (state.getLightEmission() == 0) continue;
                Emission value = emission(state);
                if (value == UNLIT) continue;
                found.add(new LightSource((cx << 4) + dx + .5, baseY + dy + .5, (cz << 4) + dz + .5,
                        value.radius, value.rule.intensity(), value.rule.color()));
            }
        }
        return List.copyOf(found);
    }
}
