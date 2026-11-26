package pl.sofron.light.render.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import pl.sofron.light.SofronPackLightClient;
import pl.sofron.light.runtime.ChunkLightAtlas;
import pl.sofron.light.runtime.AsyncStationaryAtlas;
import net.minecraft.client.Minecraft;
import pl.sofron.light.emissive.OreMasks;

public final class SodiumRenderPassCoordinator {
    private static RenderPearlNativeLightBuffer nativeBuffer;
    private static RenderPearlChunkAtlasBuffer chunkAtlasBuffer;
    private static RenderPearlChunkAtlasBuffer oreBuffer;
    private static OreMasks uploadedMasks;
    private static RenderPearlChunkAtlasBuffer fadeBuffer;
    private static java.nio.ByteBuffer uploadedFades;
    private static RenderPearlChunkAtlasBuffer voxelBuffer;
    private static pl.sofron.light.runtime.DynamicVoxelField.Snapshot lastVoxels;
    private static final java.nio.ByteBuffer voxelBytes = java.nio.ByteBuffer.allocateDirect(
        16 + 4 * pl.sofron.light.runtime.DynamicVoxelField.CELLS).order(java.nio.ByteOrder.nativeOrder());
    private static final AsyncStationaryAtlas stationaryAtlas = new AsyncStationaryAtlas();
    private static Object atlasWorld;
    private static int publishedOriginX, publishedOriginZ;
    private static long lastRevision = Long.MIN_VALUE;
    private static int lastOriginX, lastOriginZ, lastWidth;
    private static final RenderPearlLightUniformPayload payload = new RenderPearlLightUniformPayload();
    private static long submissions;
    private static volatile int diagnosticAtlasReferences;
    private static volatile int diagnosticAtlasCells;
    private static volatile long diagnosticAtlasFailures;
    public static int diagnosticAtlasReferences() { return diagnosticAtlasReferences; }
    public static int diagnosticAtlasCells() { return diagnosticAtlasCells; }
    public static long diagnosticAtlasFailures() { return diagnosticAtlasFailures; }
    public static long diagnosticDrawSubmissions() { return submissions; }
    private SodiumRenderPassCoordinator() {}

    public static void prepareFrame(double x, double y, double z) {
        if (SofronPackLightClient.CONFIG == null || Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) return;
        if (nativeBuffer == null) nativeBuffer = new RenderPearlNativeLightBuffer(RenderSystem.getDevice());
        if (chunkAtlasBuffer == null) {
            chunkAtlasBuffer = new RenderPearlChunkAtlasBuffer(RenderSystem.getDevice(), "SofronLightAtlas");
            chunkAtlasBuffer.upload(ChunkLightAtlas.empty());
        }
        if (oreBuffer == null) oreBuffer = new RenderPearlChunkAtlasBuffer(RenderSystem.getDevice(), "SofronOreRects");
        var masks = OreMasks.current();
        if (uploadedMasks != masks) {
            oreBuffer.upload(masks.rectangles().duplicate());
            uploadedMasks = masks;
        }
        if (fadeBuffer == null) fadeBuffer = new RenderPearlChunkAtlasBuffer(RenderSystem.getDevice(), "SofronOreFades");
        var fades = pl.sofron.light.emissive.OreFades.current().bytes();
        if (uploadedFades != fades) {
            fadeBuffer.upload(fades.duplicate());
            uploadedFades = fades;
        }
        int debugMode = switch (SofronPackLightClient.CONFIG.rgbDebugMode) {
            case "raw" -> 1;
            case "strength" -> 2;
            case "mask" -> 3;
            default -> 0;
        };
        Minecraft mc = Minecraft.getInstance();
        int distance = Math.max(1, mc.options.getEffectiveRenderDistance());
        int width = 2*distance+1;
        int originX = (mc.player.blockPosition().getX() >> 4)-distance;
        int originZ = (mc.player.blockPosition().getZ() >> 4)-distance;
        var field = SofronPackLightClient.VOXELS.field();
        if (voxelBuffer == null || field != lastVoxels) {
            if (voxelBuffer == null) voxelBuffer = new RenderPearlChunkAtlasBuffer(RenderSystem.getDevice(), "SofronVoxels");
            voxelBytes.clear();
            if (field == null) voxelBytes.putLong(0).putLong(0);
            else pl.sofron.light.runtime.DynamicVoxelField.put(voxelBytes, field);
            voxelBytes.flip();
            if(field!=null&&lastVoxels!=null){
                // Dynamic payload has a fixed layout: 16-byte header + int cells.
                // Compare page ranges, not whole GPU buffers on every torch move.
                var ranges=dynamicRanges(lastVoxels,field);
                voxelBuffer.uploadRanges(voxelBytes,ranges,ranges.size()>32);
            }else voxelBuffer.upload(voxelBytes);
            lastVoxels = field;
        }
        var index = SofronPackLightClient.LIGHTS.chunkIndex();
        if (atlasWorld != mc.level) {
            stationaryAtlas.reset();
            atlasWorld = mc.level;
            lastRevision = Long.MIN_VALUE;
            diagnosticAtlasReferences = 0;
            diagnosticAtlasCells = 0;
            chunkAtlasBuffer.upload(ChunkLightAtlas.empty());
        }
        try {
            var completed = stationaryAtlas.poll();
            if (completed != null) {
                chunkAtlasBuffer.uploadDelta(completed);
                publishedOriginX = completed.x(); publishedOriginZ = completed.z();
                diagnosticAtlasReferences = completed.atlas().lightReferences();
                diagnosticAtlasCells = completed.atlas().chunkCount();
            }
        } catch (RuntimeException failed) {
            // Recover on the next source revision; no stale delta baseline.
            diagnosticAtlasFailures++;
            stationaryAtlas.reset();
            diagnosticAtlasReferences = 0;
            diagnosticAtlasCells = 0;
            chunkAtlasBuffer.upload(ChunkLightAtlas.empty());
        }
        if (!stationaryAtlas.busy() && (index.revision() != lastRevision || width != lastWidth
                || originX != lastOriginX || originZ != lastOriginZ)) {
            stationaryAtlas.submit(index.snapshotFor(mc.level),originX,originZ,width,index.revision());
            lastRevision = index.revision(); lastWidth = width;
            lastOriginX = originX; lastOriginZ = originZ;
        }
        // The published atlas and its origin are one transaction. Never sample
        // yesterday's atlas using the new player's chunk coordinates.
        payload.update(debugMode, publishedOriginX, publishedOriginZ, x, y, z, field,
                diagnosticAtlasReferences != 0 && SofronPackLightClient.CONFIG.stationaryBlockLighting
                && SofronPackLightClient.CONFIG.coloredLighting);
        nativeBuffer.upload(payload);
    }

