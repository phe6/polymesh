package dev.phe.polymesh.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PolymeshLodMetadata {
    private PolymeshLodMetadata() {
    }

    public static List<PolymeshLod> fromModelExtras(RuntimeExtras extras) {
        JsonObject object = extras.asObject();
        if (object == null || !object.has("polymesh") || !object.get("polymesh").isJsonObject()) {
            return List.of();
        }
        JsonObject polymesh = object.getAsJsonObject("polymesh");
        if (!polymesh.has("lods") || !polymesh.get("lods").isJsonArray()) {
            return List.of();
        }

        JsonArray lods = polymesh.getAsJsonArray("lods");
        List<PolymeshLod> result = new ArrayList<>(lods.size());
        for (int i = 0; i < lods.size(); i++) {
            JsonElement element = lods.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject lod = element.getAsJsonObject();
            int level = intValue(lod, "level", i + 1);
            float distance = floatValue(lod, "distance", floatValue(lod, "distanceBlocks", Float.POSITIVE_INFINITY));
            ResourceLocation model = resourceLocation(lod, "model");
            result.add(new PolymeshLod(level, distance, model));
        }
        result.sort(Comparator.comparingInt(PolymeshLod::level));
        return List.copyOf(result);
    }

    public static int nodeLodLevel(RuntimeNode node) {
        JsonObject object = node.getExtras().asObject();
        if (object == null || !object.has("polymesh") || !object.get("polymesh").isJsonObject()) {
            return -1;
        }
        JsonObject polymesh = object.getAsJsonObject("polymesh");
        return intValue(polymesh, "lod", -1);
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static float floatValue(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    private static ResourceLocation resourceLocation(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text != null && !text.isBlank() ? new ResourceLocation(text) : null;
    }
}
