package dev.phe.polymesh.model;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class RuntimeNode {
    private final String name;
    private final int index;
    private final int[] children;
    private final int[] meshIndices;
    private final int meshIndex;
    private final int skinIndex;
    private final Matrix4f localTransform;
    private final int parentIndex;
    private final Vector3f translation;
    private final Quaternionf rotation;
    private final Vector3f scale;
    private final float[] morphWeights;
    private final RuntimeExtras extras;

    public RuntimeNode(String name, int index, int[] children, int meshIndex,
                      int skinIndex, Matrix4f localTransform, int parentIndex) {
        this(name, index, children, meshIndex, skinIndex, localTransform, parentIndex,
            extractTranslation(localTransform), extractRotation(localTransform), extractScale(localTransform), null,
            RuntimeExtras.EMPTY);
    }

    public RuntimeNode(String name, int index, int[] children, int meshIndex,
                      int skinIndex, Matrix4f localTransform, int parentIndex,
                      Vector3f translation, Quaternionf rotation, Vector3f scale, float[] morphWeights) {
        this(name, index, children, meshIndex, skinIndex, localTransform, parentIndex,
            translation, rotation, scale, morphWeights, RuntimeExtras.EMPTY);
    }

    public RuntimeNode(String name, int index, int[] children, int meshIndex,
                      int skinIndex, Matrix4f localTransform, int parentIndex,
                      Vector3f translation, Quaternionf rotation, Vector3f scale,
                      float[] morphWeights, RuntimeExtras extras) {
        this(name, index, children, meshIndex >= 0 ? new int[] { meshIndex } : new int[0],
            skinIndex, localTransform, parentIndex, translation, rotation, scale, morphWeights, extras);
    }

    public RuntimeNode(String name, int index, int[] children, int[] meshIndices,
                      int skinIndex, Matrix4f localTransform, int parentIndex,
                      Vector3f translation, Quaternionf rotation, Vector3f scale, float[] morphWeights) {
        this(name, index, children, meshIndices, skinIndex, localTransform, parentIndex,
            translation, rotation, scale, morphWeights, RuntimeExtras.EMPTY);
    }

    public RuntimeNode(String name, int index, int[] children, int[] meshIndices,
                      int skinIndex, Matrix4f localTransform, int parentIndex,
                      Vector3f translation, Quaternionf rotation, Vector3f scale,
                      float[] morphWeights, RuntimeExtras extras) {
        this.name = name;
        this.index = index;
        this.children = children;
        this.meshIndices = meshIndices != null ? meshIndices.clone() : new int[0];
        this.meshIndex = this.meshIndices.length > 0 ? this.meshIndices[0] : -1;
        this.skinIndex = skinIndex;
        this.localTransform = localTransform;
        this.parentIndex = parentIndex;
        this.translation = new Vector3f(translation);
        this.rotation = new Quaternionf(rotation);
        this.scale = new Vector3f(scale);
        this.morphWeights = morphWeights != null ? morphWeights.clone() : null;
        this.extras = extras != null ? extras : RuntimeExtras.EMPTY;
    }

    public String getName() { return name; }
    public int getIndex() { return index; }
    public int[] getChildren() { return children; }
    public int[] getMeshIndices() { return meshIndices.clone(); }
    public int getMeshIndex() { return meshIndex; }
    public int getSkinIndex() { return skinIndex; }
    public Matrix4f getLocalTransform() { return localTransform; }
    public int getParentIndex() { return parentIndex; }
    public Vector3f getTranslation() { return new Vector3f(translation); }
    public Quaternionf getRotation() { return new Quaternionf(rotation); }
    public Vector3f getScale() { return new Vector3f(scale); }
    public float[] getMorphWeights() { return morphWeights != null ? morphWeights.clone() : null; }
    public RuntimeExtras getExtras() { return extras; }

    public boolean hasMesh() { return meshIndices.length > 0; }
    public boolean hasSkin() { return skinIndex >= 0; }
    public boolean hasChildren() { return children != null && children.length > 0; }

    private static Vector3f extractTranslation(Matrix4f transform) {
        return transform.getTranslation(new Vector3f());
    }

    private static Quaternionf extractRotation(Matrix4f transform) {
        return transform.getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    private static Vector3f extractScale(Matrix4f transform) {
        return transform.getScale(new Vector3f());
    }
}
