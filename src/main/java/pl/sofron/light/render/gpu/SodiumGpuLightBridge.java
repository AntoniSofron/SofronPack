package pl.sofron.light.render.gpu;

import pl.sofron.light.SofronPackLightClient;
import pl.sofron.light.render.GpuLightBuffer;

public final class SodiumGpuLightBridge {
    private static LightGpuManager manager;
    private static GpuLightBuffer frame;
    private static boolean failed;
    private static long uploadCount;

    private SodiumGpuLightBridge() {}

    public static void onTerrainRender(double cameraX, double cameraY, double cameraZ) {
        if (failed || SofronPackLightClient.CONFIG == null) return;
        try {
            if (manager == null) {
                manager = new LightGpuManager(new Lwjgl33LightBackend(), 7);
                frame = new GpuLightBuffer();
            }
            frame.update(SofronPackLightClient.LIGHTS.sources(), cameraX, cameraY, cameraZ,
                    GpuLightBuffer.MAX_GPU_LIGHTS);
            manager.upload(frame);
            uploadCount++;
        } catch (RuntimeException | LinkageError exception) {
            failed = true;
            System.err.println("[Sofron Pack Light] Disabled GPU upload bridge after an error: " + exception);
        }
    }

    public static LightGpuManager manager() { return manager; }

    public static boolean bindActiveShader() {
        if (failed || manager == null) return false;
        try {
            return LwjglLightProgramBinder.bindCurrentProgram(manager, 0.85f);
        } catch (RuntimeException | LinkageError ex) {
            failed = true;
            System.err.println("[Sofron Pack Light] Shader binding disabled: " + ex);
            return false;
        }
    }
    public static long uploadCount() { return uploadCount; }
    public static boolean failed() { return failed; }

    public static void shutdown() {
        if (manager != null) manager.close();
        manager = null;
        frame = null;
        failed = false;
        uploadCount = 0;
    }
}
