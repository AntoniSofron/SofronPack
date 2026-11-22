package pl.sofron.extended.client;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * Textured 3D far terrain. Reuses Minecraft's position/UV/color shader pair
 * and block atlas. The depth buffer hides geometry behind live Sodium chunks.
 */
public final class ExtendedVoxelPipeline {
    // One quad is one 48-byte INSTANCE, not four 24-byte vertices.
    // All three uvec4 attributes have a step rate of one instance.
    public static final VertexFormat QUAD_INSTANCE = VertexFormat.builder(1)
            .addAttribute("PackedA", GpuFormat.RGBA32_UINT)
            .addAttribute("PackedB", GpuFormat.RGBA32_UINT)
            .addAttribute("PackedC", GpuFormat.RGBA32_UINT)
            .build();

    public static final RenderPipeline TERRAIN =
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.parse("sofronpack:pipeline/extended_voxel_terrain"))
                    .withVertexBinding(0, QUAD_INSTANCE)
                    .withVertexShader(Identifier.parse("minecraft:core/sofronpack/extended_voxel"))
                    .withFragmentShader(Identifier.parse("minecraft:core/sofronpack/extended_voxel"))
                    .withBindGroupLayout(BindGroupLayouts.FOG)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                    .withColorTargetState(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withCull(false)
                    .build();
    private ExtendedVoxelPipeline() {}
}
