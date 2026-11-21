package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import pl.sofron.light.config.LightConfig;
import pl.sofron.light.engine.LightPalette;
import pl.sofron.light.engine.BlockLightRules;
import pl.sofron.light.engine.LightSource;
import pl.sofron.light.engine.RgbLight;

public final class LightTracker {
    private volatile List<LightSource> sources = List.of();
    private volatile int movingCount = 0;
    // Shared per-tick entity snapshot for both moving light systems.
    private final ArrayList<Entity> nearbyEntities = new ArrayList<>();
    public List<Entity> nearbyEntities() { return nearbyEntities; }
    public int movingCount() { return movingCount; }
    private final ChunkLightIndex chunkIndex = new ChunkLightIndex();
    public ChunkLightIndex chunkIndex() { return chunkIndex; }

    private record SourceKey(long x, long y, long z) {}
    private record Ranked(LightSource source, SourceKey key, double priority) {}
    private static final Comparator<Ranked> ORDER = Comparator
            .comparingDouble(Ranked::priority)
            .thenComparingDouble(entry -> entry.source().x())
            .thenComparingDouble(entry -> entry.source().y())
            .thenComparingDouble(entry -> entry.source().z());

    private final Set<SourceKey> previousSelected = new HashSet<>();
    private static final RgbLight FIRE_COLOR = new RgbLight(1f, .42f, .12f);

    public List<LightSource> sources() { return sources; }
    public void reset() {
        sources = List.of();
        movingCount = 0;
        nearbyEntities.clear();
        chunkIndex.clear();
        previousSelected.clear();

    }

    public void tick(Minecraft client, LightConfig cfg) {
        if (!cfg.dynamicLighting || client.player == null || client.level == null) {
            reset(); return;
        }
        BlockLightRules.poll();
        var player = client.player;
        var found = new ArrayList<LightSource>();
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double maxDistanceSquared = 48 * 48;
        if (cfg.heldItems) {
            addStack(found, player.getMainHandItem(), px, py + 1.1, pz, cfg);
            addStack(found, player.getOffhandItem(), px, py + 1.1, pz, cfg);
        }
        nearbyEntities.clear();
        if (cfg.entityAndItemLighting) {
            for (Entity entity : client.level.entitiesForRendering()) {
                double x = entity.getX(), y = entity.getY(), z = entity.getZ();
                if (entity.distanceToSqr(px, py, pz) > maxDistanceSquared) continue;
                nearbyEntities.add(entity);
                if (cfg.burningEntities && entity.isOnFire()) {
                    found.add(new LightSource(x, y + entity.getBbHeight() * .5, z,
                        (float)cfg.dynamicLightRadius, .9f, FIRE_COLOR));
                }
                if (cfg.droppedItems && entity instanceof ItemEntity drop) {
                    addStack(found, drop.getItem(), x, y + .15, z, cfg);
                }
                if (cfg.heldItems && entity instanceof LivingEntity living && entity != player) {
                    addStack(found, living.getMainHandItem(), x, y + living.getBbHeight()*.6, z, cfg);
                    addStack(found, living.getOffhandItem(), x, y + living.getBbHeight()*.6, z, cfg);
                }
            }
        }
        int movingEnd = found.size();
        if (cfg.stationaryBlockLighting) {
            chunkIndex.tick(client, cfg);
        } else {
            chunkIndex.clear();
        }
        if (movingEnd == 0) {
            movingCount = 0;
            previousSelected.clear();
            sources = List.of();
            return;
        }
        // Compute hysteresis keys and distances once, rather than repeatedly
        // constructing strings during every comparison in TimSort.
        List<Ranked> ranked = new ArrayList<>(movingEnd);
        for (LightSource light : found) {
            SourceKey key = sourceKey(light);
            double distance = squared(light.x()-px, light.y()-py, light.z()-pz);
            ranked.add(new Ranked(light, key,
                    distance - (previousSelected.contains(key) ? 9.0 : 0.0)));
        }
        ranked.sort(ORDER);

        int count = Math.min(ranked.size(), Math.min(cfg.maximumDynamicLights, 256));
        movingCount = Math.min(movingEnd, count);
        List<LightSource> selected = new ArrayList<>(count);
        previousSelected.clear();
        for (int i=0;i<count;i++) {
            Ranked entry = ranked.get(i);
            selected.add(entry.source());
            previousSelected.add(entry.key());
        }
        sources = List.copyOf(selected);
    }
    private static SourceKey sourceKey(LightSource source) {
        return new SourceKey(Math.round(source.x()*2), Math.round(source.y()*2), Math.round(source.z()*2));
    }
    private static double squared(double x,double y,double z) { return x*x+y*y+z*z; }
    private static void addStack(List<LightSource> list, ItemStack stack,
                                 double x, double y, double z, LightConfig cfg) {
        if (stack.isEmpty()) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!BlockLightRules.isTracked(id)
            && !(stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock().defaultBlockState().getLightEmission() > 0)) return;
        var rule = BlockLightRules.rule(id);
        RgbLight rgb = cfg.coloredLighting && rule != null ? rule.color() : BlockLightRules.neutral();
        float power = rule == null ? 1f : rule.intensity();
        int emission = rule != null && rule.emission() != null ? rule.emission() :
            stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState().getLightEmission() : 15;
        float radius = BlockLightRules.tintRadius(rule, (float)cfg.dynamicLightRadius, emission);
        if (radius <= 0f || power <= 0f) return;
        list.add(new LightSource(x,y,z,radius,power,rgb));
    }
}
