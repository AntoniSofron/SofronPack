package pl.sofron.light.render.gpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.nio.ByteBuffer;
import pl.sofron.light.runtime.ChunkLightAtlas;

public final class RenderPearlChunkAtlasBuffer implements AutoCloseable {
    private final GpuDevice device;
    private final String uniform;
    private GpuBuffer buffer;
    private int capacityBytes;
    private boolean ready;
    public RenderPearlChunkAtlasBuffer(GpuDevice device, String uniform) { this.device=device; this.uniform=uniform; }
    public void upload(ChunkLightAtlas.Snapshot atlas) {
        upload(atlas.bytes().asReadOnlyBuffer());
    }
    public void upload(ByteBuffer data) {
        int size=data.remaining();
        if (buffer == null || size > capacityBytes) {
            if (buffer != null) buffer.close();
            if (size <= 4 * 1024 * 1024) {
                // Chunk scanning grows the atlas incrementally. Allocate in
                // geometric steps instead of recreating GPU storage each tick.
                int next = Math.max(256, capacityBytes);
                while (next < size) next = Math.max(size, next + next / 2);
                capacityBytes = next;
            } else {
                // Large worlds should not retain excessive unused VRAM.
                capacityBytes = size;
            }
            buffer=device.createBuffer(() -> uniform,
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, capacityBytes);
        }
        CommandEncoder encoder=device.createCommandEncoder();
        encoder.writeToBuffer(buffer.slice(0, size), data);
        encoder.submit();
        ready=true;
    }
    /** Apply worker-computed dirty ranges in one command submission. A reallocation
     * needs the complete image; otherwise existing GPU bytes remain valid. */
    public void uploadDelta(pl.sofron.light.runtime.AsyncStationaryAtlas.Result update) {
        ByteBuffer data = update.atlas().bytes().duplicate();
        int size = data.remaining();
        if (update.full() || buffer == null || size > capacityBytes) { upload(data); return; }
        if (update.ranges().isEmpty()) return;
        long changed=0;
        for (var range : update.ranges()) changed+=range.length();
        if (changed*2 >= size) { upload(data); return; }
        CommandEncoder encoder=device.createCommandEncoder();
        for (var range : update.ranges()) {
            encoder.writeToBuffer(buffer.slice(range.offset(),range.length()),
                    data.slice(data.position()+range.offset(),range.length()));
        }
        encoder.submit();
        ready=true;
    }
    public void bind(RenderPass pass) {
        if (ready && buffer != null) pass.setUniform(uniform, buffer);
    }
    @Override public void close() {
        ready=false;
        if(buffer!=null) buffer.close();
        buffer=null;
    }
}
