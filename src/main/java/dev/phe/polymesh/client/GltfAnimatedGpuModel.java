package dev.phe.polymesh.client;

import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeModel;
import dev.phe.polymesh.model.RuntimeSkin;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

final class GltfAnimatedGpuModel implements AutoCloseable {
    private final RuntimeModel model;
    private final Map<Long, NodeMeshBuffer> nodeMeshes = new HashMap<>();
    private boolean closed;

    GltfAnimatedGpuModel(RuntimeModel model) {
        RenderSystem.assertOnRenderThread();
        this.model = model;
        GltfGpuModelCache.INSTANCE.registerDynamic(this);
    }

    boolean owns(RuntimeModel candidate) {
        return model == candidate && !closed;
    }

    SharedGpuMesh getOrUpload(int nodeIndex, int meshIndex, RuntimeMesh mesh,
                              Matrix4f[] globalNodeTransforms, Matrix4f nodeTransform,
                              float[] morphWeights, RuntimeSkin skin, int poseVersion) {
        RenderSystem.assertOnRenderThread();
        if (closed) {
            return null;
        }

        long key = (((long) nodeIndex) << 32) ^ (meshIndex & 0xFFFFFFFFL);
        NodeMeshBuffer buffer = nodeMeshes.computeIfAbsent(key, unused -> new NodeMeshBuffer(SharedGpuMesh.dynamic(mesh)));
        if (buffer.lastPoseVersion != poseVersion) {
            buffer.mesh.uploadDeformed(mesh, globalNodeTransforms, nodeTransform, morphWeights, skin);
            buffer.lastPoseVersion = poseVersion;
        }
        return buffer.mesh;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        GltfGpuModelCache.INSTANCE.unregisterDynamic(this);
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::closeBuffers);
            return;
        }
        closeBuffers();
    }

    private void closeBuffers() {
        for (NodeMeshBuffer buffer : nodeMeshes.values()) {
            buffer.mesh.close();
        }
        nodeMeshes.clear();
    }

    private static final class NodeMeshBuffer {
        private final SharedGpuMesh mesh;
        private int lastPoseVersion = Integer.MIN_VALUE;

        private NodeMeshBuffer(SharedGpuMesh mesh) {
            this.mesh = mesh;
        }
    }
}
