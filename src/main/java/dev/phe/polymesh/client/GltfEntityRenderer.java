package dev.phe.polymesh.client;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.api.GltfRenderOptions;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.GltfModelManager;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

public abstract class GltfEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    private static final long INSTANCE_EVICT_AGE_TICKS = 1200L; // 60s unseen -> release controller + GPU resources
    private static final int INSTANCE_SWEEP_INTERVAL = 256;

    protected final ResourceLocation modelLocation;
    protected final float scale;
    protected final GltfRenderOptions options;
    private final Map<Integer, RenderInstance> renderInstances = new HashMap<>();
    private int sweepCounter;

    public GltfEntityRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation) {
        this(context, modelLocation, 1.0f);
    }

    protected GltfEntityRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation, float scale) {
        this(context, modelLocation, GltfRenderOptions.builder().scale(scale).build());
    }

    protected GltfEntityRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation, GltfRenderOptions options) {
        super(context);
        this.modelLocation = modelLocation;
        this.options = options != null ? options : GltfRenderOptions.DEFAULT;
        this.scale = this.options.scale();
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        RuntimeModel model = GltfModelManager.INSTANCE.getModel(modelLocation);
        if (model == null) return;
        ResourceLocation selectedModelLocation = PolymeshLodSelector.selectModel(modelLocation, model, options, cameraDistance(entity));
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

        long gameTime = entity.level().getGameTime();
        RenderInstance instance = renderInstances.computeIfAbsent(entity.getId(), ignored -> new RenderInstance(options));
        if (instance.model != model || !selectedModelLocation.equals(instance.modelLocation)) {
            instance.reset(selectedModelLocation, model);
        }
        instance.lastUsedGameTime = gameTime;
        sweepStaleInstances(gameTime);
        instance.tickAnimation(gameTime + partialTick);

        poseStack.pushPose();
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-renderYawDegrees(entity, entityYaw, partialTick)));

        AABB bounds = model.getBoundingBox();
        poseStack.translate(
            -((bounds.minX + bounds.maxX) * 0.5) * scale,
            -bounds.minY * scale,
            -((bounds.minZ + bounds.maxZ) * 0.5) * scale
        );
        poseStack.scale(scale, scale, scale);

        Matrix4f[] globalTransforms = instance.animationController != null
            ? instance.animationController.computeGlobalTransforms(partialTick)
            : computeStaticGlobalTransforms(model);

        PoseStack.Pose pose = poseStack.last();
        int tint = options.tint();
        int packedOverlay = OverlayTexture.NO_OVERLAY;

        instance.renderer.render(model, globalTransforms, instance.animationController, pose, buffer, packedLight, packedOverlay, tint);

        poseStack.popPose();
    }

    /**
     * Yaw (degrees) used to orient the model this frame. For living entities this is the
     * partialTick-interpolated body yaw ({@code yBodyRot}), matching vanilla mob rendering: the
     * body trails the head instead of snapping to the raw mob look yaw ({@code getYRot()}), and
     * interpolation removes the 20 Hz stepping. Subclasses may override to smooth or clamp further.
     */
    protected float renderYawDegrees(T entity, float entityYaw, float partialTick) {
        if (entity instanceof LivingEntity living) {
            return Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
        }
        return entityYaw;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
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

    public AnimationController getAnimationController(T entity) {
        RenderInstance instance = renderInstances.get(entity.getId());
        return instance != null ? instance.animationController : null;
    }

    private static float cameraDistance(Entity entity) {
        Entity cameraEntity = Minecraft.getInstance().cameraEntity;
        return cameraEntity != null ? entity.distanceTo(cameraEntity) : 0.0f;
    }

    /**
     * Instances are keyed by entity id and were never released when entities unloaded, leaking
     * animation controllers and per-instance renderer resources. Sweep occasionally and close
     * anything not rendered for a while (despawned, dimension change, or long out of view).
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
