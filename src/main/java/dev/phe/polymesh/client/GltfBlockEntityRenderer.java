package dev.phe.polymesh.client;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.api.GltfRenderOptions;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.GltfModelManager;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

public abstract class GltfBlockEntityRenderer<T extends net.minecraft.world.level.block.entity.BlockEntity> implements BlockEntityRenderer<T> {
    private static final long INSTANCE_EVICT_AGE_TICKS = 1200L; // 60s unseen -> release controller + GPU resources
    private static final int INSTANCE_SWEEP_INTERVAL = 256;

    protected final ResourceLocation modelLocation;
    protected final float scale;
    protected final GltfRenderOptions options;
    private final Map<BlockPos, RenderInstance> renderInstances = new HashMap<>();
    private int sweepCounter;

    public GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context, ResourceLocation modelLocation) {
        this(context, modelLocation, 1.0f);
    }

    protected GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context, ResourceLocation modelLocation, float scale) {
        this(context, modelLocation, GltfRenderOptions.builder().scale(scale).build());
    }

    protected GltfBlockEntityRenderer(BlockEntityRendererProvider.Context context, ResourceLocation modelLocation, GltfRenderOptions options) {
        this.modelLocation = modelLocation;
        this.options = options != null ? options : GltfRenderOptions.DEFAULT;
        this.scale = this.options.scale();
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        RuntimeModel model = GltfModelManager.INSTANCE.getModel(modelLocation);
        if (model == null) return;
        ResourceLocation selectedModelLocation = PolymeshLodSelector.selectModel(modelLocation, model, options, cameraDistance(blockEntity.getBlockPos()));
        if (selectedModelLocation == null) {
            return;
        }
        if (!selectedModelLocation.equals(modelLocation)) {
            RuntimeModel lodModel = GltfModelManager.INSTANCE.getModel(selectedModelLocation);
            if (lodModel != null) {
                model = lodModel;
            } else {
                selectedModelLocation = modelLocation;
            }
        }

        RenderInstance instance = renderInstances.computeIfAbsent(blockEntity.getBlockPos(), ignored -> new RenderInstance(options));
        if (instance.model != model || !selectedModelLocation.equals(instance.modelLocation)) {
            instance.reset(selectedModelLocation, model);
        }
        if (blockEntity.getLevel() != null) {
            long gameTime = blockEntity.getLevel().getGameTime();
            instance.lastUsedGameTime = gameTime;
            sweepStaleInstances(gameTime);
            instance.tickAnimation(gameTime + partialTick);
        }

        poseStack.pushPose();
        AABB bounds = model.getBoundingBox();
        poseStack.translate(
            0.5 - ((bounds.minX + bounds.maxX) * 0.5) * scale,
            -bounds.minY * scale,
            0.5 - ((bounds.minZ + bounds.maxZ) * 0.5) * scale
        );
        poseStack.scale(scale, scale, scale);

        Matrix4f[] globalTransforms = instance.animationController != null
            ? instance.animationController.computeGlobalTransforms(partialTick)
            : computeStaticGlobalTransforms(model);

        PoseStack.Pose pose = poseStack.last();
        int tint = options.tint();

        instance.renderer.render(model, globalTransforms, instance.animationController, pose, buffer, packedLight, packedOverlay, tint);

        poseStack.popPose();
    }

    public ResourceLocation getTextureLocation(T blockEntity) {
        return modelLocation.withPath("textures/" + modelLocation.getPath() + ".png");
    }

    private Matrix4f[] computeStaticGlobalTransforms(RuntimeModel model) {
        int nodeCount = model.getNodeCount();
        Matrix4f[] transforms = new Matrix4f[nodeCount];
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

    public AnimationController getAnimationController() {
        return renderInstances.values().stream()
            .map(instance -> instance.animationController)
            .findFirst()
            .orElse(null);
    }

    public AnimationController getAnimationController(T blockEntity) {
        RenderInstance instance = renderInstances.get(blockEntity.getBlockPos());
        return instance != null ? instance.animationController : null;
    }

    /**
     * Instances are keyed by block position and were never released when block entities were
     * removed, leaking animation controllers and per-instance GPU resources. Sweep occasionally
     * and close anything not rendered for a while (broken block, chunk unload, dimension change).
     */
    private void sweepStaleInstances(long gameTime) {
        if (++sweepCounter < INSTANCE_SWEEP_INTERVAL) {
            return;
        }
        sweepCounter = 0;
        renderInstances.entrySet().removeIf(entry -> {
            RenderInstance candidate = entry.getValue();
            if (gameTime - candidate.lastUsedGameTime <= INSTANCE_EVICT_AGE_TICKS) {
                return false;
            }
            if (candidate.renderer != null) {
                candidate.renderer.close();
            }
            return true;
        });
    }

    private static float cameraDistance(BlockPos pos) {
        if (Minecraft.getInstance().cameraEntity == null) {
            return 0.0f;
        }
        Vec3 camera = Minecraft.getInstance().cameraEntity.position();
        return (float) camera.distanceTo(Vec3.atCenterOf(pos));
    }

    private static final class RenderInstance {
        private final GltfRenderOptions options;
        private ResourceLocation modelLocation;
        private RuntimeModel model;
        private AnimationController animationController;
        private PolymeshRenderer renderer;
        private double lastAnimationFrameTime = Double.NaN;
        private long lastUsedGameTime;

        private RenderInstance(GltfRenderOptions options) {
            this.options = options;
        }

        private void reset(ResourceLocation modelLocation, RuntimeModel model) {
            if (renderer != null) {
                renderer.close();
            }
            this.modelLocation = modelLocation;
            this.model = model;
            this.animationController = new AnimationController(model);
            this.renderer = new PolymeshRenderer(modelLocation, options);
            this.lastAnimationFrameTime = Double.NaN;
            if (options.animationClip() != null) {
                animationController.play(options.animationClip(), options.loopAnimation(), options.animationTransitionSeconds());
            } else if (model.getAnimationCount() > 0) {
                animationController.play(model.getAnimations().get(0).getName(), options.loopAnimation(), options.animationTransitionSeconds());
            }
        }

        private void tickAnimation(double frameTimeTicks) {
            if (animationController == null || !animationController.isPlaying()) {
                lastAnimationFrameTime = Double.NaN;
                return;
            }
            if (Double.isNaN(lastAnimationFrameTime) || frameTimeTicks < lastAnimationFrameTime) {
                lastAnimationFrameTime = frameTimeTicks;
                return;
            }

            double deltaTicks = frameTimeTicks - lastAnimationFrameTime;
            lastAnimationFrameTime = frameTimeTicks;
            if (deltaTicks <= 0.0D) {
                return;
            }

            float deltaSeconds = (float) Math.min((deltaTicks / 20.0D) * options.animationSpeed(), 0.25D);
            animationController.tick(deltaSeconds);
        }
    }
}
