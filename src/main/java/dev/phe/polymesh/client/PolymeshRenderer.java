package dev.phe.polymesh.client;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.api.GltfRenderOptions;
import dev.phe.polymesh.api.PolymeshApi;
import dev.phe.polymesh.compat.IrisCompat;
import dev.phe.polymesh.model.RuntimeMaterial;
import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.RuntimeNode;
import dev.phe.polymesh.model.RuntimeSkin;
import dev.phe.polymesh.rendering.CpuSkinningPipeline;
import dev.phe.polymesh.rendering.MeshRenderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared render backend for Polymesh item, entity, and block-entity renderers.
 */
public final class PolymeshRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("polymesh");
    private static boolean loggedShaderFallback;

    private final ResourceLocation modelLocation;
    private final GltfRenderOptions options;
    private CpuSkinningPipeline cpuPipeline;
    private GltfAnimatedGpuModel animatedGpuModel;

    private static void logShaderFallbackOnce() {
        if (!loggedShaderFallback) {
            loggedShaderFallback = true;
            LOGGER.info("Polymesh: shader pack detected, using vertex-consumer path for compatibility.");
        }
    }

    public PolymeshRenderer(ResourceLocation modelLocation, GltfRenderOptions options) {
        this.modelLocation = modelLocation;
        this.options = options != null ? options : GltfRenderOptions.DEFAULT;
    }

    public void render(RuntimeModel model, Matrix4f[] globalTransforms, AnimationController animationController,
                       PoseStack.Pose pose, MultiBufferSource buffer, int packedLight,
                       int packedOverlay, int tint) {
        if (model == null || globalTransforms == null) {
            return;
        }

        PolymeshApi.getRenderStats().recordVisible(0);
        if (!tryRenderGpuModel(model, globalTransforms, animationController, pose, packedLight, packedOverlay)) {
            renderCpuModel(model, globalTransforms, animationController, pose, buffer, packedLight, packedOverlay, tint);
        }
    }

    private boolean tryRenderGpuModel(RuntimeModel model, Matrix4f[] globalTransforms,
                                      AnimationController animationController, PoseStack.Pose pose,
                                      int packedLight, int packedOverlay) {
        if (!options.preferGpuStaticMeshes() && !options.preferGpuAnimatedMeshes()) {
            return false;
        }
        // Under an Iris/Oculus shader pack, the raw-GL GPU path draws against the pack's extended
        // entity program with the wrong vertex layout; fall back to the vertex-consumer (CPU) path,
        // which Iris extends correctly and which preserves our smooth per-vertex normals. Mirrors
        // the BlazeRod library's renderer switching. FORCE_GPU/FORCE_CPU override for debugging.
        switch (options.shaderCompatMode()) {
            case FORCE_CPU:
                return false;
            case FORCE_GPU:
                break;
            case AUTO:
            default:
                if (IrisCompat.shaderPackInUse()) {
                    logShaderFallbackOnce();
                    return false;
                }
                break;
        }

        GltfGpuModelCache.SharedGpuModel gpuModel = GltfGpuModelCache.INSTANCE.getOrCreate(model);
        if (gpuModel.isFullyStatic() && options.preferGpuStaticMeshes()) {
            return renderStaticGpuModel(model, globalTransforms, pose, packedLight, packedOverlay, gpuModel);
        }

        if (!options.preferGpuAnimatedMeshes()) {
            return false;
        }
        return renderAnimatedGpuModel(model, globalTransforms, animationController, pose, packedLight, packedOverlay);
    }

    private boolean renderStaticGpuModel(RuntimeModel model, Matrix4f[] globalTransforms,
                                         PoseStack.Pose pose, int packedLight, int packedOverlay,
                                         GltfGpuModelCache.SharedGpuModel gpuModel) {
        Matrix4f baseModelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        for (int nodeIndex = 0; nodeIndex < model.getNodeCount(); nodeIndex++) {
            RuntimeNode node = model.getNodes().get(nodeIndex);
            if (!node.hasMesh() || isZeroScale(node)) {
                continue;
            }

            Matrix4f nodeTransform = nodeIndex < globalTransforms.length
                ? globalTransforms[nodeIndex]
                : node.getLocalTransform();
            for (int meshIndex : node.getMeshIndices()) {
                if (meshIndex < 0 || meshIndex >= model.getMeshCount()) {
                    continue;
                }

                SharedGpuMesh gpuMesh = gpuModel.getMesh(meshIndex);
                if (gpuMesh == null) {
                    return false;
                }
                RuntimeMesh mesh = model.getMeshes().get(meshIndex);
                RuntimeMaterial material = materialFor(model, mesh);
                RenderType renderType = MeshRenderType.forMaterial(material, resolveTexture(material));
                Matrix4f modelView = new Matrix4f(baseModelView).mul(nodeTransform);
                renderGpuMesh(gpuMesh, renderType, material, modelView, packedLight, packedOverlay);
            }
        }
        return true;
    }

    private boolean renderAnimatedGpuModel(RuntimeModel model, Matrix4f[] globalTransforms,
                                           AnimationController animationController, PoseStack.Pose pose,
                                           int packedLight, int packedOverlay) {
        if (animatedGpuModel == null || !animatedGpuModel.owns(model)) {
            if (animatedGpuModel != null) {
                animatedGpuModel.close();
            }
            animatedGpuModel = new GltfAnimatedGpuModel(model);
        }

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        int poseVersion = animationController != null ? animationController.getPoseVersion() : 0;
        for (int nodeIndex = 0; nodeIndex < model.getNodeCount(); nodeIndex++) {
            RuntimeNode node = model.getNodes().get(nodeIndex);
            if (!node.hasMesh() || isZeroScale(node)) {
                continue;
            }

            RuntimeSkin skin = skinFor(model, node);
            Matrix4f nodeTransform = nodeIndex < globalTransforms.length
                ? globalTransforms[nodeIndex]
                : node.getLocalTransform();
            float[] morphWeights = animationController != null
                ? animationController.getNodeMorphWeights(nodeIndex)
                : node.getMorphWeights();

            for (int meshIndex : node.getMeshIndices()) {
                if (meshIndex < 0 || meshIndex >= model.getMeshCount()) {
                    continue;
                }

                RuntimeMesh mesh = model.getMeshes().get(meshIndex);
                SharedGpuMesh gpuMesh = animatedGpuModel.getOrUpload(
                    nodeIndex, meshIndex, mesh, globalTransforms, nodeTransform, morphWeights, skin, poseVersion);
                if (gpuMesh == null) {
                    return false;
                }
                RuntimeMaterial material = materialFor(model, mesh);
                RenderType renderType = MeshRenderType.forMaterial(material, resolveTexture(material));
                renderGpuMesh(gpuMesh, renderType, material, modelView, packedLight, packedOverlay);
            }
        }
        return true;
    }

    private void renderGpuMesh(SharedGpuMesh gpuMesh, RenderType renderType, RuntimeMaterial material,
                               Matrix4f modelView, int packedLight, int packedOverlay) {
        int previousTexture0 = RenderSystem.getShaderTexture(0);
        renderType.setupRenderState();
        try {
            bindMaterialTextures(material);
            gpuMesh.draw(modelView, packedLight, packedOverlay);
            PolymeshApi.getRenderStats().recordDrawCall();
        } finally {
            renderType.clearRenderState();
            RenderSystem.setShaderTexture(0, previousTexture0);
        }
    }

    private void renderCpuModel(RuntimeModel model, Matrix4f[] globalTransforms,
                                AnimationController animationController, PoseStack.Pose pose,
                                MultiBufferSource buffer, int packedLight, int packedOverlay, int tint) {
        if (cpuPipeline == null) {
            cpuPipeline = new CpuSkinningPipeline(model);
        }

        for (int nodeIndex = 0; nodeIndex < model.getNodeCount(); nodeIndex++) {
            RuntimeNode node = model.getNodes().get(nodeIndex);
            if (!node.hasMesh() || isZeroScale(node)) {
                continue;
            }

            RuntimeSkin skin = skinFor(model, node);
            Matrix4f nodeTransform = nodeIndex < globalTransforms.length
                ? globalTransforms[nodeIndex]
                : node.getLocalTransform();
            float[] morphWeights = animationController != null
                ? animationController.getNodeMorphWeights(nodeIndex)
                : node.getMorphWeights();

            for (int meshIndex : node.getMeshIndices()) {
                if (meshIndex < 0 || meshIndex >= model.getMeshCount()) {
                    continue;
                }

                RuntimeMesh mesh = model.getMeshes().get(meshIndex);
                RuntimeMaterial material = materialFor(model, mesh);
                RenderType renderType = MeshRenderType.forMaterial(material, resolveTexture(material));
                cpuPipeline.renderMesh(mesh, globalTransforms, nodeTransform, morphWeights, skin, pose,
                    buffer.getBuffer(renderType), packedLight, packedOverlay, tint);
                PolymeshApi.getRenderStats().recordDrawCall();
            }
        }
    }

    private void bindMaterialTextures(RuntimeMaterial material) {
        RenderSystem.setShaderTexture(0, resolveTexture(material));
    }

    public ResourceLocation resolveTexture(RuntimeMaterial material) {
        ResourceLocation resolved = resolveOptionalTexture(material.getDiffuseTexture());
        return resolved != null ? resolved : modelLocation.withPath("textures/" + modelLocation.getPath() + ".png");
    }

    private ResourceLocation resolveOptionalTexture(String texture) {
        if (texture == null || texture.isEmpty()) {
            return null;
        }
        return GltfTextureManager.INSTANCE.resolveMaterialTexture(texture, modelLocation);
    }

    private static RuntimeMaterial materialFor(RuntimeModel model, RuntimeMesh mesh) {
        return mesh.getMaterialIndex() >= 0 && mesh.getMaterialIndex() < model.getMaterialCount()
            ? model.getMaterials().get(mesh.getMaterialIndex())
            : RuntimeMaterial.defaultMaterial();
    }

    private static RuntimeSkin skinFor(RuntimeModel model, RuntimeNode node) {
        return node.hasSkin() && node.getSkinIndex() >= 0 && node.getSkinIndex() < model.getSkinCount()
            ? model.getSkins().get(node.getSkinIndex())
            : null;
    }

    private static boolean isZeroScale(RuntimeNode node) {
        org.joml.Vector3f scale = node.getScale();
        return Math.abs(scale.x) < 0.000001f
            || Math.abs(scale.y) < 0.000001f
            || Math.abs(scale.z) < 0.000001f;
    }

    @Override
    public void close() {
        if (animatedGpuModel != null) {
            animatedGpuModel.close();
            animatedGpuModel = null;
        }
        cpuPipeline = null;
    }
}
