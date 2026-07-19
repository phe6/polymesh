package dev.phe.polymesh.model;

public class RuntimeMesh {
    private final float[] positions;
    private final float[] normals;
    private final float[] uvs;
    private final float[] tangents;
    private final int[] indices;
    private final int[] jointIndices;
    private final float[] jointWeights;
    private final float[][] morphPositionDeltas;
    private final float[][] morphNormalDeltas;
    private final String[] morphTargetNames;
    private final int materialIndex;
    private final int vertexCount;
    private final int triangleCount;
    private final boolean generatedNormals;
    private final boolean generatedTangents;

    public RuntimeMesh(float[] positions, float[] normals, float[] uvs,
                      int[] indices, int[] jointIndices, float[] jointWeights,
                      int materialIndex) {
        this(positions, normals, uvs, null, indices, jointIndices, jointWeights, null, null,
            null, materialIndex, false, false);
    }

    public RuntimeMesh(float[] positions, float[] normals, float[] uvs,
                      int[] indices, int[] jointIndices, float[] jointWeights,
                      float[][] morphPositionDeltas, float[][] morphNormalDeltas,
                      int materialIndex) {
        this(positions, normals, uvs, null, indices, jointIndices, jointWeights,
            morphPositionDeltas, morphNormalDeltas, null, materialIndex, false, false);
    }

    public RuntimeMesh(float[] positions, float[] normals, float[] uvs, float[] tangents,
                      int[] indices, int[] jointIndices, float[] jointWeights,
                      float[][] morphPositionDeltas, float[][] morphNormalDeltas,
                      int materialIndex, boolean generatedNormals, boolean generatedTangents) {
        this(positions, normals, uvs, tangents, indices, jointIndices, jointWeights,
            morphPositionDeltas, morphNormalDeltas, null, materialIndex, generatedNormals, generatedTangents);
    }

    public RuntimeMesh(float[] positions, float[] normals, float[] uvs, float[] tangents,
                      int[] indices, int[] jointIndices, float[] jointWeights,
                      float[][] morphPositionDeltas, float[][] morphNormalDeltas,
                      String[] morphTargetNames, int materialIndex,
                      boolean generatedNormals, boolean generatedTangents) {
        this.positions = positions != null ? positions : new float[0];
        this.normals = normals;
        this.uvs = uvs;
        this.tangents = tangents;
        this.indices = indices != null ? indices : new int[0];
        this.jointIndices = jointIndices;
        this.jointWeights = jointWeights;
        this.morphPositionDeltas = morphPositionDeltas;
        this.morphNormalDeltas = morphNormalDeltas;
        this.morphTargetNames = morphTargetNames != null ? morphTargetNames.clone() : new String[0];
        this.materialIndex = materialIndex;
        this.vertexCount = this.positions.length / 3;
        this.triangleCount = this.indices.length / 3;
        this.generatedNormals = generatedNormals;
        this.generatedTangents = generatedTangents;
    }

    public float[] getPositions() { return positions; }
    public float[] getNormals() { return normals; }
    public float[] getUvs() { return uvs; }
    public float[] getTangents() { return tangents; }
    public int[] getIndices() { return indices; }
    public int[] getJointIndices() { return jointIndices; }
    public float[] getJointWeights() { return jointWeights; }
    public float[][] getMorphPositionDeltas() { return morphPositionDeltas; }
    public float[][] getMorphNormalDeltas() { return morphNormalDeltas; }
    public String[] getMorphTargetNames() { return morphTargetNames.clone(); }
    public int getMaterialIndex() { return materialIndex; }
    public int getVertexCount() { return vertexCount; }
    public int getTriangleCount() { return triangleCount; }
    public boolean hasTangents() { return tangents != null && tangents.length >= vertexCount * 4; }
    public boolean hasGeneratedNormals() { return generatedNormals; }
    public boolean hasGeneratedTangents() { return generatedTangents; }

    public boolean hasSkinning() {
        return jointIndices != null && jointIndices.length > 0
            && jointWeights != null && jointWeights.length > 0;
    }

    public boolean hasMorphTargets() {
        return (morphPositionDeltas != null && morphPositionDeltas.length > 0)
            || (morphNormalDeltas != null && morphNormalDeltas.length > 0);
    }

    public int getMorphTargetCount() {
        int positionCount = morphPositionDeltas != null ? morphPositionDeltas.length : 0;
        int normalCount = morphNormalDeltas != null ? morphNormalDeltas.length : 0;
        return Math.max(positionCount, normalCount);
    }

    public int getMorphTargetIndex(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return -1;
        }
        for (int i = 0; i < morphTargetNames.length; i++) {
            if (targetName.equals(morphTargetNames[i])) {
                return i;
            }
        }
        return -1;
    }
}
