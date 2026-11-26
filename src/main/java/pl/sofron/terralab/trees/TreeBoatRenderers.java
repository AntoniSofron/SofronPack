package pl.sofron.terralab.trees;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.BoatRenderer;

public final class TreeBoatRenderers {
    public static void register() {
        EntityRendererRegistry.register(TreeBoats.PINE.boat(), ctx -> new BoatRenderer(ctx, ModelLayers.SPRUCE_BOAT));
        EntityRendererRegistry.register(TreeBoats.PINE.chestBoat(), ctx -> new BoatRenderer(ctx, ModelLayers.SPRUCE_CHEST_BOAT));
        EntityRendererRegistry.register(TreeBoats.APPLE.boat(), ctx -> new BoatRenderer(ctx, ModelLayers.OAK_BOAT));
        EntityRendererRegistry.register(TreeBoats.APPLE.chestBoat(), ctx -> new BoatRenderer(ctx, ModelLayers.OAK_CHEST_BOAT));
        EntityRendererRegistry.register(TreeBoats.FIR.boat(), ctx -> new BoatRenderer(ctx, ModelLayers.SPRUCE_BOAT));
        EntityRendererRegistry.register(TreeBoats.FIR.chestBoat(), ctx -> new BoatRenderer(ctx, ModelLayers.SPRUCE_CHEST_BOAT));
        EntityRendererRegistry.register(TreeBoats.CHESTNUT.boat(), ctx -> new BoatRenderer(ctx, ModelLayers.OAK_BOAT));
        EntityRendererRegistry.register(TreeBoats.CHESTNUT.chestBoat(), ctx -> new BoatRenderer(ctx, ModelLayers.OAK_CHEST_BOAT));
    }
}
