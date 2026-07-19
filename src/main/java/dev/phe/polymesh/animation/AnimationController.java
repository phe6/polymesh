package dev.phe.polymesh.animation;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.phe.polymesh.model.RuntimeAnimation;
import dev.phe.polymesh.model.RuntimeChannel;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.RuntimeNode;

import javax.annotation.Nullable;

public class AnimationController {
    private final RuntimeModel model;
    private final Matrix4f[] scratchGlobalTransforms;
    private final Quaternionf q0Scratch = new Quaternionf();
    private final Quaternionf q1Scratch = new Quaternionf();
    private final Quaternionf qResultScratch = new Quaternionf();
    private final Vector3f scratchVec3 = new Vector3f();
    private final Matrix4f scratchMatrix = new Matrix4f();
    private final boolean[] nodeComputed;
    private final Matrix4f[] localAnimatedTransforms;
    private final Vector3f[] animatedTranslations;
    private final Quaternionf[] animatedRotations;
    private final Vector3f[] animatedScales;
    private final float[][] animatedMorphWeights;
    private final Vector3f[] transitionTranslations;
    private final Quaternionf[] transitionRotations;
    private final Vector3f[] transitionScales;
    private final float[][] transitionMorphWeights;
    private final Map<String, Integer> nodeNameToIndex = new HashMap<>();
    private final Map<Integer, PoseOverride> poseOverrides = new HashMap<>();
    private final Map<Integer, float[]> morphOverrides = new HashMap<>();
    private final Map<Integer, Quaternionf> lookAtRotations = new HashMap<>();
    // Last playback time per clip, so re-entering a LOOPING clip resumes its phase instead of
    // restarting at frame 0 (rapid idle<->walk flips otherwise keep pinning the same keyframe).
    private final Map<RuntimeAnimation, Float> clipResumeTimes = new HashMap<>();

    @Nullable
    private RuntimeAnimation currentAnimation;
    @Nullable
    private RuntimeAnimation transitionFromAnimation;
    private float animationTime = 0f;
    private float transitionFromTime = 0f;
    private float transitionTime = 0f;
    private float transitionDuration = 0f;
    private boolean transitionFromLooping = false;
    // When true, the transition source is a frozen pose snapshot held in the transition* arrays
    // (used when a transition is interrupted by a new play(): fading from the visible blended pose
    // instead of the raw previous clip prevents a visual pop).
    private boolean transitionFromSnapshot = false;
    private float speedMultiplier = 1.0f;
    private boolean looping = false;
    private boolean playing = false;
    private int poseVersion = 0;

    public AnimationController(RuntimeModel model) {
        this.model = model;
        int nodeCount = model.getNodeCount();
        this.scratchGlobalTransforms = new Matrix4f[nodeCount];
        this.localAnimatedTransforms = new Matrix4f[nodeCount];
        this.animatedTranslations = new Vector3f[nodeCount];
        this.animatedRotations = new Quaternionf[nodeCount];
        this.animatedScales = new Vector3f[nodeCount];
        this.animatedMorphWeights = new float[nodeCount][];
        this.transitionTranslations = new Vector3f[nodeCount];
        this.transitionRotations = new Quaternionf[nodeCount];
        this.transitionScales = new Vector3f[nodeCount];
        this.transitionMorphWeights = new float[nodeCount][];
        this.nodeComputed = new boolean[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            scratchGlobalTransforms[i] = new Matrix4f();
            localAnimatedTransforms[i] = new Matrix4f();
            animatedTranslations[i] = new Vector3f();
            animatedRotations[i] = new Quaternionf();
            animatedScales[i] = new Vector3f();
            transitionTranslations[i] = new Vector3f();
            transitionRotations[i] = new Quaternionf();
            transitionScales[i] = new Vector3f();
            String nodeName = model.getNodes().get(i).getName();
            if (nodeName != null && !nodeName.isBlank()) {
                nodeNameToIndex.putIfAbsent(nodeName, i);
            }
        }
    }

    public void play(String animationName, boolean loop) {
        play(animationName, loop, 0.0f);
    }

    public void play(String animationName, boolean loop, float transitionSeconds) {
        play(animationName, loop, transitionSeconds, false);
    }

