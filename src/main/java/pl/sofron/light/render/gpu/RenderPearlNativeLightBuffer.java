package pl.sofron.light.render.gpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class RenderPearlNativeLightBuffer implements AutoCloseable {
    public static final String UNIFORM = "SofronLights";
    private final GpuDevice device;
    private GpuBuffer buffer;
    private boolean closed;
    private long uploads;
    private final ByteBuffer submitted = ByteBuffer.allocateDirect(RenderPearlLightUniformPayload.BYTE_SIZE);

    public RenderPearlNativeLightBuffer(GpuDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    public void upload(RenderPearlLightUniformPayload payload) {
        if (closed) throw new IllegalStateException("SofronLights buffer closed");
        Objects.requireNonNull(payload, "payload");
        ByteBuffer bytes = payload.readOnlyData();
        if (bytes.remaining() != RenderPearlLightUniformPayload.BYTE_SIZE)
            throw new IllegalArgumentException("Invalid std140 payload size: " + bytes.remaining());
        if (buffer != null && bytes.mismatch(submitted) < 0) return;
        if (buffer == null) {
            buffer = device.createBuffer(() -> "Sofron Pack Light RGB uniform",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                RenderPearlLightUniformPayload.BYTE_SIZE);
        }
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.writeToBuffer(buffer.slice(), bytes);
        encoder.submit();
        submitted.put(0, bytes, 0, bytes.capacity());
        uploads++;
    }

    public void bind(RenderPass pass) {
        if (closed || buffer == null) return;
        Objects.requireNonNull(pass, "pass").setUniform(UNIFORM, buffer.slice());
    }

    public long uploads() { return uploads; }
    public boolean isReady() { return !closed && buffer != null; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (buffer != null) { buffer.close(); buffer = null; }
    }
}
