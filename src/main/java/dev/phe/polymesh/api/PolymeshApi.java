package dev.phe.polymesh.api;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.client.GltfItemRenderer;
import dev.phe.polymesh.model.GltfModelManager;
import dev.phe.polymesh.model.RuntimeModel;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public API for the Polymesh library.
 *
 * <p>Third-party mods should call these methods during their client setup to associate
 * GLTF models with items, blocks, or entities.</p>
 */
public class PolymeshApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolymeshApi.class);

    // ---- Item Renderer Registry ----

    // Written during (possibly parallel) mod client setup, read from the render thread.
    private static final Map<Item, ResourceLocation> ITEM_RENDERERS = new ConcurrentHashMap<>();
    private static final Map<Item, GltfRenderOptions> ITEM_RENDER_OPTIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ResourceLocation[]> LOD_MODELS = new ConcurrentHashMap<>();
    private static final PolymeshRenderStats RENDER_STATS = new PolymeshRenderStats();

    /**
     * Register a GLTF model to be used as the renderer for an item.
     *
     * <p>Call this in your mod's client setup. The item itself must implement
     * {@link Item#initializeClient(java.util.function.Consumer)} and return a
     * {@link GltfItemRenderer} from the extensions — use the
     * {@link GltfItemRenderer#GltfItemRenderer(Item)} constructor, which reads the model and
     * options registered here, so the model id lives in exactly one place.</p>
     *
     * @param item          the item to render
     * @param modelLocation the resource location of the GLTF model
     */
    public static void registerItemRenderer(Item item, ResourceLocation modelLocation) {
        registerItemRenderer(item, modelLocation, GltfRenderOptions.DEFAULT);
    }

    public static void registerItemRenderer(Item item, ResourceLocation modelLocation, GltfRenderOptions options) {
        ITEM_RENDERERS.put(item, modelLocation);
        ITEM_RENDER_OPTIONS.put(item, options);
        LOGGER.debug("Registered item renderer for {} -> {} with options {}", item, modelLocation, options);
    }

    /**
     * Get the registered model location for an item, if any.
     *
     * @param item the item to look up
     * @return the model resource location, or null if not registered
     */
    public static ResourceLocation getItemModelLocation(Item item) {
        return ITEM_RENDERERS.get(item);
    }

    public static GltfRenderOptions getItemRenderOptions(Item item) {
        return ITEM_RENDER_OPTIONS.getOrDefault(item, GltfRenderOptions.DEFAULT);
    }

    public static void registerLodModel(ResourceLocation baseModel, ResourceLocation... lodModels) {
        LOD_MODELS.put(baseModel, lodModels != null ? lodModels.clone() : new ResourceLocation[0]);
        LOGGER.debug("Registered {} LOD model(s) for {}", lodModels != null ? lodModels.length : 0, baseModel);
    }

    public static ResourceLocation[] getRegisteredLodModels(ResourceLocation baseModel) {
        ResourceLocation[] lods = LOD_MODELS.get(baseModel);
        return lods != null ? lods.clone() : new ResourceLocation[0];
    }

    public static PolymeshRenderStats getRenderStats() {
        return RENDER_STATS;
    }

    // ---- Model Access ----

    /**
     * Load a GLTF model from the model cache.
     *
     * @param modelLocation The resource location of the model
     * @return The RuntimeModel, or null if not loaded
     */
    public static RuntimeModel loadModel(ResourceLocation modelLocation) {
        return GltfModelManager.INSTANCE.getModel(modelLocation);
    }

    /**
     * Check if a model is currently loaded.
     *
     * @param modelLocation the model resource location
     * @return true if loaded
     */
    public static boolean isModelLoaded(ResourceLocation modelLocation) {
        return GltfModelManager.INSTANCE.isModelLoaded(modelLocation);
    }

    // ---- Animation ----

    /**
     * Create an animation controller for a model.
     *
     * @param model The runtime model
     * @return A new AnimationController
     */
    public static AnimationController createAnimationController(RuntimeModel model) {
        return new AnimationController(model);
    }

}