    /**
     * @param freezeTransitionSource when true, the transition fades from a frozen snapshot of the
     *        pose currently on screen instead of continuing to advance the outgoing clip. Use for
     *        moving→stationary switches (e.g. walk→idle): the limbs stop with the body instead of
     *        cycling through the fade.
     */
    public void play(String animationName, boolean loop, float transitionSeconds, boolean freezeTransitionSource) {
        RuntimeAnimation anim = model.getAnimation(animationName);
        if (anim != null) {
            if (anim == currentAnimation && playing) {
                looping = loop;
                return;
            }

            float blendDuration = Math.max(0.0f, transitionSeconds);
            if (blendDuration > 0.0f && currentAnimation != null) {
                if (freezeTransitionSource || isTransitioning()) {
                    captureCurrentPoseAsTransitionSource();
                    transitionFromAnimation = null;
                    transitionFromSnapshot = true;
                } else {
                    transitionFromAnimation = currentAnimation;
                    transitionFromTime = animationTime;
                    transitionFromLooping = looping;
                    transitionFromSnapshot = false;
                }
                transitionTime = 0.0f;
                transitionDuration = blendDuration;
            } else {
                clearTransition();
            }
            if (currentAnimation != null) {
                clipResumeTimes.put(currentAnimation, animationTime);
            }
            currentAnimation = anim;
            // Looping clips resume their stored phase; one-shot clips always start from the top.
            animationTime = loop ? clipResumeTimes.getOrDefault(anim, 0f) : 0f;
            looping = loop;
            playing = true;
            poseVersion++;
        }
    }

    public void stop() {
        if (playing) {
            playing = false;
            clearTransition();
            poseVersion++;
        }
    }

    public void tick(float deltaSeconds) {
        if (!playing || currentAnimation == null) return;
        if (deltaSeconds <= 0.0f) return;
        // speedMultiplier scales how fast the CLIPS advance. The transition clock below runs on the
        // real clock: scaling it too meant a near-zero multiplier could pin a crossfade mid-blend
        // for seconds, freezing the model on the fade's snapshot pose.
        float clipDelta = deltaSeconds * speedMultiplier;

        float previousTime = animationTime;
        float previousTransitionTime = transitionTime;
        boolean previousPlaying = playing;
        animationTime += clipDelta;
        float duration = currentAnimation.getDuration();

        if (looping && duration > 0) {
            // Floor-mod so reverse playback (negative clipDelta) wraps correctly.
            animationTime = ((animationTime % duration) + duration) % duration;
        } else if (animationTime < 0.0f) {
            animationTime = 0.0f;
        } else if (animationTime > duration) {
            animationTime = duration;
            playing = false;
        }

        if ((transitionFromAnimation != null || transitionFromSnapshot) && transitionDuration > 0.0f) {
            if (transitionFromAnimation != null) {
                transitionFromTime = advanceTime(
                    transitionFromAnimation,
                    transitionFromTime,
                    clipDelta,
                    transitionFromLooping
                );
            }
            transitionTime += deltaSeconds;
            if (transitionTime >= transitionDuration) {
                clearTransition();
            }
        }
        if (previousTime != animationTime || previousTransitionTime != transitionTime || previousPlaying != playing) {
            poseVersion++;
        }
    }

    public Matrix4f[] computeGlobalTransforms(float partialTick) {
        RuntimeModel localModel = this.model;
        int nodeCount = localModel.getNodeCount();

        // Reset computed flags per-frame
        for (int i = 0; i < nodeCount; i++) {
            nodeComputed[i] = false;
        }

        resetPoseArrays(animatedTranslations, animatedRotations, animatedScales, animatedMorphWeights);

        // Apply animation if playing
        if (currentAnimation != null) {
            if (isTransitioning()) {
                if (!transitionFromSnapshot) {
                    resetPoseArrays(transitionTranslations, transitionRotations, transitionScales, transitionMorphWeights);
                    applyAnimationToPose(
                        transitionFromAnimation,
                        transitionFromTime,
                        transitionTranslations,
                        transitionRotations,
                        transitionScales,
                        transitionMorphWeights
                    );
                }
                applyAnimationToPose(
                    currentAnimation,
                    animationTime,
                    animatedTranslations,
                    animatedRotations,
                    animatedScales,
                    animatedMorphWeights
                );
                blendTransitionPose(smoothStep(clamp(transitionTime / transitionDuration, 0.0f, 1.0f)));
            } else {
                applyAnimationToNodes(currentAnimation, animationTime);
            }
        }

        applyPoseOverrides();
        applyMorphOverrides();
        rebuildLocalTransforms(nodeCount);

        // Compute global transforms from root down
        for (int i = 0; i < nodeCount; i++) {
            computeNodeGlobalTransform(i, localModel);
        }

        return scratchGlobalTransforms;
    }

