package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pl.sofron.light.engine.LightSource;

public final class ChunkLightBuckets {
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
    private float maxRadius;

    public void clear() {
        buckets.clear();
        affectingCache.clear();
        maxRadius = 0f;
    }

    public void replace(int cx, int cz, List<LightSource> sources) {
        long key = key(cx, cz);
        List<LightSource> next = sources.isEmpty() ? null : List.copyOf(sources);
        List<LightSource> old = next == null ? buckets.remove(key) : buckets.put(key, next);
        if (Objects.equals(old, next)) return;
        updateRadiusAndInvalidate(cx, cz);
    }

    public void remove(int cx, int cz) {
        if (buckets.remove(key(cx, cz)) != null) updateRadiusAndInvalidate(cx, cz);
    }

    private void updateRadiusAndInvalidate(int cx, int cz) {
        float previousRadius = maxRadius;
        recomputeMaxRadius();
        if (Float.compare(previousRadius, maxRadius) != 0) {
            affectingCache.clear();
        } else {
            int reach = (int)Math.ceil(maxRadius / 16.0);
            for (int x = cx - reach; x <= cx + reach; x++)
                for (int z = cz - reach; z <= cz + reach; z++)
                    affectingCache.remove(key(x,z));
        }
    }

    public int chunkCount() { return buckets.size(); }
    public int sourceCount() { return buckets.values().stream().mapToInt(List::size).sum(); }
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

    private void recomputeMaxRadius() {
        maxRadius = 0f;
        for (List<LightSource> sources : buckets.values())
            for (LightSource light : sources) maxRadius = Math.max(maxRadius, light.radius());
    }

    private static long key(int x, int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }
}
