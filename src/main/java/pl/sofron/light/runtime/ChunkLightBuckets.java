package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import pl.sofron.light.engine.LightSource;

public final class ChunkLightBuckets {
    private StationaryOcclusion geometry = new StationaryOcclusion();
    public StationaryOcclusion geometry(){return geometry;}
    public int geometryReach(){return (int)Math.ceil(maxRadius/16.0)+1;}
    private final Map<Long, List<LightSource>> buckets = new HashMap<>();
    // Store the result for each queried target column, not just its emitters.
    // When a source changes, only its nearby columns need rebuilding.
    // Upper bound covers the largest supported 129x129 atlas. Without an LRU
    // limit, even empty visited chunks would accumulate for entire play sessions.
    private static final int MAX_CACHED_COLUMNS = 17000;
    private final Map<Long, List<LightSource>> affectingCache = new LinkedHashMap<>(
            512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<LightSource>> eldest) {
            return size() > MAX_CACHED_COLUMNS;
        }
    };
    private final TreeMap<Float, Integer> radii = new TreeMap<>();
    private int sourceCount;
    private float maxRadius;

    /** Copy only immutable source-list references. Worker owns its lookup cache. */
    public ChunkLightBuckets snapshot() {
        var copy = new ChunkLightBuckets();
        copy.buckets.putAll(buckets);
        copy.geometry=geometry.snapshot();
        copy.maxRadius = maxRadius;
        copy.sourceCount = sourceCount;
        return copy;
    }

    public void clear() {
        buckets.clear();
        geometry.clear();
        affectingCache.clear();
        radii.clear();
        sourceCount = 0;
        maxRadius = 0f;
    }

    public void replace(int cx, int cz, List<LightSource> sources) {
        long key = key(cx, cz);
        List<LightSource> next = sources.isEmpty() ? null : List.copyOf(sources);
        List<LightSource> old = next == null ? buckets.remove(key) : buckets.put(key, next);
        if (Objects.equals(old, next)) return;
        float oldRadius = largestRadius(old);
        float newRadius = largestRadius(next);
        if (oldRadius != newRadius) {
            if (oldRadius > 0) radii.compute(oldRadius, (r, n) -> n == 1 ? null : n - 1);
            if (newRadius > 0) radii.merge(newRadius, 1, Integer::sum);
            maxRadius = radii.isEmpty() ? 0 : radii.lastKey();
        }
        sourceCount += (next == null ? 0 : next.size()) - (old == null ? 0 : old.size());
        int reach = (int)Math.ceil(Math.max(oldRadius, newRadius) / 16.0);
        for (int x = cx - reach; x <= cx + reach; x++)
            for (int z = cz - reach; z <= cz + reach; z++)
                affectingCache.remove(key(x,z));
    }

    private static float largestRadius(List<LightSource> sources) {
        float radius = 0;
        if (sources != null)
            for (LightSource light : sources) radius = Math.max(radius, light.radius());
        return radius;
    }

    public void remove(int cx, int cz) {
        replace(cx, cz, List.of());
    }

    public int chunkCount() { return buckets.size(); }
    int cachedColumns() { return affectingCache.size(); }
    public int sourceCount() { return sourceCount; }
    public List<LightSource> sampleLights(int limit){
        var result=new ArrayList<LightSource>(Math.max(0,limit));
        for(var lights:buckets.values())for(var light:lights){
            if(result.size()>=limit)return result;
            result.add(light);
        }
        return result;
    }

    public List<LightSource> allLights() {
        ArrayList<LightSource> result = new ArrayList<>();
        buckets.values().forEach(result::addAll);
        return result;
    }

    public List<LightSource> affecting(int cx, int cz) {
        long targetKey = key(cx,cz);
        List<LightSource> existing = affectingCache.get(targetKey);
        if (existing != null) return existing;

        ArrayList<LightSource> found = new ArrayList<>();
        int reach = (int)Math.ceil(maxRadius / 16.0);
        double minX = cx * 16.0, maxX = minX + 16.0;
        double minZ = cz * 16.0, maxZ = minZ + 16.0;
        for (int x = cx-reach; x <= cx+reach; x++) {
            for (int z = cz-reach; z <= cz+reach; z++) {
                List<LightSource> entries = buckets.get(key(x,z));
                if (entries == null) continue;
                for (LightSource light : entries) {
                    double nearX = Math.max(minX, Math.min(maxX, light.x()));
                    double nearZ = Math.max(minZ, Math.min(maxZ, light.z()));
                    double dx = nearX - light.x(), dz = nearZ - light.z();
                    if (dx * dx + dz * dz <= light.radius() * light.radius()) found.add(light);
                }
            }
        }
        List<LightSource> result = List.copyOf(found);
        affectingCache.put(targetKey, result);
        return result;
    }

    private static long key(int x, int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }
}
