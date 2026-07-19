package dev.phe.polymesh.rendering;

import java.util.function.Function;

import dev.phe.polymesh.model.RuntimeMaterial;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Utility class for selecting appropriate Minecraft {@link RenderType} based on
 * GLTF material properties.
 */
public final class MeshRenderType {
    private static final RenderStateShard.TransparencyStateShard NO_TRANSPARENCY =
        new RenderStateShard.TransparencyStateShard("polymesh_no_transparency", RenderSystem::disableBlend, () -> {});
    private static final RenderStateShard.TransparencyStateShard TRANSLUCENT_TRANSPARENCY =
        new RenderStateShard.TransparencyStateShard("polymesh_translucent_transparency", () -> {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
        }, () -> {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        });
    private static final RenderStateShard.CullStateShard NO_CULL = new RenderStateShard.CullStateShard(false);
    private static final RenderStateShard.LightmapStateShard LIGHTMAP = new RenderStateShard.LightmapStateShard(true);
    private static final RenderStateShard.OverlayStateShard OVERLAY = new RenderStateShard.OverlayStateShard(true);

    private static final Function<ResourceLocation, RenderType> GLTF_SOLID = Util.memoize(texture -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeEntitySolidShader))
            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(NO_TRANSPARENCY)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .createCompositeState(true);
        return RenderType.create("polymesh_gltf_solid", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES, 262144, true, false, state);
    });

    private static final Function<ResourceLocation, RenderType> GLTF_CUTOUT_NO_CULL = Util.memoize(texture -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeEntityCutoutNoCullShader))
            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(NO_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .createCompositeState(true);
        return RenderType.create("polymesh_gltf_cutout_no_cull", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES, 262144, true, false, state);
    });

    private static final Function<ResourceLocation, RenderType> GLTF_TRANSLUCENT = Util.memoize(texture -> {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentShader))
            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setCullState(NO_CULL)
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .createCompositeState(true);
        return RenderType.create("polymesh_gltf_translucent", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES, 262144, true, true, state);
    });

    private MeshRenderType() {
    }

    /**
     * Choose a render type appropriate for the given material and texture.
     *
     * @param material the runtime material
     * @param texture  the texture resource location
     * @return a suitable RenderType
     */
    public static RenderType forMaterial(RuntimeMaterial material, ResourceLocation texture) {
        RuntimeMaterial.AlphaMode alphaMode = material.getAlphaMode();

        switch (alphaMode) {
            case BLEND:
                return GLTF_TRANSLUCENT.apply(texture);
            case MASK:
                return GLTF_CUTOUT_NO_CULL.apply(texture);
            case OPAQUE:
            default:
                if (material.isDoubleSided()) {
                    return GLTF_CUTOUT_NO_CULL.apply(texture);
                }
                return GLTF_SOLID.apply(texture);
        }
    }

    public static RenderType getCutoutRenderType(ResourceLocation texture) {
        return GLTF_CUTOUT_NO_CULL.apply(texture);
    }

    public static RenderType getTranslucentRenderType(ResourceLocation texture) {
        return GLTF_TRANSLUCENT.apply(texture);
    }

    public static RenderType getSolidRenderType(ResourceLocation texture) {
        return GLTF_SOLID.apply(texture);
    }
}