    private static java.util.List<AsyncStationaryAtlas.Range> dynamicRanges(
            pl.sofron.light.runtime.DynamicVoxelField.Snapshot before,
            pl.sofron.light.runtime.DynamicVoxelField.Snapshot after){
        var result=new java.util.ArrayList<AsyncStationaryAtlas.Range>();
        int[] a=before.voxels(),b=after.voxels();
        if(before.x0()!=after.x0()||before.y0()!=after.y0()||before.z0()!=after.z0())
            result.add(new AsyncStationaryAtlas.Range(0,16));
        int start=-1;
        for(int i=0;i<b.length;i+=1024){
            int end=Math.min(b.length,i+1024);
            boolean changed=java.util.Arrays.mismatch(a,i,end,b,i,end)>=0;
            if(changed&&start<0)start=i;
            if(!changed&&start>=0){result.add(new AsyncStationaryAtlas.Range(16+4*start,4*(i-start)));start=-1;}
        }
        if(start>=0)result.add(new AsyncStationaryAtlas.Range(16+4*start,4*(b.length-start)));
        return result;
    }

    public static void beforeDraw(RenderPass pass, CameraTransform camera) {
        if (pass == null || camera == null || nativeBuffer == null || !nativeBuffer.isReady()) return;
        nativeBuffer.bind(pass);
        if (chunkAtlasBuffer != null) chunkAtlasBuffer.bind(pass);
        oreBuffer.bind(pass);
        fadeBuffer.bind(pass);
        voxelBuffer.bind(pass);
        submissions++;
    }
    public static void close() { stationaryAtlas.reset(); atlasWorld=null; diagnosticAtlasReferences=0; diagnosticAtlasCells=0; if (nativeBuffer != null) nativeBuffer.close(); nativeBuffer = null; if(chunkAtlasBuffer!=null) chunkAtlasBuffer.close(); chunkAtlasBuffer=null; if(oreBuffer!=null) oreBuffer.close(); oreBuffer=null; uploadedMasks=null; if(fadeBuffer!=null) fadeBuffer.close(); fadeBuffer=null; uploadedFades=null; if(voxelBuffer!=null) voxelBuffer.close(); voxelBuffer=null; lastVoxels=null; lastRevision=Long.MIN_VALUE; }
    public static long submissions() { return submissions; }
}
