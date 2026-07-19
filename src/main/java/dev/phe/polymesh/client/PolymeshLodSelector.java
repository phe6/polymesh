package dev.phe.polymesh.client;

import dev.phe.polymesh.api.GltfRenderOptions;
import dev.phe.polymesh.api.PolymeshApi;
import dev.phe.polymesh.model.PolymeshLod;
import dev.phe.polymesh.model.RuntimeModel;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class PolymeshLodSelector {
    private PolymeshLodSelector() {
    }

    public static ResourceLocation selectModel(ResourceLocation baseModel, RuntimeModel loadedBaseModel,
                                               GltfRenderOptions options, float distanceBlocks) {
        if (options.lodMode() == GltfRenderOptions.LodMode.DISABLED) {
            return baseModel;
        }
        if (distanceBlocks > options.maxRenderDistance()) {
            PolymeshApi.getRenderStats().recordCulled();
            return null;
        }

        int level = options.lodMode() == GltfRenderOptions.LodMode.FORCE_LEVEL
            ? options.forcedLodLevel()
            : selectLevel(loadedBaseModel.getLods(), distanceBlocks);
        if (level <= 0) {
            return baseModel;
        }

        ResourceLocation fromMetadata = modelForLevel(loadedBaseModel.getLods(), level);
        if (fromMetadata != null) {
            return fromMetadata;
        }

        ResourceLocation[] registered = PolymeshApi.getRegisteredLodModels(baseModel);
        int index = level - 1;
        return index >= 0 && index < registered.length ? registered[index] : baseModel;
    }

    private static int selectLevel(List<PolymeshLod> lods, float distanceBlocks) {
        int selected = 0;
        for (PolymeshLod lod : lods) {
            if (distanceBlocks >= lod.distanceBlocks()) {
                selected = lod.level();
            }
        }
        return selected;
    }

    private static ResourceLocation modelForLevel(List<PolymeshLod> lods, int level) {
        for (PolymeshLod lod : lods) {
            if (lod.level() == level) {
                return lod.model();
            }
        }
        return null;
    }
}
