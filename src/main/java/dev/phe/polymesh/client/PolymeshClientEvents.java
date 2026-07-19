package dev.phe.polymesh.client;

import dev.phe.polymesh.model.GltfModelManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side event registration helper.
 *
 * <p>Not intended for direct use by library consumers. Mods using this library
 * should register their own renderers through their own event handlers.</p>
 */
public class PolymeshClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolymeshClientEvents.class);

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(PolymeshClientEvents::registerReloadListeners);
    }

    private static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PolymeshReloadListener());
        LOGGER.debug("Registered Polymesh client reload listener");
    }

    private static void reloadClientResources(ResourceManager resourceManager) {
        GltfGpuModelCache.INSTANCE.closeAll();
        GltfTextureManager.INSTANCE.loadTextures(resourceManager);
        GltfModelManager.INSTANCE.reload(resourceManager);
    }

    private static final class PolymeshReloadListener implements ResourceManagerReloadListener {
        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
            reloadClientResources(resourceManager);
        }
    }
}
