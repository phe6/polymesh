package dev.phe.polymesh.client;

import dev.phe.polymesh.animation.AnimationController;
import dev.phe.polymesh.api.GltfRenderOptions;
import dev.phe.polymesh.api.PolymeshApi;
import dev.phe.polymesh.model.GltfModelManager;
import dev.phe.polymesh.model.RuntimeModel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GltfItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GltfItemRenderer.class);
    private final ResourceLocation modelLocation;
    private final GltfRenderOptions options;

    // Caches - created lazily on first successful model load to avoid per-frame allocation
    private RuntimeModel cachedModel;
    private AnimationController animationController;
    private PolymeshRenderer renderer;
    private long lastAnimationMillis = -1L;

    /**
     * Build a renderer from the registry populated by
     * {@link dev.phe.polymesh.api.PolymeshApi#registerItemRenderer}. Call this from
     * {@code initializeClient} so the model id and options live in exactly one place.
     * The registration must have happened during client setup, before the item is first rendered;
     * an unregistered item logs an error once and renders the missing-model debug cube.
     */
    public GltfItemRenderer(Item item) {
        this(resolveRegisteredModel(item), PolymeshApi.getItemRenderOptions(item));
    }

    private static ResourceLocation resolveRegisteredModel(Item item) {
        ResourceLocation location = PolymeshApi.getItemModelLocation(item);
        if (location == null) {
            LOGGER.error("No Polymesh model registered for item {}; call PolymeshApi.registerItemRenderer during client setup",
                item);
            return new ResourceLocation("polymesh", "unregistered");
        }
        return location;
    }

    public GltfItemRenderer(ResourceLocation modelLocation, float scale) {
        this(modelLocation, GltfRenderOptions.builder().scale(scale).build());
    }

    public GltfItemRenderer(ResourceLocation modelLocation, GltfRenderOptions options) {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        this.modelLocation = modelLocation;
        this.options = options;
    }

    public GltfItemRenderer(ResourceLocation modelLocation) {
        this(modelLocation, 1.0f);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        RuntimeModel model = GltfModelManager.INSTANCE.getModel(modelLocation);
        if (model == null) {
            clearRuntimeCaches();
            renderDebugCube(poseStack, buffer, packedLight);
            return;
        }

        // Initialize caches if model is now available
        if (cachedModel != model) {
            clearRuntimeCaches();
            cachedModel = model;
            animationController = new AnimationController(model);
            renderer = new PolymeshRenderer(modelLocation, options);
            if (options.animationClip() != null) {
                animationController.play(options.animationClip(), options.loopAnimation(), options.animationTransitionSeconds());
            } else if (model.getAnimationCount() > 0) {
                animationController.play(model.getAnimations().get(0).getName(), options.loopAnimation(), options.animationTransitionSeconds());
            }
        }

        tickAnimationClock();
        Matrix4f[] globalTransforms = animationController.computeGlobalTransforms(1.0f);

        poseStack.pushPose();
        // ItemRenderer applies a vanilla -0.5 block-model offset before calling
        // the custom item renderer. Cancel it in the original item space before
        // any display-context scaling, otherwise the cancellation is scaled too
        // and dropped items orbit around their entity/shadow origin.
        poseStack.translate(0.5, 0.5, 0.5);
        applyDisplayTransform(poseStack, displayContext);
        poseStack.scale(options.scale(), options.scale(), options.scale());
        applyModelBoundsTransform(poseStack, model, displayContext);

        PoseStack.Pose pose = poseStack.last();
        renderer.render(model, globalTransforms, animationController, pose, buffer, packedLight, packedOverlay, options.tint());

        poseStack.popPose();
    }

    private void tickAnimationClock() {
        if (animationController == null || !animationController.isPlaying()) {
            lastAnimationMillis = -1L;
            return;
        }

        long now = Util.getMillis();
        if (lastAnimationMillis < 0L) {
            lastAnimationMillis = now;
            return;
        }

        long elapsedMillis = Math.max(0L, now - lastAnimationMillis);
        lastAnimationMillis = now;
        if (elapsedMillis > 0L) {
            float deltaSeconds = Math.min(elapsedMillis / 1000.0f * options.animationSpeed(), 0.25f);
            animationController.tick(deltaSeconds);
        }
    }

    private void clearRuntimeCaches() {
        cachedModel = null;
        animationController = null;
        lastAnimationMillis = -1L;
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    private void applyDisplayTransform(PoseStack poseStack, ItemDisplayContext context) {
        switch (context) {
            case GROUND:
                poseStack.scale(0.5f, 0.5f, 0.5f);
                break;
            case FIXED:
                poseStack.scale(0.5f, 0.5f, 0.5f);
                break;
            case THIRD_PERSON_LEFT_HAND:
            case THIRD_PERSON_RIGHT_HAND:
                poseStack.translate(0, 0.25, 0);
                poseStack.scale(0.5f, 0.5f, 0.5f);
                break;
            case FIRST_PERSON_LEFT_HAND:
            case FIRST_PERSON_RIGHT_HAND:
                poseStack.scale(0.4f, 0.4f, 0.4f);
                poseStack.translate(0, -0.3, 0);
                break;
            case HEAD:
                poseStack.scale(0.5f, 0.5f, 0.5f);
                poseStack.translate(0, 0.5, 0);
                break;
            default:
                break;
        }
    }

    private void applyModelBoundsTransform(PoseStack poseStack, RuntimeModel model, ItemDisplayContext context) {
        AABB bounds = model.getBoundingBox();
        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double centerY = (bounds.minY + bounds.maxY) * 0.5;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;
        if (context == ItemDisplayContext.GUI) {
            poseStack.translate(-centerX, -centerY, -centerZ);
        } else {
            poseStack.translate(-centerX, -bounds.minY, -centerZ);
        }
    }

    private void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(0.25f, 0.25f, 0.25f);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.debugQuads());
        float min = -0.5f, max = 0.5f;
        int r = 255, g = 0, b = 0;

        // Simple debug cube
        for (int face = 0; face < 6; face++) {
            for (int i = 0; i < 4; i++) {
                float x = (i & 1) == 0 ? min : max;
                float y = (i & 2) == 0 ? min : max;
                float z = face < 2 ? (face == 0 ? min : max) : (face < 4 ? ((i & 1) == 0 ? min : max) : ((i & 2) == 0 ? min : max));
                vertexConsumer.vertex(pose.pose(), x, y, z).color(r, g, b, 255).uv(0, 0).uv2(packedLight).normal(0, 0, 1).endVertex();
            }
        }
        poseStack.popPose();
    }

}
