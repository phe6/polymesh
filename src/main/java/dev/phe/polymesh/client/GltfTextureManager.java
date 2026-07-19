package dev.phe.polymesh.client;

import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * Maps texture references found inside GLTF files to Minecraft {@link ResourceLocation}s.
 *
 * <p>Handles three sources: textures shipped under {@code assets/<modid>/textures/gltf/},
 * embedded GLB images (registered as {@link DynamicTexture}s at model load), and direct
 * {@code namespace:path} strings in material data.</p>
 */
public class GltfTextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(GltfTextureManager.class);

    public static final GltfTextureManager INSTANCE = new GltfTextureManager();

    private volatile Map<ResourceLocation, ResourceLocation> textureMappings = Map.of();
    private final Map<ResourceLocation, byte[]> embeddedImages = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, ResourceLocation> embeddedTextureMappings = new ConcurrentHashMap<>();

    private GltfTextureManager() {}

    public Map<ResourceLocation, ResourceLocation> loadTextures(ResourceManager resourceManager) {
        Map<ResourceLocation, ResourceLocation> mappings = new HashMap<>();
        embeddedImages.clear();
        embeddedTextureMappings.clear();

        var resources = resourceManager.listResources("textures/gltf", path ->
            path.toString().endsWith(".png") || path.toString().endsWith(".jpg") || path.toString().endsWith(".jpeg"));

        for (var entry : resources.entrySet()) {
            ResourceLocation textureLocation = entry.getKey();
            try {
                ResourceLocation mappedLocation = new ResourceLocation(
                    textureLocation.getNamespace(), textureKeyPath(textureLocation.getPath()));
                mappings.put(mappedLocation, textureLocation);
                LOGGER.debug("Mapped GLTF texture: {} -> {}", mappedLocation, textureLocation);
            } catch (Exception e) {
                LOGGER.error("Failed to load GLTF texture {}: {}", textureLocation, e.getMessage());
            }
        }

        LOGGER.info("Loaded {} GLTF textures", mappings.size());
        textureMappings = Map.copyOf(mappings);
        return mappings;
    }

    /** Strip the {@code textures/gltf/} prefix and the trailing image extension only. */
    private static String textureKeyPath(String resourcePath) {
        String path = resourcePath.startsWith("textures/gltf/")
            ? resourcePath.substring("textures/gltf/".length())
            : resourcePath;
        for (String extension : new String[] {".png", ".jpg", ".jpeg"}) {
            if (path.endsWith(extension)) {
                return path.substring(0, path.length() - extension.length());
            }
        }
        return path;
    }

    public ResourceLocation getTexture(ResourceLocation textureKey) {
        ResourceLocation embedded = embeddedTextureMappings.get(textureKey);
        if (embedded != null) {
            return embedded;
        }
        return textureMappings.get(textureKey);
    }

    public ResourceLocation registerEmbeddedTexture(ResourceLocation modelLocation, int imageIndex, ByteBuffer imageData) {
        if (imageData == null) {
            return null;
        }

        ByteBuffer copySource = imageData.slice();
        byte[] bytes = new byte[copySource.remaining()];
        copySource.get(bytes);

        ResourceLocation textureLocation = new ResourceLocation(
            modelLocation.getNamespace(),
            "gltf_embedded/" + modelLocation.getPath().replace('/', '_') + "/" + imageIndex
        );
        embeddedImages.put(textureLocation, bytes);
        uploadEmbeddedTexture(textureLocation, bytes);
        return textureLocation;
    }

    @Nullable
    public ResourceLocation resolveMaterialTexture(String uri, ResourceLocation modelLocation) {
        if (uri == null || uri.isBlank()) {
            return null;
        }

        if (uri.contains(":")) {
            try {
                ResourceLocation direct = new ResourceLocation(uri);
                ResourceLocation mapped = getTexture(direct);
                return mapped != null ? mapped : direct;
            } catch (Exception e) {
                LOGGER.warn("Invalid texture URI: {}", uri);
                return null;
            }
        }

        ResourceLocation resolved = resolveTexturePath(uri, modelLocation);
        if (resolved != null) {
            return resolved;
        }
        return new ResourceLocation(modelLocation.getNamespace(), "textures/gltf/" + uri);
    }

    public ResourceLocation resolveTexturePath(String uri, ResourceLocation modelLocation) {
        if (uri == null) return null;

        ResourceLocation textureLocation;
        if (uri.contains(":")) {
            try {
                textureLocation = new ResourceLocation(uri);
            } catch (Exception e) {
                LOGGER.warn("Invalid texture URI: {}", uri);
                return null;
            }
        } else if (uri.startsWith("/")) {
            String path = modelLocation.getPath().replace("models/gltf/", "");
            textureLocation = new ResourceLocation(modelLocation.getNamespace(), path + uri);
        } else {
            textureLocation = new ResourceLocation(modelLocation.getNamespace(),
                "textures/gltf/" + uri);
        }

        return getTexture(textureLocation);
    }

    public Map<ResourceLocation, ResourceLocation> getAllMappings() {
        return Map.copyOf(textureMappings);
    }

    private void uploadEmbeddedTexture(ResourceLocation textureLocation, byte[] bytes) {
        try {
            ByteBuffer imageBuffer = ByteBuffer.allocateDirect(bytes.length);
            imageBuffer.put(bytes).flip();
            NativeImage image = NativeImage.read(imageBuffer);
            DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
            embeddedTextureMappings.put(textureLocation, textureLocation);
            LOGGER.debug("Registered embedded GLTF texture: {}", textureLocation);
        } catch (Exception e) {
            LOGGER.warn("Failed to register embedded GLTF texture {}: {}", textureLocation, e.getMessage());
        }
    }
}
