package pl.sofron.light.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One in-flight build; worker exclusively owns the atlas cache and reads immutable source lists.
 * Published bytes are never mutated. Render thread never waits for the worker. */
public final class AsyncStationaryAtlas {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SofronPack-Stationary-RGB"); t.setDaemon(true); return t;
    });
    private static final int PAGE = 4096;
    public record Range(int offset, int length) {}
    public record Result(ChunkLightAtlas.Snapshot atlas, int x, int z, int width,
                         long revision, List<Range> ranges, boolean full) {}
    private ChunkLightAtlas workerAtlas = new ChunkLightAtlas();
    private CompletableFuture<Result> pending;
    private Result published;
    public boolean busy() { return pending != null; }
    public Result published() { return published; }
    public void reset() {
        // Old running work can finish, but cannot publish into another world.
        pending = null; published = null; workerAtlas = new ChunkLightAtlas();
    }
    public void submit(ChunkLightBuckets input, int x, int z, int width, long revision) {
        if (pending != null) return;
        ChunkLightAtlas atlas = workerAtlas;
        Result previous = published;
        pending = CompletableFuture.supplyAsync(() -> {
            ChunkLightAtlas.Snapshot temporary = atlas.build(input,x,z,width);
            ByteBuffer bytes = ByteBuffer.allocateDirect(temporary.bytes().remaining()).order(ByteOrder.nativeOrder());
            bytes.put(temporary.bytes().duplicate()).flip();
            var snapshot = new ChunkLightAtlas.Snapshot(bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),
                    temporary.chunkCount(), temporary.lightReferences());
            List<Range> ranges = previous == null ? List.of() : changes(previous.atlas.bytes(), snapshot.bytes());
            return new Result(snapshot,x,z,width,revision,ranges,previous == null || ranges.size() > 64);
        }, WORKER);
    }
    public Result poll() {
        if (pending == null || !pending.isDone()) return null;
        var completed = pending;
        pending = null;
        Result result = completed.join();
        published = result;
        return result;
    }
    /** Page-sized comparisons execute off the render thread. Coalesce adjacent pages. */
    public static List<Range> changes(ByteBuffer before, ByteBuffer after) {
        int length = after.remaining(), old = before.remaining();
        var ranges = new ArrayList<Range>();
        int start = -1;
        for (int at=0;at<length;at+=PAGE) {
            int n=Math.min(PAGE,length-at);
            boolean different = at+n>old || before.slice(before.position()+at,n).mismatch(after.slice(after.position()+at,n))>=0;
            if (different && start<0) start=at;
            if (!different && start>=0) { ranges.add(new Range(start,at-start)); start=-1; }
        }
        if (start>=0) ranges.add(new Range(start,length-start));
        return List.copyOf(ranges);
    }
}