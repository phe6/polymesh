package dev.phe.polymesh.client;

import dev.phe.polymesh.api.GltfRenderOptions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class GltfBlockEntityRendererFactory {

    public static <T extends BlockEntity> BlockEntityRendererProvider<T> create(ResourceLocation modelLocation, float scale) {
        return context -> new GltfBlockEntityRenderer<T>(context, modelLocation, scale) {};
    }

    public static <T extends BlockEntity> BlockEntityRendererProvider<T> create(ResourceLocation modelLocation, GltfRenderOptions options) {
        return context -> new GltfBlockEntityRenderer<T>(context, modelLocation, options) {};
    }

    public static <T extends BlockEntity> BlockEntityRendererProvider<T> create(ResourceLocation modelLocation) {
        return create(modelLocation, 1.0f);
    }
}
