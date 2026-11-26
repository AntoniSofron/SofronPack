package pl.sofron.light.runtime;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;

/** Independently cached columns. Sparse sections keep exact sources, crowded sections
 * store the accumulated RGB field on a two-block lattice (nine nodes per axis). */
public final class ChunkLightAtlas {
    private static final int GRID = 9;
    private static final ByteBuffer EMPTY_BYTES = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asReadOnlyBuffer();
    private static final int MAX_WORDS = 16 * 1024 * 1024;
    private record Column(List<LightSource> lights, List<StationaryOcclusion.Column> geometry, int[] words) {}
    private final StationaryReach reach = new StationaryReach();
    // Placed light centers sit on half-block coordinates. For each configured radius
    // there are only a finite number of distinct squared distances to a grid node.
    private final Map<Float, float[]> falloffs = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Float, float[]> eldest) {
            return size() > 16;
        }
    };
    private final Map<Long,Column> cached = new HashMap<>();
    // Reuse off-heap staging memory. Repeated reallocations of multi-megabyte
    // direct buffers on every atlas revision trigger expensive native cleanup.
    private ByteBuffer staging;
    private final float[] bakeScratch = new float[GRID*GRID*GRID*5];
    private final RadialLightRuns runs = new RadialLightRuns();
    private final float[] groupWeights = new float[GRID*GRID*GRID];
    private final float[] groupEnergy = new float[GRID*GRID*GRID];
    public record Snapshot(ByteBuffer bytes, int chunkCount, int lightReferences) {}
    public void clear() {
        cached.clear();
        falloffs.clear();
        runs.clear();
        staging = null;
    }

    public Snapshot build(ChunkLightIndex index, int originX, int originZ, int width) {
        return build(index.snapshot(), originX, originZ, width);
    }

    public Snapshot build(ChunkLightBuckets index, int originX, int originZ, int width) {
        if (width < 1 || width > 129) throw new IllegalArgumentException("Invalid light atlas width");
        int cells = width*width;
        int size = 1+cells;
        int references = 0;
        Column[] columns = new Column[cells];
        cached.keySet().removeIf(key -> {
            int x = (int)(key >> 32)-originX, z = key.intValue()-originZ;
            return x < 0 || z < 0 || x >= width || z >= width;
        });
        for (int z=0; z<width; z++) for (int x=0; x<width; x++) {
            int cx = originX+x, cz = originZ+z;
            long key = ((long)cx << 32) | (cz & 0xffffffffL);
            List<LightSource> lights = index.affecting(cx,cz);
            if (lights.isEmpty()) { cached.remove(key); continue; }
            var geometry=index.geometry();
            var neighborhood=geometry.known()?geometry.neighborhood(cx,cz,index.geometryReach()):List.<StationaryOcclusion.Column>of();
            Column column = cached.get(key);
            if (column == null || !column.lights.equals(lights) || !column.geometry.equals(neighborhood)) {
                column = new Column(lights, neighborhood, encode(lights,cx*16,cz*16,geometry));
                cached.put(key,column);
            }
            columns[z*width+x] = column;
            references += lights.size();
            size += column.words.length;
            if (size > MAX_WORDS) throw new IllegalStateException("Light atlas exceeds 64 MiB");
        }
        int requiredBytes = Math.multiplyExact(size, Integer.BYTES);
        if (staging == null || staging.capacity() < requiredBytes) {
            int capacity = Math.max(4096, staging == null ? 0 : staging.capacity());
            while (capacity < requiredBytes) {
                // Grow geometrically to avoid repeated GPU-sized allocations
                // while colored light sources are discovered in loaded chunks.
                int grown = capacity + capacity / 2;
                capacity = Math.max(requiredBytes, grown);
            }
            staging = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        }
        staging.clear();
        staging.limit(requiredBytes);
        var words = staging.asIntBuffer();
        words.put(width);
        int next = 1+cells;
        for (Column column : columns) {
            words.put(column == null ? 0 : next);
            if (column != null) next += column.words.length;
        }
        for (Column column : columns) if (column != null) words.put(column.words);
        return new Snapshot(staging.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),cells,references);
    }

    private int[] encode(List<LightSource> lights, int x0, int z0, StationaryOcclusion geometry) {
        var sections = new TreeMap<Integer,List<LightSource>>();
        for (LightSource light : lights) {
            int low = (int)Math.floor((light.y()-light.radius())/16);
            int high = (int)Math.floor((light.y()+light.radius())/16);
            for (int y=low; y<=high; y++) sections.computeIfAbsent(y,k -> new ArrayList<>()).add(light);
        }
        int low = sections.firstKey(), height = sections.lastKey()-low+1;
        IntArrayList out = new IntArrayList();
        out.size(2+height);
        out.set(0,low);
        out.set(1,height);
        sections.forEach((section, local) -> {
            out.set(2+section-low,out.size());
            int y0 = section*16;
            if (geometry.known() || (local.size() > 12 && local.stream().allMatch(s -> s.radius() >= 2))) {
                out.add(-1);
                bake(out,local,x0,y0,z0,geometry);
            } else {
                out.add(local.size());
                for (LightSource s : local) {
                    put(out,s.x()-x0); put(out,s.y()-y0); put(out,s.z()-z0); put(out,s.radius());
                    put(out,s.color().red()); put(out,s.color().green()); put(out,s.color().blue()); put(out,s.power());
                }
            }
        });
        return out.toIntArray();
    }

    private void bake(IntArrayList out, List<LightSource> lights, int x0, int y0, int z0, StationaryOcclusion geometry) {
        float[] sums = bakeScratch;
        java.util.Arrays.fill(sums, 0f);
        // Group by exact hue; sum every source's original radial energy and
        // squared influence, then apply RGB once per grid node per hue.
        // No source is discarded or moved, and no brightness cap is introduced.
        var groups = new java.util.LinkedHashMap<RgbLight,List<LightSource>>();
        for (LightSource light : lights) groups.computeIfAbsent(light.color(), k -> new ArrayList<>()).add(light);
        for (var entry : groups.entrySet()) {
            RgbLight c = entry.getKey();
            float peak = Math.max(c.red(),Math.max(c.green(),c.blue()));
            if (peak <= 0) continue;
            java.util.Arrays.fill(groupWeights, 0f);
            java.util.Arrays.fill(groupEnergy, 0f);
            if (!runs.accumulate(entry.getValue(),x0,y0,z0,groupWeights,groupEnergy))
            for (LightSource s : entry.getValue()) {
            double x=s.x()-x0, y=s.y()-y0, z=s.z()-z0, radius=s.radius();
            if (s.power()<=0) continue;
            float[] falloff = null;
            if (radius <= 64 && x*2 == Math.rint(x*2) && y*2 == Math.rint(y*2) && z*2 == Math.rint(z*2)) {
                falloff = falloffs.computeIfAbsent(s.radius(), r -> {
                    double r2 = (double)r*r;
                    float[] table = new float[(int)Math.ceil(4*r2)];
                    for (int i=0; i<table.length; i++) table[i] = attenuation(i * 0.25, r);
                    return table;
                });
            }
            int xa=first(x,radius), xb=last(x,radius), ya=first(y,radius), yb=last(y,radius);
            for (int k=first(z,radius); k<=last(z,radius); k++) for (int j=ya; j<=yb; j++) for (int i=xa; i<=xb; i++) {
                double dx=2*i-x, dy=2*j-y, dz=2*k-z, sq=dx*dx+dy*dy+dz*dz;
                if (sq >= radius*radius) continue;
                float influence = s.power()*(falloff == null ? attenuation(sq, radius) : falloff[(int)(4*sq)]);
                float weight = influence*influence;
                int at=(k*GRID+j)*GRID+i;
                groupWeights[at]+=weight;
                groupEnergy[at]+=influence;
            }
            }
            boolean[] reachable=geometry.known()?reach.sample(entry.getValue(),x0,y0,z0,geometry,groupWeights):null;
            for (int node=0;node<groupWeights.length;node++) {
                if(reachable!=null&&!reachable[node])continue;
                float weight=groupWeights[node];
                if (weight==0f) continue;
                int at=5*node;
                sums[at]+=c.red()*weight; sums[at+1]+=c.green()*weight; sums[at+2]+=c.blue()*weight;
                sums[at+3]+=peak*weight; sums[at+4]+=peak*groupEnergy[node];
            }
        }
        for (int i=0; i<sums.length; i+=5) {
            float weight=sums[i+3];
            int rgb=0;
            if (weight>1e-5f) {
                for (int k=0;k<3;k++) rgb |= Math.min(255,Math.round(255*sums[i+k]/weight)) << (k*8);
            }
            out.add(rgb);
            put(out,weight>1e-5f ? sums[i+4] : 0);
        }
    }

    private static float attenuation(double squared, double radius) {
        return (float)Math.pow(1 - Math.sqrt(squared)/radius, 2.8);
    }

    private static int first(double center,double radius) { return Math.max(0,(int)Math.ceil((center-radius)/2)); }
    private static int last(double center,double radius) { return Math.min(GRID-1,(int)Math.floor((center+radius)/2)); }
    private static void put(IntArrayList out,double value) { out.add(Float.floatToRawIntBits((float)value)); }
    public static Snapshot empty() {
        return new Snapshot(EMPTY_BYTES.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),0,0);
    }
}
