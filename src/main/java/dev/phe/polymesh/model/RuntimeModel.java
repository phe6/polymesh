package dev.phe.polymesh.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import org.joml.Matrix4f;

import net.minecraft.world.phys.AABB;

public class RuntimeModel {
    private final List<RuntimeNode> nodes;
    private final List<RuntimeMesh> meshes;
    private final List<RuntimeSkin> skins;
    private final List<RuntimeAnimation> animations;
    private final List<RuntimeMaterial> materials;
    private final AABB boundingBox;
    private final RuntimeExtras extras;
    private final List<String> extensionsUsed;
    private final List<String> extensionsRequired;
    private final List<PolymeshLod> lods;
    private final Map<String, RuntimeAnimation> animationMap;

    public RuntimeModel(List<RuntimeNode> nodes, List<RuntimeMesh> meshes,
                       List<RuntimeSkin> skins, List<RuntimeAnimation> animations,
                       List<RuntimeMaterial> materials, AABB boundingBox) {
        this(nodes, meshes, skins, animations, materials, boundingBox, RuntimeExtras.EMPTY, List.of(), List.of());
    }

    public RuntimeModel(List<RuntimeNode> nodes, List<RuntimeMesh> meshes,
                       List<RuntimeSkin> skins, List<RuntimeAnimation> animations,
                       List<RuntimeMaterial> materials, AABB boundingBox,
                       RuntimeExtras extras, List<String> extensionsUsed, List<String> extensionsRequired) {
        this.nodes = nodes;
        this.meshes = meshes;
        this.skins = skins;
        this.animations = animations;
        this.materials = materials;
        this.boundingBox = boundingBox;
        this.extras = extras != null ? extras : RuntimeExtras.EMPTY;
        this.extensionsUsed = List.copyOf(extensionsUsed != null ? extensionsUsed : List.of());
        this.extensionsRequired = List.copyOf(extensionsRequired != null ? extensionsRequired : List.of());
        this.lods = PolymeshLodMetadata.fromModelExtras(this.extras);
        this.animationMap = new HashMap<>();
        for (RuntimeAnimation anim : animations) {
            this.animationMap.put(anim.getName(), anim);
        }
    }

    public List<RuntimeNode> getNodes() { return nodes; }
    public List<RuntimeMesh> getMeshes() { return meshes; }
    public List<RuntimeSkin> getSkins() { return skins; }
    public List<RuntimeAnimation> getAnimations() { return animations; }
    public List<RuntimeMaterial> getMaterials() { return materials; }
    public AABB getBoundingBox() { return boundingBox; }
    public RuntimeExtras getExtras() { return extras; }
    public List<String> getExtensionsUsed() { return extensionsUsed; }
    public List<String> getExtensionsRequired() { return extensionsRequired; }
    public List<PolymeshLod> getLods() { return lods; }

    @Nullable
    public RuntimeAnimation getAnimation(String name) {
        return animationMap.get(name);
    }

    public int getNodeCount() { return nodes.size(); }
    public int getMeshCount() { return meshes.size(); }
    public int getSkinCount() { return skins.size(); }
    public int getAnimationCount() { return animations.size(); }
    public int getMaterialCount() { return materials.size(); }

    public int getNodeIndex(String nodeName) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getName().equals(nodeName)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    public RuntimeNode getNode(String nodeName) {
        int index = getNodeIndex(nodeName);
        return index >= 0 ? nodes.get(index) : null;
    }

    public int getMorphTargetIndex(int meshIndex, String targetName) {
        if (meshIndex < 0 || meshIndex >= meshes.size()) {
            return -1;
        }
        return meshes.get(meshIndex).getMorphTargetIndex(targetName);
    }
}
