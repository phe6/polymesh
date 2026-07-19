package dev.phe.polymesh.client;

import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeSkin;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

final class SharedGpuMesh implements AutoCloseable {
    private static final int ATTR_POSITION = 0;
    private static final int ATTR_COLOR = 1;
    private static final int ATTR_UV0 = 2;
    private static final int ATTR_UV1_OVERLAY = 3;
    private static final int ATTR_UV2_LIGHT = 4;
    private static final int ATTR_NORMAL = 5;
    private static final long OFFSET_POSITION = 0L;
    private static final long OFFSET_COLOR = 12L;
    private static final long OFFSET_UV0 = 16L;
    private static final long OFFSET_NORMAL = 24L;
    private static final int POLYMESH_VERTEX_STRIDE = 36;

    private final int vao;
    private final int vertexBuffer;
    private final int indexBuffer;
    private final int indexCount;
    private final int indexType;
    private final int vertexCount;
    private final boolean dynamic;
    private final ByteBuffer dynamicVertexData;
    private final Matrix4f[] skinningMatrices;
    private final Vector4f tempPos = new Vector4f();
    private final Vector3f tempNormal = new Vector3f();
    private final Vector4f skinnedPos = new Vector4f();
    private final Vector3f skinnedNormal = new Vector3f();

    SharedGpuMesh(RuntimeMesh mesh) {
        this(mesh, false);
    }

    static SharedGpuMesh dynamic(RuntimeMesh mesh) {
        return new SharedGpuMesh(mesh, true);
    }

