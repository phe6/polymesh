package dev.phe.polymesh.rendering;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.RuntimeSkin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

public class CpuSkinningPipeline {
    private final RuntimeModel model;
    private final Vector4f tempPos = new Vector4f();
    private final Vector3f tempNormal = new Vector3f();
    private final Vector4f tempSkinnedPos = new Vector4f();
    private final Vector3f tempSkinnedNormal = new Vector3f();
    private final Vector3f tempPoseNormal = new Vector3f();

    private final Matrix4f[] skinningMatrices;
    private final Matrix4f[] inverseBindMatrices;

    public CpuSkinningPipeline(RuntimeModel model) {
        this.model = model;
        this.skinningMatrices = new Matrix4f[256];
        this.inverseBindMatrices = new Matrix4f[256];
        for (int i = 0; i < skinningMatrices.length; i++) {
            skinningMatrices[i] = new Matrix4f();
            inverseBindMatrices[i] = new Matrix4f();
        }
    }

    public void computeSkinningMatrices(Matrix4f[] globalNodeTransforms, @Nullable RuntimeSkin skin) {
        if (skin == null) return;

        int[] jointNodeIndices = skin.getJointNodeIndices();
        Matrix4f[] ibms = skin.getInverseBindMatrices();

        for (int i = 0; i < jointNodeIndices.length && i < 256; i++) {
            int nodeIndex = jointNodeIndices[i];
            if (nodeIndex >= 0 && nodeIndex < globalNodeTransforms.length) {
                Matrix4f ibm = ibms != null && i < ibms.length ? ibms[i] : new Matrix4f();
                skinningMatrices[i].set(globalNodeTransforms[nodeIndex]).mul(ibm);
            }
        }
    }

