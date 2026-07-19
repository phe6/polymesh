package dev.phe.polymesh.client;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeNode;
import dev.phe.polymesh.model.RuntimeSkin;
import dev.phe.polymesh.model.RuntimeMaterial;
import dev.phe.polymesh.model.GltfModelManager;
import dev.phe.polymesh.rendering.CpuSkinningPipeline;
import dev.phe.polymesh.rendering.MeshRenderType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * Generic standalone GLTF model renderer for use in any rendering context.
 *
 * <p>Not tied to any specific Minecraft object (item, block, entity). Consumers create
 * an instance for a specific model and call {@link #renderModel} directly.</p>
 */
public class GltfModelRenderer {
    private final ResourceLocation modelLocation;
    protected final CpuSkinningPipeline pipeline;
    protected final AnimationController animationController;
    private double lastAnimationFrameTime = Double.NaN;

    public GltfModelRenderer(ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;

        RuntimeModel model = GltfModelManager.INSTANCE.getModel(modelLocation);
        if (model != null) {
            this.pipeline = new CpuSkinningPipeline(model);
            this.animationController = new AnimationController(model);
        } else {
            this.pipeline = null;
            this.animationController = null;
        }
    }

    public RuntimeModel getModel() {
        return GltfModelManager.INSTANCE.getModel(modelLocation);
    }

    public CpuSkinningPipeline getPipeline() {
        return pipeline;
    }

    public AnimationController getAnimationController() {
        return animationController;
    }

    public void tickAnimation(float partialTick) {
        if (animationController == null || !animationController.isPlaying()) {
            lastAnimationFrameTime = Double.NaN;
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }

        double frameTimeTicks = Minecraft.getInstance().level.getGameTime() + partialTick;
        if (Double.isNaN(lastAnimationFrameTime) || frameTimeTicks < lastAnimationFrameTime) {
            lastAnimationFrameTime = frameTimeTicks;
            return;
        }

        double deltaTicks = frameTimeTicks - lastAnimationFrameTime;
        lastAnimationFrameTime = frameTimeTicks;
        if (deltaTicks > 0.0D) {
            animationController.tick((float) Math.min(deltaTicks / 20.0D, 0.25D));
        }
    }

    public Matrix4f[] computeGlobalTransforms(float partialTick) {
        if (animationController != null) {
            return animationController.computeGlobalTransforms(partialTick);
        }
        // No animation, compute static transforms from the model's default pose
        RuntimeModel model = getModel();
        if (model != null) {
            int nodeCount = model.getNodeCount();
            Matrix4f[] transforms = new Matrix4f[nodeCount];
            // Compute global transforms from local transforms
            for (int i = 0; i < nodeCount; i++) {
                transforms[i] = new Matrix4f(model.getNodes().get(i).getLocalTransform());
            }
            for (int i = 0; i < nodeCount; i++) {
                int parent = model.getNodes().get(i).getParentIndex();
                if (parent >= 0 && parent < nodeCount) {
                    transforms[i] = new Matrix4f(transforms[parent]).mul(transforms[i]);
                }
            }
            return transforms;
        }
        return null;
    }

    public void renderModel(RuntimeModel model, Matrix4f[] globalTransforms,
                            PoseStack.Pose pose,
                            MultiBufferSource buffer, int packedLight,
                            int packedOverlay, int tint) {
        if (model == null || pipeline == null) return;

        pipeline.computeSkinningMatrices(globalTransforms, null);

        for (int nodeIndex = 0; nodeIndex < model.getNodeCount(); nodeIndex++) {
            RuntimeNode node = model.getNodes().get(nodeIndex);
            if (!node.hasMesh()) continue;

            RuntimeSkin skin = node.hasSkin() && node.getSkinIndex() >= 0 && node.getSkinIndex() < model.getSkinCount()
                ? model.getSkins().get(node.getSkinIndex())
                : null;
            Matrix4f nodeTransform = nodeIndex < globalTransforms.length
                ? globalTransforms[nodeIndex]
                : node.getLocalTransform();
            float[] morphWeights = animationController != null
                ? animationController.getNodeMorphWeights(nodeIndex)
                : node.getMorphWeights();

            for (int meshIndex : node.getMeshIndices()) {
                if (meshIndex < 0 || meshIndex >= model.getMeshCount()) continue;

                RuntimeMesh mesh = model.getMeshes().get(meshIndex);
                RuntimeMaterial material = mesh.getMaterialIndex() >= 0 && mesh.getMaterialIndex() < model.getMaterialCount()
                    ? model.getMaterials().get(mesh.getMaterialIndex())
                    : RuntimeMaterial.defaultMaterial();

                ResourceLocation texture = resolveTexture(material);
                var renderType = MeshRenderType.forMaterial(material, texture);
                var vertexConsumer = buffer.getBuffer(renderType);

                pipeline.renderMesh(mesh, globalTransforms, nodeTransform, morphWeights, skin, pose,
                    vertexConsumer, packedLight, packedOverlay, tint);
            }
        }
    }

    protected ResourceLocation getTextureLocation() {
        return modelLocation.withPath("textures/" + modelLocation.getPath() + ".png");
    }

    protected ResourceLocation resolveTexture(RuntimeMaterial material) {
        String diffuseTexture = material.getDiffuseTexture();
        if (diffuseTexture != null && !diffuseTexture.isEmpty()) {
            ResourceLocation resolved = GltfTextureManager.INSTANCE.resolveMaterialTexture(diffuseTexture, modelLocation);
            if (resolved != null) {
                return resolved;
            }
        }
        return getTextureLocation();
    }
}
