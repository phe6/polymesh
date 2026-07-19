package dev.phe.polymesh.model;

import org.joml.Matrix4f;

public class RuntimeSkin {
    private final int[] jointNodeIndices;
    private final Matrix4f[] inverseBindMatrices;

    public RuntimeSkin(int[] jointNodeIndices, Matrix4f[] inverseBindMatrices) {
        this.jointNodeIndices = jointNodeIndices;
        this.inverseBindMatrices = inverseBindMatrices;
    }

    public int[] getJointNodeIndices() { return jointNodeIndices; }
    public Matrix4f[] getInverseBindMatrices() { return inverseBindMatrices; }

    public int getJointCount() { return jointNodeIndices.length; }
}