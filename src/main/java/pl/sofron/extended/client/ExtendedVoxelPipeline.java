package pl.sofron.extended.client;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Textured 3D far terrain. Reuses Minecraft's position/UV/color shader pair
 * and block atlas. The depth buffer hides geometry behind live Sodium chunks.
 */
public final class ExtendedVoxelPipeline {
    public static final RenderPipeline TERRAIN =
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.parse("sofronpack:pipeline/extended_voxel_terrain"))
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withCull(false)
                    .build();
    private ExtendedVoxelPipeline() {}
}
