package dev.phe.polymesh.client;

import dev.phe.polymesh.model.RuntimeMesh;
import dev.phe.polymesh.model.RuntimeModel;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class GltfGpuModelCache {
    public static final GltfGpuModelCache INSTANCE = new GltfGpuModelCache();

    private final Map<RuntimeModel, SharedGpuModel> models = new IdentityHashMap<>();
    private final Set<AutoCloseable> dynamicResources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private GltfGpuModelCache() {
    }

    public SharedGpuModel getOrCreate(RuntimeModel model) {
        RenderSystem.assertOnRenderThread();
        return models.computeIfAbsent(model, SharedGpuModel::new);
    }

    public void closeAll() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::closeAll);
            return;
        }

        AutoCloseable[] dynamic;
        synchronized (dynamicResources) {
            dynamic = dynamicResources.toArray(AutoCloseable[]::new);
            dynamicResources.clear();
        }
        for (AutoCloseable resource : dynamic) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
        for (SharedGpuModel model : models.values()) {
            model.close();
        }
        models.clear();
    }

    void registerDynamic(AutoCloseable resource) {
        synchronized (dynamicResources) {
            dynamicResources.add(resource);
        }
    }

    void unregisterDynamic(AutoCloseable resource) {
        synchronized (dynamicResources) {
            dynamicResources.remove(resource);
        }
    }

    public static final class SharedGpuModel implements AutoCloseable {
        private final SharedGpuMesh[] meshes;
        private final boolean fullyStatic;

        private SharedGpuModel(RuntimeModel model) {
            this.meshes = new SharedGpuMesh[model.getMeshCount()];
            boolean allStatic = true;
            for (int i = 0; i < model.getMeshCount(); i++) {
                RuntimeMesh mesh = model.getMeshes().get(i);
                if (isGpuStatic(mesh)) {
                    meshes[i] = new SharedGpuMesh(mesh);
                } else {
                    allStatic = false;
                }
            }
            this.fullyStatic = allStatic;
        }

        public boolean isFullyStatic() {
            return fullyStatic;
        }

        SharedGpuMesh getMesh(int index) {
            return index >= 0 && index < meshes.length ? meshes[index] : null;
        }

        private static boolean isGpuStatic(RuntimeMesh mesh) {
            return mesh.getVertexCount() > 0
                && mesh.getIndices().length > 0
                && !mesh.hasSkinning()
                && !mesh.hasMorphTargets();
        }

        @Override
        public void close() {
            for (SharedGpuMesh mesh : meshes) {
                if (mesh != null) {
                    mesh.close();
                }
            }
        }
    }
}