    public void renderMesh(RuntimeMesh mesh, Matrix4f[] globalNodeTransforms,
                           @Nullable Matrix4f nodeTransform,
                           @Nullable float[] morphWeights,
                           @Nullable RuntimeSkin skin, PoseStack.Pose pose,
                           VertexConsumer vertexConsumer, int packedLight,
                           int packedOverlay, int tint) {
        computeSkinningMatrices(globalNodeTransforms, skin);

        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        float[] uvs = mesh.getUvs();
        int[] indices = mesh.getIndices();
        int[] jointIndices = mesh.getJointIndices();
        float[] jointWeights = mesh.getJointWeights();
        float[][] morphPositionDeltas = mesh.getMorphPositionDeltas();
        float[][] morphNormalDeltas = mesh.getMorphNormalDeltas();
        boolean hasSkinning = mesh.hasSkinning();
        boolean hasMorphTargets = morphWeights != null && mesh.hasMorphTargets();

        if (positions == null || indices == null) return;

        Matrix4f poseMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        int numTriangles = indices.length / 3;

        for (int tri = 0; tri < numTriangles; tri++) {
            for (int j = 0; j < 3; j++) {
                int vertIdx = indices[tri * 3 + j];

                int positionOffset = vertIdx * 3;
                float x = positions[positionOffset];
                float y = positions[positionOffset + 1];
                float z = positions[positionOffset + 2];

                float nx = normals != null ? normals[positionOffset] : 0;
                float ny = normals != null ? normals[positionOffset + 1] : 0;
                float nz = normals != null ? normals[positionOffset + 2] : 0;

                if (hasMorphTargets) {
                    int morphCount = Math.min(morphWeights.length, mesh.getMorphTargetCount());
                    for (int morphIndex = 0; morphIndex < morphCount; morphIndex++) {
                        float weight = morphWeights[morphIndex];
                        if (Math.abs(weight) < 0.0001f) {
                            continue;
                        }
                        if (morphPositionDeltas != null && morphIndex < morphPositionDeltas.length) {
                            float[] delta = morphPositionDeltas[morphIndex];
                            if (delta != null && positionOffset + 2 < delta.length) {
                                x += delta[positionOffset] * weight;
                                y += delta[positionOffset + 1] * weight;
                                z += delta[positionOffset + 2] * weight;
                            }
                        }
                        if (morphNormalDeltas != null && morphIndex < morphNormalDeltas.length) {
                            float[] delta = morphNormalDeltas[morphIndex];
                            if (delta != null && positionOffset + 2 < delta.length) {
                                nx += delta[positionOffset] * weight;
                                ny += delta[positionOffset + 1] * weight;
                                nz += delta[positionOffset + 2] * weight;
                            }
                        }
                    }
                }

                if (hasSkinning && jointIndices != null && jointWeights != null) {
                    skinVertex(x, y, z, nx, ny, nz, jointIndices, jointWeights, vertIdx);
                } else {
                    tempSkinnedPos.set(x, y, z, 1.0f);
                    tempSkinnedNormal.set(nx, ny, nz);
                }

                if (!hasSkinning && nodeTransform != null) {
                    nodeTransform.transform(tempSkinnedPos);
                    nodeTransform.transformDirection(tempSkinnedNormal);
                }

                if (tempSkinnedPos.w != 0 && tempSkinnedPos.w != 1.0f) {
                    float invW = 1.0f / tempSkinnedPos.w;
                    tempSkinnedPos.x *= invW;
                    tempSkinnedPos.y *= invW;
                    tempSkinnedPos.z *= invW;
                    tempSkinnedPos.w = 1.0f;
                }

                float px = tempSkinnedPos.x(), py = tempSkinnedPos.y(), pz = tempSkinnedPos.z();
                tempPos.set(px, py, pz, 1.0f);
                poseMatrix.transform(tempPos);
                float tx = tempPos.x();
                float ty = tempPos.y();
                float tz = tempPos.z();

                tempPoseNormal.set(tempSkinnedNormal);
                normalMatrix.transform(tempPoseNormal);
                tempSkinnedNormal.set(tempPoseNormal);

                float len = tempSkinnedNormal.length();
                if (len > 0.001f) {
                    tempSkinnedNormal.div(len);
                }

                float u = uvs != null && vertIdx * 2 + 1 < uvs.length ? uvs[vertIdx * 2] : 0;
                float v = uvs != null && vertIdx * 2 + 1 < uvs.length ? uvs[vertIdx * 2 + 1] : 0;

                int a = (tint >> 24) & 0xFF;
                int r = (tint >> 16) & 0xFF;
                int g = (tint >> 8) & 0xFF;
                int b = tint & 0xFF;

                vertexConsumer.vertex(tx, ty, tz)
                    .color(r, g, b, a)
                    .uv(u, v)
                    .overlayCoords(packedOverlay)
                    .uv2(packedLight)
                    .normal(tempSkinnedNormal.x, tempSkinnedNormal.y, tempSkinnedNormal.z)
                    .endVertex();
            }
        }
    }

    private void skinVertex(float x, float y, float z, float nx, float ny, float nz,
                            int[] jointIndices, float[] jointWeights, int vertexIndex) {
        tempSkinnedPos.set(0, 0, 0, 0);
        tempSkinnedNormal.set(0, 0, 0);

        for (int i = 0; i < 4; i++) {
            int jointIdx = vertexIndex * 4 + i;
            if (jointIdx >= jointIndices.length || jointIdx >= jointWeights.length) break;

            int jointIndex = jointIndices[jointIdx];
            float weight = jointWeights[jointIdx];

            if (weight < 0.0001f || jointIndex < 0 || jointIndex >= skinningMatrices.length) continue;

            tempPos.set(x, y, z, 1.0f);
            skinningMatrices[jointIndex].transform(tempPos, tempPos);
            tempPos.mul(weight);
            tempSkinnedPos.add(tempPos.x, tempPos.y, tempPos.z, tempPos.w);

            tempNormal.set(nx, ny, nz);
            skinningMatrices[jointIndex].transformDirection(tempNormal, tempNormal);
            tempNormal.mul(weight);
            tempSkinnedNormal.add(tempNormal);
        }

        if (tempSkinnedPos.w == 0) {
            tempSkinnedPos.set(x, y, z, 1.0f);
        }
    }
}
