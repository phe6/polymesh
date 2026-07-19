package dev.phe.polymesh.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * Immutable wrapper for glTF {@code extras} metadata.
 */
public final class RuntimeExtras {
    public static final RuntimeExtras EMPTY = new RuntimeExtras(null);

    @Nullable
    private final JsonElement root;

    private RuntimeExtras(@Nullable JsonElement root) {
        this.root = root != null && !root.isJsonNull() ? root.deepCopy() : null;
    }

    public static RuntimeExtras from(@Nullable JsonElement root) {
        return root == null || root.isJsonNull() ? EMPTY : new RuntimeExtras(root);
    }

    public boolean isEmpty() {
        return root == null;
    }

    @Nullable
    public JsonElement raw() {
        return root != null ? root.deepCopy() : null;
    }

    @Nullable
    public JsonObject asObject() {
        return root != null && root.isJsonObject() ? root.getAsJsonObject().deepCopy() : null;
    }

    @Nullable
    public JsonElement get(String key) {
        JsonObject object = asObject();
        JsonElement value = object != null ? object.get(key) : null;
        return value != null ? value.deepCopy() : null;
    }

    @Nullable
    public String getString(String key) {
        JsonElement value = get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    public boolean getBoolean(String key, boolean fallback) {
        JsonElement value = get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    public float getFloat(String key, float fallback) {
        JsonElement value = get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }
}
