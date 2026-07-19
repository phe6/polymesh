package dev.phe.polymesh.client;

import dev.phe.polymesh.api.GltfRenderOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class GltfEntityRendererFactory {

    public static <T extends Entity> EntityRendererProvider<T> create(ResourceLocation modelLocation, float scale) {
        return context -> new GltfEntityRenderer<T>(context, modelLocation, scale) {};
    }

    public static <T extends Entity> EntityRendererProvider<T> create(ResourceLocation modelLocation, GltfRenderOptions options) {
        return context -> new GltfEntityRenderer<T>(context, modelLocation, options) {};
    }

    public static <T extends Entity> EntityRendererProvider<T> create(ResourceLocation modelLocation) {
        return create(modelLocation, 1.0f);
    }
}
