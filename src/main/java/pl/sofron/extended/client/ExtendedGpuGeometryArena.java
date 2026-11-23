package pl.sofron.extended.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import pl.sofron.extended.ExtendedPackedQuads;

/**
 * Paginated GPU vertex arena for SofronPack packed quad instances.
 *
 * Unlike one GpuBuffer per LOD tile, dozens/hundreds of tile allocations
 * share one physical buffer and old ranges are recycled after chunk edits.
 * Pages are allocated on demand, NOT preallocated up to 128-chunk coverage.
 * No hard cap on user render distance, visible tiles, or geometry size.
 *
 * Only call on the render thread, before a RenderPass opens.
 */
public final class ExtendedGpuGeometryArena implements AutoCloseable {
    private static final int PAGE_BYTES = 4 * 1024 * 1024;
    private final GpuDevice device;
    private final ArrayList<Page> pages = new ArrayList<>();
    private long liveBytes;
    private long residentBytes;

    private static int align(int size) {
        int stride = ExtendedPackedQuads.QUAD_BYTES;
        return Math.toIntExact(((long)size + stride - 1) / stride * stride);
    }

    private final class Page implements AutoCloseable {
        final GpuBuffer buffer;
        final ExtendedArenaRanges ranges;
        final int size;
        int allocations;

        Page(int requested) {
            size = align(Math.max(PAGE_BYTES, requested));
            buffer = device.createBuffer(() -> "SofronPack shared LOD packed-quad arena",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            ranges = new ExtendedArenaRanges(size);
            residentBytes += size;
        }

        @Override public void close() {
            buffer.close();
            residentBytes -= size;
        }
    }

    public final class Region implements AutoCloseable {
        private final Page page;
        private final int offset, size;
        private boolean released;

        private Region(Page page, int offset, int size) {
            this.page = page;
            this.offset = offset;
            this.size = size;
        }

        public int size() { return size; }

        public GpuBufferSlice slice(int bytes) {
            if (released || bytes < 0 || bytes > size)
                throw new IllegalStateException("Use of released or oversized GPU arena region");
            return page.buffer.slice(offset, bytes);
        }

        public void upload(ByteBuffer source) {
            int length = source.remaining();
            if (length == 0 || length > size)
                throw new IllegalArgumentException("Bad packed quad upload");
            var encoder = device.createCommandEncoder();
            encoder.writeToBuffer(slice(length), source);
            encoder.submit();
        }

        @Override public void close() {
            if (released) return;
            released = true;
            page.ranges.release(offset, size);
            page.allocations--;
            liveBytes -= size;
            // Keep one empty page to recycle future tiles, but return all
            // other unused GPU pages immediately to the driver.
            if (page.allocations == 0 && (pages.size() > 1 || page.size > align(PAGE_BYTES))) {
                pages.remove(page);
                page.close();
            }
        }
    }

    public ExtendedGpuGeometryArena(GpuDevice device) {
        this.device = device;
    }

    public Region allocate(int bytes) {
        if (bytes <= 0 || bytes % ExtendedPackedQuads.QUAD_BYTES != 0)
            throw new IllegalArgumentException("Bad packed instance allocation");
        for (Page page : pages) {
            int offset = page.ranges.allocate(bytes);
            if (offset >= 0) {
                page.allocations++;
                liveBytes += bytes;
                return new Region(page, offset, bytes);
            }
        }
        Page page = new Page(bytes);
        pages.add(page);
        int offset = page.ranges.allocate(bytes);
        if (offset < 0) throw new IllegalStateException("Empty GPU page could not allocate");
        page.allocations++;
        liveBytes += bytes;
        return new Region(page, offset, bytes);
    }

    public long liveBytes() { return liveBytes; }
    public long residentBytes() { return residentBytes; }
    public int pageCount() { return pages.size(); }

    @Override public void close() {
        for (Page page : pages) page.close();
        pages.clear();
        liveBytes = 0;
    }
}