    private void resetPoseArrays(Vector3f[] translations, Quaternionf[] rotations,
                                 Vector3f[] scales, float[][] morphWeights) {
        for (int i = 0; i < model.getNodeCount(); i++) {
            RuntimeNode node = model.getNodes().get(i);
            translations[i].set(node.getTranslation());
            rotations[i].set(node.getRotation());
            scales[i].set(node.getScale());
            float[] nodeWeights = node.getMorphWeights();
            morphWeights[i] = nodeWeights != null ? nodeWeights.clone() : null;
        }
    }

    private void blendTransitionPose(float alpha) {
        for (int i = 0; i < model.getNodeCount(); i++) {
            scratchVec3.set(animatedTranslations[i]);
            animatedTranslations[i].set(transitionTranslations[i]).lerp(scratchVec3, alpha);

            qResultScratch.set(animatedRotations[i]);
            animatedRotations[i].set(transitionRotations[i]).slerp(qResultScratch, alpha).normalize();

            scratchVec3.set(animatedScales[i]);
            animatedScales[i].set(transitionScales[i]).lerp(scratchVec3, alpha);

            animatedMorphWeights[i] = blendMorphWeights(transitionMorphWeights[i], animatedMorphWeights[i], alpha);
        }
    }

    @Nullable
    private static float[] blendMorphWeights(@Nullable float[] from, @Nullable float[] to, float alpha) {
        if (from == null && to == null) {
            return null;
        }
        int length = Math.max(from != null ? from.length : 0, to != null ? to.length : 0);
        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
            float fromValue = from != null && i < from.length ? from[i] : 0.0f;
            float toValue = to != null && i < to.length ? to[i] : 0.0f;
            result[i] = fromValue + (toValue - fromValue) * alpha;
        }
        return result;
    }

    public boolean isTransitioning() {
        return (transitionFromAnimation != null || transitionFromSnapshot)
            && transitionDuration > 0.0f && transitionTime < transitionDuration;
    }

    public float getTransitionTime() { return transitionTime; }

    public float getTransitionDuration() { return transitionDuration; }

    private void clearTransition() {
        transitionFromAnimation = null;
        transitionFromTime = 0.0f;
        transitionTime = 0.0f;
        transitionDuration = 0.0f;
        transitionFromLooping = false;
        transitionFromSnapshot = false;
    }

    /**
     * Samples the pose currently visible on screen (including any in-flight transition blend) and
     * stores it in the transition* arrays so an interrupting transition can fade from it without
     * popping. Pose/morph overrides are excluded: they are re-applied on top every frame.
     */
    private void captureCurrentPoseAsTransitionSource() {
        boolean blending = isTransitioning();
        if (blending && !transitionFromSnapshot && transitionFromAnimation != null) {
            resetPoseArrays(transitionTranslations, transitionRotations, transitionScales, transitionMorphWeights);
            applyAnimationToPose(
                transitionFromAnimation,
                transitionFromTime,
                transitionTranslations,
                transitionRotations,
                transitionScales,
                transitionMorphWeights
            );
        }
        resetPoseArrays(animatedTranslations, animatedRotations, animatedScales, animatedMorphWeights);
        applyAnimationToPose(
            currentAnimation,
            animationTime,
            animatedTranslations,
            animatedRotations,
            animatedScales,
            animatedMorphWeights
        );
        if (blending) {
            // Guard: only blend while a real transition is active (transitionDuration > 0);
            // dividing by 0 here would poison the pose arrays with NaN.
            blendTransitionPose(smoothStep(clamp(transitionTime / transitionDuration, 0.0f, 1.0f)));
        }
        for (int i = 0; i < model.getNodeCount(); i++) {
            transitionTranslations[i].set(animatedTranslations[i]);
            transitionRotations[i].set(animatedRotations[i]);
            transitionScales[i].set(animatedScales[i]);
            float[] weights = animatedMorphWeights[i];
            transitionMorphWeights[i] = weights != null ? weights.clone() : null;
        }
    }

    private static float advanceTime(RuntimeAnimation animation, float time, float deltaSeconds, boolean loop) {
        float nextTime = time + deltaSeconds;
        float duration = animation.getDuration();
        if (loop && duration > 0.0f) {
            return ((nextTime % duration) + duration) % duration;
        }
        return Math.max(0.0f, Math.min(nextTime, duration));
    }

    private static float smoothStep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private void applyAnimationToNodes(RuntimeAnimation animation, float time) {
        applyAnimationToPose(
            animation,
            time,
            animatedTranslations,
            animatedRotations,
            animatedScales,
            animatedMorphWeights
        );
    }

    private void applyAnimationToPose(RuntimeAnimation animation, float time,
                                      Vector3f[] translations, Quaternionf[] rotations,
                                      Vector3f[] scales, float[][] morphWeights) {
        for (RuntimeChannel channel : animation.getChannels()) {
            int nodeIndex = channel.getTargetNodeIndex();
            if (nodeIndex < 0 || nodeIndex >= model.getNodeCount()) continue;

            float[] inputTimes = channel.getInputTimes();
            float[] outputValues = channel.getOutputValues();

            if (inputTimes == null || outputValues == null || inputTimes.length == 0) continue;

            int keyframeIndex = findKeyframeIndex(inputTimes, time);
            float localTime = time;

            if (channel.getInterpolation() == RuntimeChannel.Interpolation.STEP) {
                localTime = inputTimes[keyframeIndex];
            }

            float[] interpolated = interpolateKeyframe(channel, keyframeIndex, inputTimes, outputValues, localTime);
            if (interpolated == null) continue;

            applyChannelValue(nodeIndex, channel.getTargetPath(), interpolated,
                translations, rotations, scales, morphWeights);
        }
    }

    private int findKeyframeIndex(float[] inputTimes, float time) {
        int low = 0;
        int high = inputTimes.length - 2;

        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (inputTimes[mid] <= time) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        return Math.min(low, Math.max(0, inputTimes.length - 2));
    }

    private float[] interpolateKeyframe(RuntimeChannel channel, int keyframeIndex,
                                         float[] inputTimes, float[] outputValues, float time) {
        RuntimeChannel.TargetPath path = channel.getTargetPath();
        int components = channel.getOutputComponentCount();
        int valueStride = channel.getInterpolation() == RuntimeChannel.Interpolation.CUBICSPLINE
            ? components * 3
            : components;

        if (keyframeIndex >= inputTimes.length - 1) {
            int offset = keyframeIndex * valueStride;
            if (channel.getInterpolation() == RuntimeChannel.Interpolation.CUBICSPLINE) {
                offset += components;
            }
            if (offset + components > outputValues.length) return null;
            float[] result = new float[components];
            System.arraycopy(outputValues, offset, result, 0, components);
            return result;
        }

        float t0 = inputTimes[keyframeIndex];
        float t1 = inputTimes[keyframeIndex + 1];
        float alpha = (time - t0) / (t1 - t0);
        alpha = Math.max(0f, Math.min(1f, alpha));

        int offset0 = keyframeIndex * components;
        int offset1 = (keyframeIndex + 1) * components;
        if (channel.getInterpolation() == RuntimeChannel.Interpolation.CUBICSPLINE) {
            offset0 = keyframeIndex * valueStride + components;
            offset1 = (keyframeIndex + 1) * valueStride + components;
        }

        if (offset0 + components > outputValues.length || offset1 + components > outputValues.length) {
            return null;
        }

        float[] result = new float[components];

        if (path == RuntimeChannel.TargetPath.ROTATION) {
            q0Scratch.set(outputValues[offset0], outputValues[offset0 + 1],
                    outputValues[offset0 + 2], outputValues[offset0 + 3]);
            q1Scratch.set(outputValues[offset1], outputValues[offset1 + 1],
                    outputValues[offset1 + 2], outputValues[offset1 + 3]);

            q0Scratch.slerp(q1Scratch, alpha, qResultScratch);

            result[0] = qResultScratch.x;
            result[1] = qResultScratch.y;
            result[2] = qResultScratch.z;
            result[3] = qResultScratch.w;
        } else {
            for (int i = 0; i < components; i++) {
                result[i] = outputValues[offset0 + i] * (1 - alpha) + outputValues[offset1 + i] * alpha;
            }
        }

        return result;
    }

    private void applyChannelValue(int nodeIndex, RuntimeChannel.TargetPath path, float[] values,
                                   Vector3f[] translations, Quaternionf[] rotations,
                                   Vector3f[] scales, float[][] morphWeights) {
        switch (path) {
            case TRANSLATION -> {
                translations[nodeIndex].set(values[0], values[1], values[2]);
            }
            case ROTATION -> {
                rotations[nodeIndex].set(values[0], values[1], values[2], values[3]).normalize();
            }
            case SCALE -> {
                scales[nodeIndex].set(values[0], values[1], values[2]);
            }
            case WEIGHTS -> {
                float[] weights = morphWeights[nodeIndex];
                if (weights == null || weights.length != values.length) {
                    weights = new float[values.length];
                    morphWeights[nodeIndex] = weights;
                }
                System.arraycopy(values, 0, weights, 0, values.length);
            }
        }
    }

    private void applyPoseOverrides() {
        for (Map.Entry<Integer, PoseOverride> entry : poseOverrides.entrySet()) {
            int nodeIndex = entry.getKey();
            if (nodeIndex < 0 || nodeIndex >= model.getNodeCount()) {
                continue;
            }
            PoseOverride override = entry.getValue();
            if (override.translation != null) {
                if (override.additive) {
                    animatedTranslations[nodeIndex].add(override.translation);
                } else {
                    animatedTranslations[nodeIndex].set(override.translation);
                }
            }
            if (override.rotation != null) {
                if (override.additive) {
                    animatedRotations[nodeIndex].mul(override.rotation).normalize();
                } else {
                    animatedRotations[nodeIndex].set(override.rotation).normalize();
                }
            }
            if (override.scale != null) {
                if (override.additive) {
                    animatedScales[nodeIndex].mul(override.scale);
                } else {
                    animatedScales[nodeIndex].set(override.scale);
                }
            }
        }
    }

    private void rebuildLocalTransforms(int nodeCount) {
        for (int i = 0; i < nodeCount; i++) {
            localAnimatedTransforms[i]
                .identity()
                .translate(animatedTranslations[i])
                .rotate(animatedRotations[i])
                .scale(animatedScales[i]);
        }
    }

    private void computeNodeGlobalTransform(int nodeIndex, RuntimeModel model) {
        if (nodeComputed[nodeIndex]) return;
        nodeComputed[nodeIndex] = true;

        RuntimeNode node = model.getNodes().get(nodeIndex);
        int parentIndex = node.getParentIndex();

        if (parentIndex >= 0) {
            if (!nodeComputed[parentIndex]) {
                computeNodeGlobalTransform(parentIndex, model);
            }
            // FIX: Correct parent-child matrix multiplication order
            scratchGlobalTransforms[nodeIndex].set(scratchGlobalTransforms[parentIndex]);
            scratchGlobalTransforms[nodeIndex].mul(localAnimatedTransforms[nodeIndex]);
        } else {
            scratchGlobalTransforms[nodeIndex].set(localAnimatedTransforms[nodeIndex]);
        }
    }

    public void play(String animationName) {
        play(animationName, false);
    }

    public void setAnimationTime(float time) {
        if (this.animationTime != time) {
            this.animationTime = time;
            clearTransition();
            poseVersion++;
        }
    }

    public int getNodeIndex(String nodeName) {
        return nodeNameToIndex.getOrDefault(nodeName, -1);
    }

    public void setBoneTransform(String nodeName, @Nullable Vector3f translation,
                                 @Nullable Quaternionf rotation, @Nullable Vector3f scale,
                                 boolean additive) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            setBoneTransform(nodeIndex, translation, rotation, scale, additive);
        }
    }

    public void setBoneTransform(int nodeIndex, @Nullable Vector3f translation,
                                 @Nullable Quaternionf rotation, @Nullable Vector3f scale,
                                 boolean additive) {
        poseOverrides.put(nodeIndex, new PoseOverride(translation, rotation, scale, additive));
        poseVersion++;
    }

    /**
     * Aim a node at a target by REPLACING its animated local rotation, instead of adding a
     * correction over it like {@link #lookAt}. The playing clip can do whatever it likes with
     * this bone — head-bob, sway, crossfade between clips — the gaze holds, because the bone's
     * rotation channel is fully owned by the look while this is applied each frame.
     *
     * <p>Yaw and pitch limits (degrees) are measured from the node's REST orientation relative
     * to its parent — anatomical limits, unaffected by whichever clip is playing. The node's
     * animated translation and scale are preserved (only rotation is replaced), and the parent
     * chain remains fully animated, so the head still rides the body naturally. {@code smoothing}
     * is 0..1 as in {@link #lookAt}: 0 snaps, closer to 1 retains more of the previous gaze.</p>
     */
    public void lookAtStable(String nodeName, Vector3f modelTarget, float maxYaw, float maxPitch, float smoothing) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            lookAtStable(nodeIndex, modelTarget, maxYaw, maxPitch, smoothing);
        }
    }

    /**
     * Aim a node at a target by replacing its animated local rotation.
     *
     * @see #lookAtStable(String, Vector3f, float, float, float)
     */
    public void lookAtStable(int nodeIndex, Vector3f modelTarget, float maxYaw, float maxPitch, float smoothing) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount() || modelTarget == null) {
            return;
        }

        PoseOverride previousOverride = poseOverrides.remove(nodeIndex);
        Matrix4f[] baseTransforms = computeGlobalTransforms(0.0f);
        if (previousOverride != null) {
            poseOverrides.put(nodeIndex, previousOverride);
        }

        RuntimeNode node = model.getNodes().get(nodeIndex);
        int parentIndex = node.getParentIndex();
        Quaternionf parentRotation = parentIndex >= 0
            ? baseTransforms[parentIndex].getUnnormalizedRotation(new Quaternionf()).normalize()
            : new Quaternionf();

        Vector3f nodePosition = baseTransforms[nodeIndex].getTranslation(new Vector3f());
        Vector3f direction = new Vector3f(modelTarget).sub(nodePosition);
        if (direction.lengthSquared() < 1.0e-8f) {
            return;
        }
        direction.normalize();

        // The bind-pose orientation relative to the parent: the anatomical "straight ahead".
        Quaternionf restRotation = node.getLocalTransform()
            .getUnnormalizedRotation(new Quaternionf()).normalize();

        // Express the target direction in the node's REST frame (model -> parent -> rest-local).
        Vector3f localDirection = new Quaternionf(parentRotation).mul(restRotation).invert()
            .transform(new Vector3f(direction));
        if (localDirection.lengthSquared() < 1.0e-8f) {
            return;
        }
        localDirection.normalize();

        float yaw = (float) Math.atan2(localDirection.x, localDirection.z);
        float horizontal = (float) Math.sqrt(
            localDirection.x * localDirection.x + localDirection.z * localDirection.z);
        float pitch = (float) -Math.atan2(localDirection.y, horizontal);

        float yawLimit = (float) Math.toRadians(Math.max(0.0f, maxYaw));
        float pitchLimit = (float) Math.toRadians(Math.max(0.0f, maxPitch));
        yaw = clamp(yaw, -yawLimit, yawLimit);
        pitch = clamp(pitch, -pitchLimit, pitchLimit);

        // Absolute local rotation: rest orientation plus the clamped aim. This REPLACES whatever
        // the clip put on this bone (translation/scale stay animated via the null arguments).
        Quaternionf desiredRotation = new Quaternionf(restRotation)
            .rotateY(yaw).rotateX(pitch).normalize();

        Quaternionf previousRotation = lookAtRotations.get(nodeIndex);
        float smoothingAmount = clamp(smoothing, 0.0f, 1.0f);
        Quaternionf smoothedRotation = new Quaternionf(desiredRotation);
        if (previousRotation != null && smoothingAmount > 0.0f) {
            smoothedRotation.set(previousRotation)
                .slerp(desiredRotation, 1.0f - smoothingAmount).normalize();
        }

        lookAtRotations.put(nodeIndex, new Quaternionf(smoothedRotation));
        setBoneTransform(nodeIndex, null, smoothedRotation, null, false);
    }

    /**
     * Aim a node's local +Z axis toward a target in model space.
     *
     * <p>The generated rotation is additive over the active animation clip. The yaw and pitch
     * limits are degrees, and {@code smoothing} is clamped to 0..1 where 0 snaps immediately
     * and values closer to 1 retain more of the previous look rotation.</p>
     */
    public void lookAt(String nodeName, Vector3f modelTarget, float maxYaw, float maxPitch, float smoothing) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            lookAt(nodeIndex, modelTarget, maxYaw, maxPitch, smoothing);
        }
    }

    /**
     * Aim a node's local +Z axis toward a target in model space.
     *
     * @see #lookAt(String, Vector3f, float, float, float)
     */
    public void lookAt(int nodeIndex, Vector3f modelTarget, float maxYaw, float maxPitch, float smoothing) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount() || modelTarget == null) {
            return;
        }

        PoseOverride previousOverride = poseOverrides.remove(nodeIndex);
        Matrix4f[] baseTransforms = computeGlobalTransforms(0.0f);
        if (previousOverride != null) {
            poseOverrides.put(nodeIndex, previousOverride);
        }

        Matrix4f nodeTransform = baseTransforms[nodeIndex];
        Vector3f nodePosition = nodeTransform.getTranslation(new Vector3f());
        Vector3f direction = new Vector3f(modelTarget).sub(nodePosition);
        if (direction.lengthSquared() < 1.0e-8f) {
            return;
        }
        direction.normalize();

        Quaternionf nodeRotation = nodeTransform.getUnnormalizedRotation(new Quaternionf()).normalize();
        Quaternionf inverseNodeRotation = new Quaternionf(nodeRotation).invert();
        Vector3f localDirection = new Vector3f(direction);
        inverseNodeRotation.transform(localDirection);
        if (localDirection.lengthSquared() < 1.0e-8f) {
            return;
        }
        localDirection.normalize();

        float yaw = (float) Math.atan2(localDirection.x, localDirection.z);
        float horizontal = (float) Math.sqrt(localDirection.x * localDirection.x + localDirection.z * localDirection.z);
        float pitch = (float) -Math.atan2(localDirection.y, horizontal);

        float yawLimit = (float) Math.toRadians(Math.max(0.0f, maxYaw));
        float pitchLimit = (float) Math.toRadians(Math.max(0.0f, maxPitch));
        yaw = clamp(yaw, -yawLimit, yawLimit);
        pitch = clamp(pitch, -pitchLimit, pitchLimit);

        Quaternionf desiredRotation = new Quaternionf().rotateY(yaw).rotateX(pitch).normalize();
        Quaternionf smoothedRotation = new Quaternionf(desiredRotation);
        Quaternionf previousRotation = lookAtRotations.get(nodeIndex);
        float smoothingAmount = clamp(smoothing, 0.0f, 1.0f);
        if (previousRotation != null && smoothingAmount > 0.0f) {
            float blend = 1.0f - smoothingAmount;
            smoothedRotation.set(previousRotation).slerp(desiredRotation, blend).normalize();
        }

        lookAtRotations.put(nodeIndex, new Quaternionf(smoothedRotation));
        setBoneTransform(nodeIndex, null, smoothedRotation, null, true);
    }

    public void clearBoneTransform(String nodeName) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            clearBoneTransform(nodeIndex);
        }
    }

    public void clearBoneTransform(int nodeIndex) {
        lookAtRotations.remove(nodeIndex);
        if (poseOverrides.remove(nodeIndex) != null) {
            poseVersion++;
        }
    }

    public void clearBoneTransforms() {
        lookAtRotations.clear();
        if (!poseOverrides.isEmpty()) {
            poseOverrides.clear();
            poseVersion++;
        }
    }

    public void setMorphWeight(String nodeName, String targetName, float weight) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex < 0) {
            return;
        }
        int targetIndex = getMorphTargetIndex(nodeIndex, targetName);
        if (targetIndex >= 0) {
            setMorphWeight(nodeIndex, targetIndex, weight);
        }
    }

    public void setMorphWeight(String nodeName, int targetIndex, float weight) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            setMorphWeight(nodeIndex, targetIndex, weight);
        }
    }

    public void setMorphWeight(int nodeIndex, int targetIndex, float weight) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount() || targetIndex < 0) {
            return;
        }
        int morphCount = Math.max(determineNodeMorphTargetCount(nodeIndex), targetIndex + 1);
        float[] weights = morphOverrides.computeIfAbsent(nodeIndex, ignored -> baseMorphWeights(nodeIndex, morphCount));
        if (weights.length <= targetIndex) {
            float[] expanded = new float[targetIndex + 1];
            System.arraycopy(weights, 0, expanded, 0, weights.length);
            weights = expanded;
            morphOverrides.put(nodeIndex, weights);
        }
        if (weights[targetIndex] != weight) {
            weights[targetIndex] = weight;
            poseVersion++;
        }
    }

    public void setMorphWeights(String nodeName, float[] weights) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            setMorphWeights(nodeIndex, weights);
        }
    }

    public void setMorphWeights(int nodeIndex, float[] weights) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount() || weights == null) {
            return;
        }
        morphOverrides.put(nodeIndex, weights.clone());
        poseVersion++;
    }

    public void clearMorphWeights(String nodeName) {
        int nodeIndex = getNodeIndex(nodeName);
        if (nodeIndex >= 0) {
            clearMorphWeights(nodeIndex);
        }
    }

    public void clearMorphWeights(int nodeIndex) {
        if (morphOverrides.remove(nodeIndex) != null) {
            poseVersion++;
        }
    }

    public void clearMorphWeights() {
        if (!morphOverrides.isEmpty()) {
            morphOverrides.clear();
            poseVersion++;
        }
    }

    public int getMorphTargetIndex(String nodeName, String targetName) {
        int nodeIndex = getNodeIndex(nodeName);
        return nodeIndex >= 0 ? getMorphTargetIndex(nodeIndex, targetName) : -1;
    }

    public int getMorphTargetIndex(int nodeIndex, String targetName) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount()) {
            return -1;
        }
        RuntimeNode node = model.getNodes().get(nodeIndex);
        for (int meshIndex : node.getMeshIndices()) {
            int targetIndex = model.getMorphTargetIndex(meshIndex, targetName);
            if (targetIndex >= 0) {
                return targetIndex;
            }
        }
        return -1;
    }

    @Nullable
    public float[] getNodeMorphWeights(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= animatedMorphWeights.length) {
            return null;
        }
        float[] weights = animatedMorphWeights[nodeIndex];
        return weights != null ? weights.clone() : null;
    }

    @Nullable
    public Matrix4f getNodeGlobalTransform(String nodeName, float partialTick) {
        int nodeIndex = getNodeIndex(nodeName);
        return nodeIndex >= 0 ? getNodeGlobalTransform(nodeIndex, partialTick) : null;
    }

    @Nullable
    public Matrix4f getNodeGlobalTransform(int nodeIndex, float partialTick) {
        if (nodeIndex < 0 || nodeIndex >= model.getNodeCount()) {
            return null;
        }
        Matrix4f[] transforms = computeGlobalTransforms(partialTick);
        return nodeIndex < transforms.length ? new Matrix4f(transforms[nodeIndex]) : null;
    }

    @Nullable
    public Vector3f getNodeGlobalPosition(String nodeName, float partialTick) {
        int nodeIndex = getNodeIndex(nodeName);
        return nodeIndex >= 0 ? getNodeGlobalPosition(nodeIndex, partialTick) : null;
    }

    @Nullable
    public Vector3f getNodeGlobalPosition(int nodeIndex, float partialTick) {
        Matrix4f transform = getNodeGlobalTransform(nodeIndex, partialTick);
        return transform != null ? transform.getTranslation(new Vector3f()) : null;
    }

    public void reset() {
        animationTime = 0f;
        playing = false;
        currentAnimation = null;
        clipResumeTimes.clear();
        clearTransition();
        poseVersion++;
    }

    /**
     * Scales the rate at which {@link #tick(float)} advances animation time. 1.0 is authored speed;
     * 0 freezes playback without pausing transitions. NEGATIVE values play a LOOPING clip in
     * reverse (e.g. a strafe-left cycle becomes strafe-right, a walk becomes a backpedal);
     * non-looping clips clamp at 0 instead of reversing.
     */
    public void setSpeedMultiplier(float multiplier) {
        this.speedMultiplier = multiplier;
    }

    public float getSpeedMultiplier() { return speedMultiplier; }

    public boolean isPlaying() { return playing; }
    public float getAnimationTime() { return animationTime; }
    public int getPoseVersion() { return poseVersion; }
    @Nullable
    public RuntimeAnimation getCurrentAnimation() { return currentAnimation; }
    public boolean isLooping() { return looping; }

    public String getAnimationName() {
        return currentAnimation != null ? currentAnimation.getName() : null;
    }

    private void applyMorphOverrides() {
        for (Map.Entry<Integer, float[]> entry : morphOverrides.entrySet()) {
            int nodeIndex = entry.getKey();
            if (nodeIndex < 0 || nodeIndex >= model.getNodeCount()) {
                continue;
            }
            animatedMorphWeights[nodeIndex] = entry.getValue().clone();
        }
    }

    private float[] baseMorphWeights(int nodeIndex, int minimumLength) {
        float[] base = animatedMorphWeights[nodeIndex];
        if (base == null) {
            base = model.getNodes().get(nodeIndex).getMorphWeights();
        }
        int length = Math.max(minimumLength, base != null ? base.length : 0);
        float[] result = new float[length];
        if (base != null) {
            System.arraycopy(base, 0, result, 0, Math.min(base.length, result.length));
        }
        return result;
    }

    private int determineNodeMorphTargetCount(int nodeIndex) {
        RuntimeNode node = model.getNodes().get(nodeIndex);
        int count = 0;
        for (int meshIndex : node.getMeshIndices()) {
            if (meshIndex >= 0 && meshIndex < model.getMeshCount()) {
                count = Math.max(count, model.getMeshes().get(meshIndex).getMorphTargetCount());
            }
        }
        float[] nodeWeights = node.getMorphWeights();
        return Math.max(count, nodeWeights != null ? nodeWeights.length : 0);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class PoseOverride {
        @Nullable
        private final Vector3f translation;
        @Nullable
        private final Quaternionf rotation;
        @Nullable
        private final Vector3f scale;
        private final boolean additive;

        private PoseOverride(@Nullable Vector3f translation, @Nullable Quaternionf rotation,
                             @Nullable Vector3f scale, boolean additive) {
            this.translation = translation != null ? new Vector3f(translation) : null;
            this.rotation = rotation != null ? new Quaternionf(rotation) : null;
            this.scale = scale != null ? new Vector3f(scale) : null;
            this.additive = additive;
        }
    }
}
