package dev.phe.polymesh.model;

public class RuntimeChannel {
    public enum TargetPath {
        TRANSLATION,
        ROTATION,
        SCALE,
        WEIGHTS
    }

    public enum Interpolation {
        LINEAR,
        STEP,
        CUBICSPLINE
    }

    private final int targetNodeIndex;
    private final TargetPath targetPath;
    private final float[] inputTimes;
    private final float[] outputValues;
    private final Interpolation interpolation;
    private final int outputComponentCount;

    public RuntimeChannel(int targetNodeIndex, TargetPath targetPath,
                         float[] inputTimes, float[] outputValues,
                         Interpolation interpolation) {
        this(targetNodeIndex, targetPath, inputTimes, outputValues, interpolation,
            targetPath == TargetPath.ROTATION ? 4 : 3);
    }

    public RuntimeChannel(int targetNodeIndex, TargetPath targetPath,
                         float[] inputTimes, float[] outputValues,
                         Interpolation interpolation, int outputComponentCount) {
        this.targetNodeIndex = targetNodeIndex;
        this.targetPath = targetPath;
        this.inputTimes = inputTimes;
        this.outputValues = outputValues;
        this.interpolation = interpolation;
        this.outputComponentCount = outputComponentCount;
    }

    public int getTargetNodeIndex() { return targetNodeIndex; }
    public TargetPath getTargetPath() { return targetPath; }
    public float[] getInputTimes() { return inputTimes; }
    public float[] getOutputValues() { return outputValues; }
    public Interpolation getInterpolation() { return interpolation; }
    public int getOutputComponentCount() { return outputComponentCount; }

    public int getKeyframeCount() { return inputTimes.length; }
}
