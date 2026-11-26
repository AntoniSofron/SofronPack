package pl.sofron.light.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.registries.BuiltInRegistries;
import pl.sofron.light.config.LightConfig;
import pl.sofron.light.engine.BlockLightRules;
import pl.sofron.light.engine.RgbLight;

public final class DynamicEmitterRegistry {
    public record Source(double x, double y, double z, int vanillaLevel,
                         int red, int green, int blue, int tintRadius) {}
    private record Profile(int level,int red,int green,int blue,int radius){}
    private static final Profile DARK=new Profile(0,0,0,0,0);
    private final java.util.IdentityHashMap<net.minecraft.world.item.Item,Profile> profiles=new java.util.IdentityHashMap<>();
    private long profileRevision=Long.MIN_VALUE;
    private double profileRadius;
    private boolean profileColored;
    private volatile List<Source> sources = List.of();
    public List<Source> sources() { return sources; }
    public void clear() { sources = List.of(); profiles.clear(); profileRevision=Long.MIN_VALUE; }

    public void update(Minecraft client, LightConfig cfg) {
        update(client, cfg, client.level == null ? List.of() : client.level.entitiesForRendering());
    }

    public void update(Minecraft client, LightConfig cfg, Iterable<Entity> nearbyEntities) {
        if (client.player == null || client.level == null || !cfg.dynamicLighting) {clear();return;}
        long revision=BlockLightRules.revision();
        if(profileRevision!=revision||profileRadius!=cfg.dynamicLightRadius||profileColored!=cfg.coloredLighting){
            profiles.clear();profileRevision=revision;profileRadius=cfg.dynamicLightRadius;profileColored=cfg.coloredLighting;
        }
        var player=client.player;
        List<Source> collected=new ArrayList<>();
        if (cfg.heldItems) {
            add(collected,player.getMainHandItem(),player.getX(),player.getY()+1.1,player.getZ(),cfg);
            add(collected,player.getOffhandItem(),player.getX(),player.getY()+1.1,player.getZ(),cfg);
        }
        if(cfg.entityAndItemLighting) {
            for(Entity entity:nearbyEntities) {
                if(entity.distanceToSqr(player.getX(),player.getY(),player.getZ())>28*28)continue;
                double x=entity.getX(), y=entity.getY()+entity.getBbHeight()*.55, z=entity.getZ();
                if(cfg.burningEntities && entity.isOnFire())
                    collected.add(new Source(x,y,z,12,cfg.coloredLighting ? 255 : 0,cfg.coloredLighting ? 108 : 0,cfg.coloredLighting ? 31 : 0,8));
                if(cfg.droppedItems && entity instanceof ItemEntity drop)
                    add(collected,drop.getItem(),x,entity.getY()+.2,z,cfg);
                if(cfg.heldItems && entity != player && entity instanceof LivingEntity living) {
                    add(collected,living.getMainHandItem(),x,y,z,cfg);
                    add(collected,living.getOffhandItem(),x,y,z,cfg);
                }
            }
        }
        double px=player.getX(),py=player.getY(),pz=player.getZ();
        if(collected.size()>1)collected.sort(Comparator.comparingDouble(s -> squared(s.x()-px,s.y()-py,s.z()-pz)));
        sources=List.copyOf(collected.subList(0,Math.min(collected.size(),Math.min(64, cfg.maximumDynamicLights))));
    }
    private static double squared(double x,double y,double z){return x*x+y*y+z*z;}
    private void add(List<Source> result,ItemStack item,double x,double y,double z,LightConfig cfg){
        if(item.isEmpty())return;
        Profile p=profiles.computeIfAbsent(item.getItem(),i->profile(i,cfg));
        if(p.level>0)result.add(new Source(x,y,z,p.level,p.red,p.green,p.blue,p.radius));
    }
    private static Profile profile(net.minecraft.world.item.Item item,LightConfig cfg){
        String id=BuiltInRegistries.ITEM.getKey(item).toString();
        var rule=BlockLightRules.rule(id);
        int vanilla=item instanceof BlockItem bi?bi.getBlock().defaultBlockState().getLightEmission():-1;
        if(!BlockLightRules.isTracked(id)&&vanilla<=0)return DARK;
        int emission=rule!=null&&rule.emission()!=null?rule.emission():vanilla>=0?vanilla:14;
        if(emission<=0)return DARK;
        RgbLight hue=rule!=null&&cfg.coloredLighting?rule.color():BlockLightRules.neutral();
        float power=rule!=null&&cfg.coloredLighting?rule.intensity():0f;
        int radius=rule==null?0:Math.max(0,Math.min(15,Math.round(BlockLightRules.tintRadius(rule,(float)cfg.dynamicLightRadius,emission))));
        return new Profile(Math.min(emission,15),Math.round(255*hue.red()*power),
                Math.round(255*hue.green()*power),Math.round(255*hue.blue()*power),radius);
    }
}
