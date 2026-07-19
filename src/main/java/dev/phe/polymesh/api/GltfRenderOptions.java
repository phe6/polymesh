package dev.phe.polymesh.api;

import javax.annotation.Nullable;

public final class GltfRenderOptions {
    public enum LodMode {
        AUTO,
        DISABLED,
        FORCE_LEVEL
    }

    /**
     * How the renderer reacts to an active Iris/Oculus shader pack. The fast GPU path draws with
     * raw GL against hardcoded vertex-attribute indices, which a shader pack's extended entity
     * program cannot consume correctly; the CPU vertex-consumer path flows through Iris's own
     * BufferBuilder extension and stays correct. AUTO picks the safe path automatically.
     */
    public enum ShaderCompatMode {
        /** Use the CPU vertex-consumer path while a shader pack is active; GPU path otherwise. */
        AUTO,
        /** Always try the GPU path, even under a shader pack (for debugging the raw-GL path). */
        FORCE_GPU,
        /** Always use the CPU vertex-consumer path, regardless of shader pack. */
        FORCE_CPU
    }

    public static final GltfRenderOptions DEFAULT = builder().build();

    private final float scale;
    private final float animationSpeed;
    private final float animationTransitionSeconds;
    private final int tint;
    @Nullable
    private final String animationClip;
    private final boolean loopAnimation;
    private final boolean strictValidation;
    private final boolean preferGpuStaticMeshes;
    private final boolean preferGpuAnimatedMeshes;
    private final LodMode lodMode;
    private final int forcedLodLevel;
    private final float maxRenderDistance;
    private final ShaderCompatMode shaderCompatMode;

    private GltfRenderOptions(Builder builder) {
        this.scale = builder.scale;
        this.animationSpeed = builder.animationSpeed;
        this.animationTransitionSeconds = builder.animationTransitionSeconds;
        this.tint = builder.tint;
        this.animationClip = builder.animationClip;
        this.loopAnimation = builder.loopAnimation;
        this.strictValidation = builder.strictValidation;
        this.preferGpuStaticMeshes = builder.preferGpuStaticMeshes;
        this.preferGpuAnimatedMeshes = builder.preferGpuAnimatedMeshes;
        this.lodMode = builder.lodMode;
        this.forcedLodLevel = builder.forcedLodLevel;
        this.maxRenderDistance = builder.maxRenderDistance;
        this.shaderCompatMode = builder.shaderCompatMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public float scale() {
        return scale;
    }

    public float animationSpeed() {
        return animationSpeed;
    }

    public float animationTransitionSeconds() {
        return animationTransitionSeconds;
    }

    public int tint() {
        return tint;
    }

    @Nullable
    public String animationClip() {
        return animationClip;
    }

    public boolean loopAnimation() {
        return loopAnimation;
    }

    public boolean strictValidation() {
        return strictValidation;
    }

    public boolean preferGpuStaticMeshes() {
        return preferGpuStaticMeshes;
    }

    public boolean preferGpuAnimatedMeshes() {
        return preferGpuAnimatedMeshes;
    }

    public LodMode lodMode() {
        return lodMode;
    }

    public int forcedLodLevel() {
        return forcedLodLevel;
    }

    public float maxRenderDistance() {
        return maxRenderDistance;
    }

    public ShaderCompatMode shaderCompatMode() {
        return shaderCompatMode;
    }

    @Override
    public String toString() {
        return "GltfRenderOptions{"
            + "scale=" + scale
            + ", animationSpeed=" + animationSpeed
            + ", animationTransitionSeconds=" + animationTransitionSeconds
            + ", tint=0x" + Integer.toHexString(tint)
            + ", animationClip='" + animationClip + '\''
            + ", loopAnimation=" + loopAnimation
            + ", strictValidation=" + strictValidation
            + ", preferGpuStaticMeshes=" + preferGpuStaticMeshes
            + ", preferGpuAnimatedMeshes=" + preferGpuAnimatedMeshes
            + ", lodMode=" + lodMode
            + ", forcedLodLevel=" + forcedLodLevel
            + ", maxRenderDistance=" + maxRenderDistance
            + ", shaderCompatMode=" + shaderCompatMode
            + '}';
    }

    public static final class Builder {
        private float scale = 1.0f;
        private float animationSpeed = 1.0f;
        private float animationTransitionSeconds = 0.0f;
        private int tint = 0xFFFFFFFF;
        @Nullable
        private String animationClip;
        private boolean loopAnimation = true;
        private boolean strictValidation = false;
        private boolean preferGpuStaticMeshes = true;
        private boolean preferGpuAnimatedMeshes = true;
        private LodMode lodMode = LodMode.AUTO;
        private int forcedLodLevel = 0;
        private float maxRenderDistance = Float.POSITIVE_INFINITY;
        private ShaderCompatMode shaderCompatMode = ShaderCompatMode.AUTO;

        private Builder() {
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        /**
         * Playback speed multiplier. 1 is authored speed, 0 freezes playback, and negative values
         * play a looping clip in reverse (matching {@code AnimationController.setSpeedMultiplier}).
         */
        public Builder animationSpeed(float animationSpeed) {
            this.animationSpeed = Float.isFinite(animationSpeed) ? animationSpeed : 1.0f;
            return this;
        }

        public Builder animationTransitionSeconds(float animationTransitionSeconds) {
            this.animationTransitionSeconds = Math.max(0.0f, animationTransitionSeconds);
            return this;
        }

        public Builder tint(int tint) {
            this.tint = tint;
            return this;
        }

        public Builder animationClip(@Nullable String animationClip) {
            this.animationClip = animationClip;
            return this;
        }

        public Builder loopAnimation(boolean loopAnimation) {
            this.loopAnimation = loopAnimation;
            return this;
        }

        public Builder strictValidation(boolean strictValidation) {
            this.strictValidation = strictValidation;
            return this;
        }

        public Builder preferGpuStaticMeshes(boolean preferGpuStaticMeshes) {
            this.preferGpuStaticMeshes = preferGpuStaticMeshes;
            return this;
        }

        public Builder preferGpuAnimatedMeshes(boolean preferGpuAnimatedMeshes) {
            this.preferGpuAnimatedMeshes = preferGpuAnimatedMeshes;
            return this;
        }

        public Builder lodMode(LodMode lodMode) {
            this.lodMode = lodMode != null ? lodMode : LodMode.AUTO;
            return this;
        }

        public Builder forceLodLevel(int forcedLodLevel) {
            this.lodMode = LodMode.FORCE_LEVEL;
            this.forcedLodLevel = Math.max(0, forcedLodLevel);
            return this;
        }

        public Builder maxRenderDistance(float maxRenderDistance) {
            this.maxRenderDistance = maxRenderDistance > 0.0f ? maxRenderDistance : Float.POSITIVE_INFINITY;
            return this;
        }

        public Builder shaderCompatMode(ShaderCompatMode shaderCompatMode) {
            this.shaderCompatMode = shaderCompatMode != null ? shaderCompatMode : ShaderCompatMode.AUTO;
            return this;
        }

        public GltfRenderOptions build() {
            return new GltfRenderOptions(this);
        }
    }
}
