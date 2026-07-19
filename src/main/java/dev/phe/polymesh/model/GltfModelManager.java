package dev.phe.polymesh.model;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GltfModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(GltfModelManager.class);

    public static final GltfModelManager INSTANCE = new GltfModelManager();

    private final Map<ResourceLocation, RuntimeModel> modelCache = new HashMap<>();
    private final java.util.Set<ResourceLocation> reportedMissing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean loaded = false;

    private GltfModelManager() {}

    public synchronized void ensureLoaded() {
        if (loaded && !modelCache.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            LOGGER.error("Minecraft resource manager is unavailable; GLTF models cannot be loaded yet");
            return;
        }

        reload(mc.getResourceManager());
    }

    public synchronized void reload(ResourceManager resourceManager) {
        modelCache.clear();
        reportedMissing.clear();
        loaded = true;
        LOGGER.info("Loading GLTF models...");
        try {
            var resources = resourceManager.listResources("models/gltf",
                loc -> loc.getPath().endsWith(".glb") || loc.getPath().endsWith(".gltf"));
            for (var entry : resources.entrySet()) {
                ResourceLocation location = entry.getKey();
                try (var inputStream = entry.getValue().open()) {
                    RuntimeModel model = loadModelResource(resourceManager, location, inputStream);

                    if (model != null) {
                        ResourceLocation modelLoc = new ResourceLocation(
                            location.getNamespace(), modelIdPath(location.getPath()));
                        modelCache.put(modelLoc, model);
                        LOGGER.info("Loaded GLTF model: {} -> {}", location, modelLoc);
                    } else {
                        LOGGER.warn("GLTF adapter returned null for {}", location);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load GLTF model {}: {}", location, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error listing GLTF resources: {}", e.getMessage(), e);
        }
        LOGGER.info("Loaded {} GLTF models into cache", modelCache.size());
    }

    /** Strip the {@code models/gltf/} prefix and the trailing {@code .gltf}/{@code .glb} extension only. */
    static String modelIdPath(String resourcePath) {
        String path = resourcePath.startsWith("models/gltf/")
            ? resourcePath.substring("models/gltf/".length())
            : resourcePath;
        if (path.endsWith(".gltf")) {
            return path.substring(0, path.length() - ".gltf".length());
        }
        if (path.endsWith(".glb")) {
            return path.substring(0, path.length() - ".glb".length());
        }
        return path;
    }

    private RuntimeModel loadModelResource(ResourceManager resourceManager, ResourceLocation location, InputStream inputStream) throws Exception {
        if (location.getPath().endsWith(".glb")) {
            return JgltfModelAdapter.adapt(location, inputStream, null);
        }

        com.google.gson.JsonObject gltfRoot;
        byte[] gltfBytes = inputStream.readAllBytes();
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(gltfBytes)) {
            gltfRoot = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(bais)).getAsJsonObject();
        }

        // Determine the external .bin name from the GLTF itself
        String binName = null;
        if (gltfRoot.has("buffers")) {
            com.google.gson.JsonArray buffers = gltfRoot.getAsJsonArray("buffers");
            if (buffers.size() > 0) {
                com.google.gson.JsonObject firstBuffer = buffers.get(0).getAsJsonObject();
                if (firstBuffer.has("uri")) {
                    binName = firstBuffer.get("uri").getAsString();
                }
            }
        }

        // Re-create input stream for the adapter
        try (java.io.ByteArrayInputStream modelStream = new java.io.ByteArrayInputStream(gltfBytes)) {
            InputStream binStream = null;
            Map<String, byte[]> externalResources = loadExternalImageResources(resourceManager, location, gltfRoot);
            if (binName != null && !binName.isEmpty()) {
                // Build the .bin resource location relative to the GLTF's directory
                String gltfDir = location.getPath();
                int lastSlash = gltfDir.lastIndexOf('/');
                String binPath = lastSlash >= 0 ? gltfDir.substring(0, lastSlash + 1) + binName : binName;
                ResourceLocation binLoc = new ResourceLocation(location.getNamespace(), binPath);
                var binResource = resourceManager.getResource(binLoc);
                if (binResource.isPresent()) {
                    binStream = binResource.get().open();
                    LOGGER.debug("Found external buffer for {}: {}", location, binLoc);
                } else {
                    LOGGER.warn("External buffer not found for {}: expected at {}", location, binLoc);
                }
            }

            try {
                return JgltfModelAdapter.adapt(location, modelStream, binStream, binName, externalResources);
            } finally {
                if (binStream != null) {
                    try { binStream.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private Map<String, byte[]> loadExternalImageResources(ResourceManager resourceManager, ResourceLocation modelLocation, com.google.gson.JsonObject gltfRoot) {
        Map<String, byte[]> externalResources = new HashMap<>();
        if (!gltfRoot.has("images")) {
            return externalResources;
        }

        com.google.gson.JsonArray images = gltfRoot.getAsJsonArray("images");
        for (int i = 0; i < images.size(); i++) {
            com.google.gson.JsonObject image = images.get(i).getAsJsonObject();
            if (!image.has("uri")) {
                continue;
            }

            String uri = image.get("uri").getAsString();
            if (!isRelativeExternalUri(uri)) {
                continue;
            }

            ResourceLocation imageLocation = resolveResource(modelLocation, uri);
            var imageResource = resourceManager.getResource(imageLocation);
            if (imageResource.isEmpty()) {
                imageLocation = new ResourceLocation(modelLocation.getNamespace(), uri);
                imageResource = resourceManager.getResource(imageLocation);
            }

            if (imageResource.isPresent()) {
                try (InputStream imageStream = imageResource.get().open()) {
                    externalResources.put(uri, imageStream.readAllBytes());
                    LOGGER.debug("Found external image for {}: {}", modelLocation, imageLocation);
                } catch (Exception e) {
                    LOGGER.warn("Failed to read external image {} for {}: {}", imageLocation, modelLocation, e.getMessage());
                }
            } else {
                LOGGER.warn("External image not found for {}: {}", modelLocation, uri);
            }
        }

        return externalResources;
    }

    private boolean isRelativeExternalUri(String uri) {
        try {
            URI parsed = URI.create(uri);
            return !parsed.isAbsolute() && !uri.startsWith("/") && !uri.contains("\\") && !uri.contains("..");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ResourceLocation resolveResource(ResourceLocation modelLocation, String relativeUri) {
        String gltfDir = modelLocation.getPath();
        int lastSlash = gltfDir.lastIndexOf('/');
        String resourcePath = lastSlash >= 0 ? gltfDir.substring(0, lastSlash + 1) + relativeUri : relativeUri;
        return new ResourceLocation(modelLocation.getNamespace(), resourcePath);
    }

    public RuntimeModel getModel(ResourceLocation location) {
        ensureLoaded();
        RuntimeModel model = modelCache.get(location);
        if (model == null) {
            // getModel is called from render loops; report each missing id once, not per frame.
            if (reportedMissing.add(location)) {
                LOGGER.warn("GLTF model not found: {}. Loaded models: {}", location, modelCache.keySet());
            }
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Model found in cache: {}", location);
        }
        return model;
    }

    public Map<ResourceLocation, RuntimeModel> getAllModels() {
        ensureLoaded();
        return Map.copyOf(modelCache);
    }

    public int getModelCount() {
        ensureLoaded();
        return modelCache.size();
    }

    public boolean isModelLoaded(ResourceLocation location) {
        ensureLoaded();
        return modelCache.containsKey(location);
    }
}