    private SharedGpuMesh(RuntimeMesh mesh, boolean dynamic) {
        RenderSystem.assertOnRenderThread();
        this.vertexCount = mesh.getVertexCount();
        this.dynamic = dynamic;
        this.dynamicVertexData = dynamic
            ? ByteBuffer.allocateDirect(vertexCount * stride()).order(ByteOrder.nativeOrder())
            : null;
        this.skinningMatrices = new Matrix4f[256];
        for (int i = 0; i < skinningMatrices.length; i++) {
            skinningMatrices[i] = new Matrix4f();
        }
        this.indexCount = mesh.getIndices().length;
        this.indexType = mesh.getVertexCount() > 65535 ? GL11.GL_UNSIGNED_INT : GL11.GL_UNSIGNED_SHORT;
        this.vao = GL30.glGenVertexArrays();
        this.vertexBuffer = GL15.glGenBuffers();
        this.indexBuffer = GL15.glGenBuffers();

        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        if (dynamic) {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) vertexCount * stride(), GL15.GL_DYNAMIC_DRAW);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buildVertexBuffer(mesh), GL15.GL_STATIC_DRAW);
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buildIndexBuffer(mesh), GL15.GL_STATIC_DRAW);
        setupAttributes();

        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElementBuffer);
    }

    void uploadDeformed(RuntimeMesh mesh, Matrix4f[] globalNodeTransforms, Matrix4f nodeTransform,
                        float[] morphWeights, RuntimeSkin skin) {
        RenderSystem.assertOnRenderThread();
        if (!dynamic || dynamicVertexData == null || mesh.getVertexCount() != vertexCount) {
            return;
        }

        computeSkinningMatrices(globalNodeTransforms, skin);
        writeDeformedVertices(mesh, nodeTransform, morphWeights, skin != null, dynamicVertexData);

        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, dynamicVertexData);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
    }

    void draw(Matrix4f modelViewMatrix, int packedLight, int packedOverlay) {
        RenderSystem.assertOnRenderThread();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null || indexCount <= 0) {
            return;
        }

        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        for (int i = 0; i < 12; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelViewMatrix);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        }
        if (shader.INVERSE_VIEW_ROTATION_MATRIX != null) {
            shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        RenderSystem.setupShaderLights(shader);
        shader.apply();

        GL30.glBindVertexArray(vao);
        GL20.glDisableVertexAttribArray(ATTR_UV1_OVERLAY);
        GL30.glVertexAttribI2i(ATTR_UV1_OVERLAY, packedOverlay & 0xFFFF, packedOverlay >>> 16 & 0xFFFF);
        GL20.glDisableVertexAttribArray(ATTR_UV2_LIGHT);
        GL30.glVertexAttribI2i(ATTR_UV2_LIGHT, packedLight & 0xFFFF, packedLight >>> 16 & 0xFFFF);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, indexType, 0L);
        shader.clear();

        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElementBuffer);
    }

    private ByteBuffer buildVertexBuffer(RuntimeMesh mesh) {
        int stride = stride();
        ByteBuffer buffer = ByteBuffer.allocateDirect(mesh.getVertexCount() * stride).order(ByteOrder.nativeOrder());
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        float[] uvs = mesh.getUvs();

        for (int i = 0; i < mesh.getVertexCount(); i++) {
            buffer.putFloat(positions[i * 3]);
            buffer.putFloat(positions[i * 3 + 1]);
            buffer.putFloat(positions[i * 3 + 2]);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
            buffer.putFloat(uvs != null ? uvs[i * 2] : 0.0f);
            buffer.putFloat(uvs != null ? uvs[i * 2 + 1] : 0.0f);
            buffer.putFloat(normalComponent(normals, i, 0));
            buffer.putFloat(normalComponent(normals, i, 1));
            buffer.putFloat(normalComponent(normals, i, 2));
        }
        buffer.flip();
        return buffer;
    }

    private void writeDeformedVertices(RuntimeMesh mesh, Matrix4f nodeTransform, float[] morphWeights,
                                       boolean hasSkin, ByteBuffer buffer) {
        buffer.clear();
        float[] positions = mesh.getPositions();
        float[] normals = mesh.getNormals();
        float[] uvs = mesh.getUvs();
        int[] jointIndices = mesh.getJointIndices();
        float[] jointWeights = mesh.getJointWeights();
        float[][] morphPositionDeltas = mesh.getMorphPositionDeltas();
        float[][] morphNormalDeltas = mesh.getMorphNormalDeltas();
        boolean hasSkinning = hasSkin && mesh.hasSkinning() && jointIndices != null && jointWeights != null;
        boolean hasMorphTargets = morphWeights != null && mesh.hasMorphTargets();

        for (int i = 0; i < vertexCount; i++) {
            int positionOffset = i * 3;
            float x = positions[positionOffset];
            float y = positions[positionOffset + 1];
            float z = positions[positionOffset + 2];
            float nx = normals != null ? normals[positionOffset] : 0.0f;
            float ny = normals != null ? normals[positionOffset + 1] : 1.0f;
            float nz = normals != null ? normals[positionOffset + 2] : 0.0f;

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

            if (hasSkinning) {
                skinVertex(x, y, z, nx, ny, nz, jointIndices, jointWeights, i);
            } else {
                skinnedPos.set(x, y, z, 1.0f);
                skinnedNormal.set(nx, ny, nz);
                if (nodeTransform != null) {
                    nodeTransform.transform(skinnedPos);
                    nodeTransform.transformDirection(skinnedNormal);
                }
            }

            normalize(skinnedNormal);

            buffer.putFloat(skinnedPos.x);
            buffer.putFloat(skinnedPos.y);
            buffer.putFloat(skinnedPos.z);
            buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
            buffer.putFloat(uvs != null && i * 2 + 1 < uvs.length ? uvs[i * 2] : 0.0f);
            buffer.putFloat(uvs != null && i * 2 + 1 < uvs.length ? uvs[i * 2 + 1] : 0.0f);
            buffer.putFloat(skinnedNormal.x);
            buffer.putFloat(skinnedNormal.y);
            buffer.putFloat(skinnedNormal.z);
        }
        buffer.flip();
    }

    private ByteBuffer buildIndexBuffer(RuntimeMesh mesh) {
        if (indexType == GL11.GL_UNSIGNED_INT) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(mesh.getIndices().length * Integer.BYTES).order(ByteOrder.nativeOrder());
            IntBuffer ints = buffer.asIntBuffer();
            ints.put(mesh.getIndices());
            buffer.limit(mesh.getIndices().length * Integer.BYTES);
            return buffer;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(mesh.getIndices().length * Short.BYTES).order(ByteOrder.nativeOrder());
        for (int index : mesh.getIndices()) {
            buffer.putShort((short) index);
        }
        buffer.flip();
        return buffer;
    }

    private void setupAttributes() {
        int stride = stride();
        GL20.glEnableVertexAttribArray(ATTR_POSITION);
        GL20.glVertexAttribPointer(ATTR_POSITION, 3, GL11.GL_FLOAT, false, stride, OFFSET_POSITION);
        GL20.glEnableVertexAttribArray(ATTR_COLOR);
        GL20.glVertexAttribPointer(ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, stride, OFFSET_COLOR);
        GL20.glEnableVertexAttribArray(ATTR_UV0);
        GL20.glVertexAttribPointer(ATTR_UV0, 2, GL11.GL_FLOAT, false, stride, OFFSET_UV0);
        GL20.glEnableVertexAttribArray(ATTR_NORMAL);
        GL20.glVertexAttribPointer(ATTR_NORMAL, 3, GL11.GL_FLOAT, false, stride, OFFSET_NORMAL);
    }

    private int stride() {
        return POLYMESH_VERTEX_STRIDE;
    }

    private float normalComponent(float[] normals, int vertex, int component) {
        return normals != null ? normals[vertex * 3 + component] : (component == 1 ? 1.0f : 0.0f);
    }

    private void computeSkinningMatrices(Matrix4f[] globalNodeTransforms, RuntimeSkin skin) {
        if (skin == null) {
            return;
        }

        int[] jointNodeIndices = skin.getJointNodeIndices();
        Matrix4f[] ibms = skin.getInverseBindMatrices();
        for (int i = 0; i < jointNodeIndices.length && i < skinningMatrices.length; i++) {
            int nodeIndex = jointNodeIndices[i];
            if (nodeIndex >= 0 && nodeIndex < globalNodeTransforms.length) {
                Matrix4f ibm = ibms != null && i < ibms.length ? ibms[i] : new Matrix4f();
                skinningMatrices[i].set(globalNodeTransforms[nodeIndex]).mul(ibm);
            } else {
                skinningMatrices[i].identity();
            }
        }
    }

    private void skinVertex(float x, float y, float z, float nx, float ny, float nz,
                            int[] jointIndices, float[] jointWeights, int vertexIndex) {
        skinnedPos.set(0, 0, 0, 0);
        skinnedNormal.set(0, 0, 0);

        for (int i = 0; i < 4; i++) {
            int jointOffset = vertexIndex * 4 + i;
            if (jointOffset >= jointIndices.length || jointOffset >= jointWeights.length) {
                break;
            }

            int jointIndex = jointIndices[jointOffset];
            float weight = jointWeights[jointOffset];
            if (weight < 0.0001f || jointIndex < 0 || jointIndex >= skinningMatrices.length) {
                continue;
            }

            Matrix4f jointMatrix = skinningMatrices[jointIndex];
            tempPos.set(x, y, z, 1.0f);
            jointMatrix.transform(tempPos, tempPos);
            skinnedPos.add(tempPos.x * weight, tempPos.y * weight, tempPos.z * weight, tempPos.w * weight);

            tempNormal.set(nx, ny, nz);
            jointMatrix.transformDirection(tempNormal, tempNormal);
            skinnedNormal.add(tempNormal.mul(weight));
        }

        if (skinnedPos.w == 0.0f) {
            skinnedPos.set(x, y, z, 1.0f);
            skinnedNormal.set(nx, ny, nz);
        } else if (skinnedPos.w != 1.0f) {
            float invW = 1.0f / skinnedPos.w;
            skinnedPos.x *= invW;
            skinnedPos.y *= invW;
            skinnedPos.z *= invW;
            skinnedPos.w = 1.0f;
        }
    }

    private static void normalize(Vector3f vector) {
        float length = vector.length();
        if (length > 0.001f) {
            vector.div(length);
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        GL15.glDeleteBuffers(vertexBuffer);
        GL15.glDeleteBuffers(indexBuffer);
        GL30.glDeleteVertexArrays(vao);
    }
}
