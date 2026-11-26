package pl.sofron.light.runtime;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import pl.sofron.light.config.LightConfig;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;
import pl.sofron.light.engine.RgbLightField;

public final class LiveLightProbe {
    private volatile RgbLight eyeLight = RgbLight.BLACK;
    private Object lastWorld;
    private List<LightSource> lastMoving = List.of();
    private long lastRevision = Long.MIN_VALUE;
    private double lastX, lastY, lastZ;
    private int lastIntensity;
    private int age;
    private volatile boolean dirty = true;

    public RgbLight eyeLight() { return eyeLight; }

    public RgbLight sample(Minecraft client, LightTracker tracker, LightConfig cfg,
                           double x, double y, double z) {
        if (client.level == null || !cfg.dynamicLighting) return RgbLight.BLACK;
        var stationary = tracker.chunkIndex().lightsAffectingChunk((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4);
        List<LightSource> nearby;
        if (stationary.isEmpty()) {
            // Reuse the immutable moving-light snapshot instead of allocating a list.
            nearby = tracker.sources();
        } else {
            var combined = new java.util.ArrayList<LightSource>(tracker.sources().size() + Math.min(64,stationary.size()));
            combined.addAll(tracker.sources());
            // Filter before allocating references for every lava block in a column.
            for(var s:stationary){
                double dx=x-s.x(),dy=y-s.y(),dz=z-s.z();
                if(dx*dx+dy*dy+dz*dz<s.radius()*s.radius())combined.add(s);
            }
            nearby = combined;
        }
        return RgbLightField.sample(x, y, z, nearby,
            cfg.intensityPercent / 100.0f, new MinecraftOcclusion(client.level));
    }

    public void invalidate(BlockPos pos) {
        if (lastWorld == null) return;
        double dx = pos.getX() + .5 - lastX;
        double dy = pos.getY() + .5 - lastY;
        double dz = pos.getZ() + .5 - lastZ;
        if (dx*dx + dy*dy + dz*dz < 80.0 * 80.0) dirty = true;
    }

    public void invalidateChunk(int cx, int cz) {
        if (lastWorld == null) return;
        if (Math.abs(cx - ((int)Math.floor(lastX) >> 4)) <= 6 &&
                Math.abs(cz - ((int)Math.floor(lastZ) >> 4)) <= 6) dirty = true;
    }

    public void tick(Minecraft client, LightTracker tracker, LightConfig cfg) {
        if (client.player == null || client.level == null || !cfg.dynamicLighting) {
            eyeLight = RgbLight.BLACK;
            lastWorld = null;
            lastMoving = List.of();
            dirty = true;
            age = 0;
            return;
        }
        double x = client.player.getX();
        double y = client.player.getEyeY();
        double z = client.player.getZ();
        long revision = tracker.chunkIndex().revision();
        List<LightSource> moving = tracker.sources();

        if (!dirty && lastWorld == client.level && lastRevision == revision
                && lastX == x && lastY == y && lastZ == z
                && lastIntensity == cfg.intensityPercent
                && lastMoving.equals(moving) && ++age < 20) return;

        age = 0;
        dirty = false;
        lastWorld = client.level;
        lastRevision = revision;
        lastMoving = moving;
        lastIntensity = cfg.intensityPercent;
        lastX = x;
        lastY = y;
        lastZ = z;
        eyeLight = sample(client, tracker, cfg, x, y, z);
    }
}
