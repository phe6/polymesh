package dev.phe.polymesh.model;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.phe.polymesh.client.GltfTextureManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AnimationModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.ImageModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.SkinModel;
import de.javagl.jgltf.model.io.GltfModelReader;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

public class JgltfModelAdapter {
    private static final float MODEL_UNIT_SCALE = 1.0f;
    public static RuntimeModel adapt(ResourceLocation location, InputStream inputStream, java.io.InputStream binStream) throws Exception {
        return adapt(location, inputStream, binStream, null);
    }

    public static RuntimeModel adapt(ResourceLocation location, InputStream inputStream, java.io.InputStream binStream, String externalBufferUri) throws Exception {
        return adapt(location, inputStream, binStream, externalBufferUri, Map.of());
    }

    public static RuntimeModel adapt(ResourceLocation location, InputStream inputStream, java.io.InputStream binStream, String externalBufferUri, Map<String, byte[]> externalResources) throws Exception {
        // Create a temp directory so jgltf can resolve relative .bin references
        Path tempDir = Files.createTempDirectory("polymesh_");
        Path tempFile = tempDir.resolve(location.getPath().substring(location.getPath().lastIndexOf('/') + 1));
        try {
            byte[] modelBytes = inputStream.readAllBytes();
            JsonObject gltfJson = parseGltfJson(location, modelBytes);
            Files.write(tempFile, modelBytes);

            // Write external .bin if provided
            if (binStream != null) {
                Path binFile = resolveExternalResourcePath(tempDir, externalBufferUri, tempFile);
                Files.createDirectories(binFile.getParent());
                Files.copy(binStream, binFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            for (Map.Entry<String, byte[]> entry : externalResources.entrySet()) {
                Path resourceFile = resolveExternalResourcePath(tempDir, entry.getKey(), tempFile);
                Files.createDirectories(resourceFile.getParent());
                Files.write(resourceFile, entry.getValue());
            }

            GltfModelReader reader = new GltfModelReader();
            GltfModel gltfModel = reader.read(tempFile);
            return adaptGltf(location, gltfModel, gltfJson);
        } finally {
            // Clean up temp directory
            try {
                java.util.stream.Stream<Path> walk = Files.walk(tempDir);
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
                walk.close();
            } catch (Exception ignored) {}
        }
    }

    private static Path resolveExternalResourcePath(Path tempDir, String externalResourceUri, Path tempFile) {
        if (externalResourceUri == null || externalResourceUri.isBlank()) {
            String binName = tempFile.getFileName().toString().replace(".gltf", ".bin").replace(".glb", ".bin");
            return tempDir.resolve(binName);
        }

        URI uri = URI.create(externalResourceUri);
        if (uri.isAbsolute()) {
            throw new IllegalArgumentException("External GLTF resource URI must be relative: " + externalResourceUri);
        }

        Path relativePath = Path.of(uri.getPath()).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..") || externalResourceUri.contains("\\")) {
            throw new IllegalArgumentException("Unsafe external GLTF resource URI: " + externalResourceUri);
        }

        Path resolved = tempDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(tempDir)) {
            throw new IllegalArgumentException("Unsafe external GLTF resource URI: " + externalResourceUri);
        }
        return resolved;
    }

    public static RuntimeModel adaptGltf(GltfModel gltfModel) {
        return adaptGltf(new ResourceLocation("polymesh", "anonymous"), gltfModel);
    }

    public static RuntimeModel adaptGltf(ResourceLocation location, GltfModel gltfModel) {
        return adaptGltf(location, gltfModel, null);
    }

    public static RuntimeModel adaptGltf(ResourceLocation location, GltfModel gltfModel, JsonObject gltfJson) {
        List<RuntimeNode> runtimeNodes = new ArrayList<>();
        List<RuntimeMesh> runtimeMeshes = new ArrayList<>();
        List<RuntimeSkin> runtimeSkins = new ArrayList<>();
        List<RuntimeAnimation> runtimeAnimations = new ArrayList<>();
        List<RuntimeMaterial> runtimeMaterials = new ArrayList<>();

        List<NodeModel> nodeModels = gltfModel.getNodeModels();
        List<MeshModel> meshModels = gltfModel.getMeshModels();

        // Parse materials first to build index map
        List<MaterialModel> glMaterials = gltfModel.getMaterialModels();
        java.util.IdentityHashMap<MaterialModel, Integer> materialIndexMap = new java.util.IdentityHashMap<>();
        for (int i = 0; i < glMaterials.size(); i++) {
            MaterialModel mat = glMaterials.get(i);
            materialIndexMap.put(mat, i);

            if (mat instanceof de.javagl.jgltf.model.v2.MaterialModelV2 matV2) {
                String diffuseTex = null;
                de.javagl.jgltf.model.TextureModel baseColorTex = matV2.getBaseColorTexture();
                if (baseColorTex != null) {
                    de.javagl.jgltf.model.ImageModel img = baseColorTex.getImageModel();
                    if (img != null) {
                        diffuseTex = resolveImageTexture(location, gltfModel, img);
                    }
                }

                String emissiveTex = null;
                de.javagl.jgltf.model.TextureModel emissiveTexModel = matV2.getEmissiveTexture();
                if (emissiveTexModel != null) {
                    de.javagl.jgltf.model.ImageModel img = emissiveTexModel.getImageModel();
                    if (img != null) {
                        emissiveTex = resolveImageTexture(location, gltfModel, img);
                    }
                }

                String normalTex = resolveTexture(location, gltfModel, matV2.getNormalTexture());
                String metallicRoughnessTex = resolveTexture(location, gltfModel, matV2.getMetallicRoughnessTexture());
                String occlusionTex = resolveTexture(location, gltfModel, matV2.getOcclusionTexture());
                float[] diffuseColor = matV2.getBaseColorFactor();
                if (diffuseColor == null) diffuseColor = new float[] {1.0f, 1.0f, 1.0f, 1.0f};

                float[] emissiveColor = matV2.getEmissiveFactor();
                if (emissiveColor == null) emissiveColor = new float[] {0.0f, 0.0f, 0.0f};

                RuntimeMaterial.AlphaMode alphaMode;
                de.javagl.jgltf.model.v2.MaterialModelV2.AlphaMode glAlpha = matV2.getAlphaMode();
                if (glAlpha == null) {
                    alphaMode = RuntimeMaterial.AlphaMode.OPAQUE;
                } else {
                    alphaMode = switch (glAlpha) {
                        case MASK -> RuntimeMaterial.AlphaMode.MASK;
                        case BLEND -> RuntimeMaterial.AlphaMode.BLEND;
                        default -> RuntimeMaterial.AlphaMode.OPAQUE;
                    };
                }

                runtimeMaterials.add(new RuntimeMaterial(
                    mat.getName() != null ? mat.getName() : "material_" + i,
                    diffuseTex,
                    emissiveTex,
                    normalTex,
                    metallicRoughnessTex,
                    occlusionTex,
                    diffuseColor,
                    emissiveColor,
                    emissiveStrength(gltfJson, i),
                    matV2.getRoughnessFactor(),
                    matV2.getMetallicFactor(),
                    alphaMode,
                    matV2.getAlphaCutoff(),
                    matV2.isDoubleSided(),
                    extrasFromArray(gltfJson, "materials", i)
                ));
            } else {
                runtimeMaterials.add(new RuntimeMaterial(
                    mat.getName() != null ? mat.getName() : "material_" + i,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new float[] { 1.0f, 1.0f, 1.0f, 1.0f },
                    new float[] { 0.0f, 0.0f, 0.0f },
                    0.0f,
                    1.0f,
                    1.0f,
                    RuntimeMaterial.AlphaMode.OPAQUE,
                    0.5f,
                    false,
                    extrasFromArray(gltfJson, "materials", i)
                ));
            }
        }

        // Add a default material if the model has none
        if (runtimeMaterials.isEmpty()) {
            runtimeMaterials.add(RuntimeMaterial.defaultMaterial());
        }

        // FIX: Process each mesh primitive into RuntimeMesh, and build a mapping
        // from (meshModelIndex, primitiveIndex) -> runtimeMeshIndex
        int[][] meshToRuntimeMesh = new int[meshModels.size()][];
        for (int m = 0; m < meshModels.size(); m++) {
            MeshModel mesh = meshModels.get(m);
            String[] morphTargetNames = morphTargetNames(gltfJson, m);
            List<MeshPrimitiveModel> primitives = mesh.getMeshPrimitiveModels();
            meshToRuntimeMesh[m] = new int[primitives.size()];
            for (int p = 0; p < primitives.size(); p++) {
                MeshPrimitiveModel prim = primitives.get(p);
                ModelVerifier.verifyPrimitive(location, m, p, prim);
                int materialIndex = 0; // default material index
                MaterialModel primMaterial = prim.getMaterialModel();
                if (primMaterial != null) {
                    Integer idx = materialIndexMap.get(primMaterial);
                    if (idx != null) {
                        materialIndex = idx;
                    }
                }
                RuntimeMesh runtimeMesh = convertPrimitive(prim, materialIndex, MODEL_UNIT_SCALE, morphTargetNames);
                if (runtimeMesh != null) {
                    meshToRuntimeMesh[m][p] = runtimeMeshes.size();
                    runtimeMeshes.add(runtimeMesh);
                } else {
                    meshToRuntimeMesh[m][p] = -1;
                }
            }
        }

        // FIX: Process nodes with proper multi-primitive support
        for (int i = 0; i < nodeModels.size(); i++) {
            NodeModel node = nodeModels.get(i);
            int[] childIndices = convertChildren(node, nodeModels);
            int parentIndex = findParentIndex(node, nodeModels);
            int skinIndex = -1;
            int meshIndex = -1;
            int[] meshIndices = new int[0];

            // Determine which RuntimeMesh corresponds to this node's first mesh primitive
            List<MeshModel> nodeMeshModels = node.getMeshModels();
            if (!nodeMeshModels.isEmpty()) {
                List<Integer> nodeRuntimeMeshes = new ArrayList<>();
                for (MeshModel nodeMeshModel : nodeMeshModels) {
                    int meshModelIdx = meshModels.indexOf(nodeMeshModel);
                    if (meshModelIdx >= 0) {
                        for (int runtimeMeshIndex : meshToRuntimeMesh[meshModelIdx]) {
                            if (runtimeMeshIndex >= 0) {
                                nodeRuntimeMeshes.add(runtimeMeshIndex);
                            }
                        }
                    }
                }
                meshIndices = new int[nodeRuntimeMeshes.size()];
                for (int j = 0; j < nodeRuntimeMeshes.size(); j++) {
                    meshIndices[j] = nodeRuntimeMeshes.get(j);
                }
                if (meshIndices.length > 0) {
                    meshIndex = meshIndices[0];
                }
            }

            // Get or compute local transform
            Matrix4f localTransform = convertNodeTransform(node);
            Vector3f translation = convertNodeTranslation(node, localTransform);
            Quaternionf rotation = convertNodeRotation(node, localTransform);
            Vector3f scale = convertNodeScale(node, localTransform);
            float[] morphWeights = node.getWeights();
            if (morphWeights == null && !nodeMeshModels.isEmpty()) {
                morphWeights = nodeMeshModels.get(0).getWeights();
            }
            if (morphWeights == null && meshIndex >= 0 && meshIndex < runtimeMeshes.size()) {
                int morphTargetCount = runtimeMeshes.get(meshIndex).getMorphTargetCount();
                if (morphTargetCount > 0) {
                    morphWeights = new float[morphTargetCount];
                }
            }

            String name = node.getName();
            if (name == null) {
                name = "node_" + i;
            }

            // Skin index
            SkinModel skinModel = node.getSkinModel();
            if (skinModel != null) {
                int sIdx = gltfModel.getSkinModels().indexOf(skinModel);
                if (sIdx < 0) {
                    sIdx = runtimeSkins.size();
                    runtimeSkins.add(convertSkin(skinModel, nodeModels));
                }
                skinIndex = sIdx;
            }

            runtimeNodes.add(new RuntimeNode(
                name, i, childIndices, meshIndices, skinIndex, localTransform, parentIndex,
                translation, rotation, scale, morphWeights, extrasFromArray(gltfJson, "nodes", i)));
        }

        // Process all skins
        List<SkinModel> skinModels = gltfModel.getSkinModels();
        runtimeSkins.clear(); // Clear and re-populate in order
        for (SkinModel skin : skinModels) {
            runtimeSkins.add(convertSkin(skin, nodeModels));
        }

        // Process animations
        List<AnimationModel> animationModels = gltfModel.getAnimationModels();
        for (AnimationModel anim : animationModels) {
            RuntimeAnimation runtimeAnim = convertAnimation(anim, nodeModels);
            if (runtimeAnim != null) {
                runtimeAnimations.add(runtimeAnim);
            }
        }

        AABB boundingBox = computeBoundingBox(runtimeNodes, runtimeMeshes);

        RuntimeModel runtimeModel = new RuntimeModel(
            runtimeNodes,
            runtimeMeshes,
            runtimeSkins,
            runtimeAnimations,
            runtimeMaterials,
            boundingBox,
            extrasFromRoot(gltfJson),
            stringList(gltfJson, "extensionsUsed"),
            stringList(gltfJson, "extensionsRequired")
        );
        ModelVerifier.verifyRuntimeModel(location, runtimeModel);
        return runtimeModel;
    }

    private static String resolveImageTexture(ResourceLocation location, GltfModel gltfModel, ImageModel image) {
        String uri = image.getUri();
        if (uri != null && !uri.isBlank()) {
            return uri;
        }

        int imageIndex = gltfModel.getImageModels().indexOf(image);
        if (imageIndex < 0) {
            imageIndex = 0;
        }
        ResourceLocation textureLocation = GltfTextureManager.INSTANCE.registerEmbeddedTexture(
            new ResourceLocation(location.getNamespace(), GltfModelManager.modelIdPath(location.getPath())),
            imageIndex,
            image.getImageData()
        );
        return textureLocation != null ? textureLocation.toString() : null;
    }

    private static String resolveTexture(ResourceLocation location, GltfModel gltfModel, de.javagl.jgltf.model.TextureModel texture) {
        if (texture == null || texture.getImageModel() == null) {
            return null;
        }
        return resolveImageTexture(location, gltfModel, texture.getImageModel());
    }

    private static int[] convertChildren(NodeModel node, List<NodeModel> allNodes) {
        List<NodeModel> children = node.getChildren();
        int[] childIndices = new int[children.size()];
        for (int i = 0; i < children.size(); i++) {
            childIndices[i] = allNodes.indexOf(children.get(i));
        }
        return childIndices;
    }

    private static int findParentIndex(NodeModel node, List<NodeModel> allNodes) {
        for (int i = 0; i < allNodes.size(); i++) {
            NodeModel parent = allNodes.get(i);
            if (parent.getChildren().contains(node)) {
                return i;
            }
        }
        return -1;
    }

    private static Matrix4f convertNodeTransform(NodeModel node) {
        Matrix4f result = new Matrix4f();
        float[] matrix = node.getMatrix();
        if (matrix != null && matrix.length >= 16) {
            result.set(
                matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4], matrix[5], matrix[6], matrix[7],
                matrix[8], matrix[9], matrix[10], matrix[11],
                matrix[12], matrix[13], matrix[14], matrix[15]
            );
        } else {
            float[] translation = node.getTranslation();
            float[] rotation = node.getRotation();
            float[] scale = node.getScale();

            if (translation != null && translation.length >= 3) {
                result.translate(translation[0], translation[1], translation[2]);
            }
            if (rotation != null && rotation.length >= 4) {
                Quaternionf q = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]);
                result.rotate(q);
            }
            if (scale != null && scale.length >= 3) {
                result.scale(scale[0], scale[1], scale[2]);
            }
        }
        return result;
    }

    private static Vector3f convertNodeTranslation(NodeModel node, Matrix4f localTransform) {
        float[] translation = node.getTranslation();
        if (translation != null && translation.length >= 3) {
            return new Vector3f(translation[0], translation[1], translation[2]);
        }
        return localTransform.getTranslation(new Vector3f());
    }

    private static Quaternionf convertNodeRotation(NodeModel node, Matrix4f localTransform) {
        float[] rotation = node.getRotation();
        if (rotation != null && rotation.length >= 4) {
            return new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]).normalize();
        }
        return localTransform.getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    private static Vector3f convertNodeScale(NodeModel node, Matrix4f localTransform) {
        float[] scale = node.getScale();
        if (scale != null && scale.length >= 3) {
            return new Vector3f(scale[0], scale[1], scale[2]);
        }
        return localTransform.getScale(new Vector3f());
    }

    // FIX: Added scale parameter to apply Blockbench conversion
    private static RuntimeMesh convertPrimitive(MeshPrimitiveModel primitive, int materialIndex, float scale,
                                                String[] morphTargetNames) {
        Map<String, AccessorModel> attributes = primitive.getAttributes();

        AccessorModel positionsAccessor = attributes.get("POSITION");
        AccessorModel normalsAccessor = attributes.get("NORMAL");
        AccessorModel uvsAccessor = attributes.get("TEXCOORD_0");
        AccessorModel jointsAccessor = attributes.get("JOINTS_0");
        AccessorModel weightsAccessor = attributes.get("WEIGHTS_0");
        AccessorModel indicesAccessor = primitive.getIndices();

        if (positionsAccessor == null) return null;

        float[] positions = readFloatAccessor(positionsAccessor, 3);
        // FIX: Apply scale to all position components
        if (scale != 1.0f) {
            for (int i = 0; i < positions.length; i++) {
                positions[i] *= scale;
            }
        }

        float[] normals = normalsAccessor != null ? readFloatAccessor(normalsAccessor, 3) : null;
        float[] uvs = uvsAccessor != null ? readFloatAccessor(uvsAccessor, 2) : null;
        AccessorModel tangentsAccessor = attributes.get("TANGENT");
        float[] tangents = tangentsAccessor != null ? readFloatAccessor(tangentsAccessor, 4) : null;
        int[] jointIndices = jointsAccessor != null ? readIntAccessor(jointsAccessor, 4) : null;
        float[] jointWeights = weightsAccessor != null ? readFloatAccessor(weightsAccessor, 4) : null;
        int[] indices = indicesAccessor != null ? readIntAccessor(indicesAccessor, 1) : null;
        if (indices == null) {
            indices = new int[positions.length / 3];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i;
            }
        }
        boolean generatedNormals = false;
        boolean generatedTangents = false;
        int vertexCount = positions.length / 3;
        if (normals == null || normals.length != vertexCount * 3) {
            normals = MeshAttributeGenerator.generateSmoothNormals(positions, indices);
            generatedNormals = true;
        }
        if ((tangents == null || tangents.length != vertexCount * 4)
            && uvs != null && uvs.length == vertexCount * 2) {
            tangents = MeshAttributeGenerator.generateTangents(positions, normals, uvs, indices);
            generatedTangents = true;
        }

        List<Map<String, AccessorModel>> targets = primitive.getTargets();
        float[][] morphPositionDeltas = null;
        float[][] morphNormalDeltas = null;
        if (targets != null && !targets.isEmpty()) {
            morphPositionDeltas = new float[targets.size()][];
            morphNormalDeltas = new float[targets.size()][];
            for (int i = 0; i < targets.size(); i++) {
                Map<String, AccessorModel> target = targets.get(i);
                AccessorModel positionDeltaAccessor = target.get("POSITION");
                if (positionDeltaAccessor != null) {
                    morphPositionDeltas[i] = readFloatAccessor(positionDeltaAccessor, 3);
                    if (scale != 1.0f) {
                        for (int j = 0; j < morphPositionDeltas[i].length; j++) {
                            morphPositionDeltas[i][j] *= scale;
                        }
                    }
                }
                AccessorModel normalDeltaAccessor = target.get("NORMAL");
                if (normalDeltaAccessor != null) {
                    morphNormalDeltas[i] = readFloatAccessor(normalDeltaAccessor, 3);
                }
            }
        }

        return new RuntimeMesh(positions, normals, uvs, tangents, indices, jointIndices, jointWeights,
            morphPositionDeltas, morphNormalDeltas, morphTargetNames, materialIndex,
            generatedNormals, generatedTangents);
    }

    private static float[] readFloatAccessor(AccessorModel accessor, int components) {
        int count = accessor.getCount();
        float[] result = new float[count * components];
        AccessorData data = accessor.getAccessorData();
        ByteBuffer buffer = data.createByteBuffer();
        int byteStride = components * accessor.getComponentSizeInBytes();
        int componentSize = accessor.getComponentSizeInBytes();

        for (int i = 0; i < count; i++) {
            int elementOffset = i * byteStride;
            for (int j = 0; j < components; j++) {
                int componentOffset = elementOffset + j * componentSize;
                result[i * components + j] = readComponentFloat(buffer, componentOffset,
                    accessor.getComponentType(), accessor.isNormalized());
            }
        }
        return result;
    }

    private static int[] readIntAccessor(AccessorModel accessor, int components) {
        int count = accessor.getCount();
        int[] result = new int[count * components];
        AccessorData data = accessor.getAccessorData();
        ByteBuffer buffer = data.createByteBuffer();
        int byteStride = components * accessor.getComponentSizeInBytes();
        int componentSize = accessor.getComponentSizeInBytes();

        for (int i = 0; i < count; i++) {
            int elementOffset = i * byteStride;
            for (int j = 0; j < components; j++) {
                int componentOffset = elementOffset + j * componentSize;
                result[i * components + j] = readComponentInt(buffer, componentOffset, accessor.getComponentType());
            }
        }
        return result;
    }

    private static float readComponentFloat(ByteBuffer buffer, int offset, int componentType, boolean normalized) {
        switch (componentType) {
            case 5120: // BYTE
                byte signedByte = buffer.get(offset);
                return normalized ? Math.max(signedByte / 127.0f, -1.0f) : signedByte;
            case 5121: // UNSIGNED_BYTE
                int unsignedByte = buffer.get(offset) & 0xFF;
                return normalized ? unsignedByte / 255.0f : unsignedByte;
            case 5122: // SHORT
                short signedShort = buffer.getShort(offset);
                return normalized ? Math.max(signedShort / 32767.0f, -1.0f) : signedShort;
            case 5123: // UNSIGNED_SHORT
                int unsignedShort = buffer.getShort(offset) & 0xFFFF;
                return normalized ? unsignedShort / 65535.0f : unsignedShort;
            case 5124: // INT
                int signedInt = buffer.getInt(offset);
                return normalized ? Math.max(signedInt / 2147483647.0f, -1.0f) : signedInt;
            case 5125: // UNSIGNED_INT
                long unsignedInt = buffer.getInt(offset) & 0xFFFFFFFFL;
                return normalized ? unsignedInt / 4294967295.0f : unsignedInt;
            case 5126: // FLOAT
                return buffer.getFloat(offset);
            default:
                return 0;
        }
    }

    private static int readComponentInt(ByteBuffer buffer, int offset, int componentType) {
        switch (componentType) {
            case 5120: // BYTE
                return buffer.get(offset);
            case 5121: // UNSIGNED_BYTE
                return buffer.get(offset) & 0xFF;
            case 5122: // SHORT
                return buffer.getShort(offset);
            case 5123: // UNSIGNED_SHORT
                return buffer.getShort(offset) & 0xFFFF;
            case 5124: // INT
                return buffer.getInt(offset);
            case 5125: // UNSIGNED_INT
                return (int) (buffer.getInt(offset) & 0xFFFFFFFFL);
            case 5126: // FLOAT
                return (int) buffer.getFloat(offset);
            default:
                return 0;
        }
    }

    private static RuntimeSkin convertSkin(SkinModel skin, List<NodeModel> nodeModels) {
        List<NodeModel> joints = skin.getJoints();
        int jointCount = joints.size();
        int[] jointNodeIndices = new int[jointCount];
        Matrix4f[] inverseBindMatrices = new Matrix4f[jointCount];

        for (int i = 0; i < jointCount; i++) {
            jointNodeIndices[i] = nodeModels.indexOf(joints.get(i));
            inverseBindMatrices[i] = new Matrix4f();
        }

        AccessorModel ibmAccessor = skin.getInverseBindMatrices();
        if (ibmAccessor != null) {
            float[] matrices = readFloatAccessor(ibmAccessor, 16);
            for (int i = 0; i < jointCount && i < ibmAccessor.getCount(); i++) {
                int offset = i * 16;
                inverseBindMatrices[i] = new Matrix4f(
                    matrices[offset], matrices[offset + 1], matrices[offset + 2], matrices[offset + 3],
                    matrices[offset + 4], matrices[offset + 5], matrices[offset + 6], matrices[offset + 7],
                    matrices[offset + 8], matrices[offset + 9], matrices[offset + 10], matrices[offset + 11],
                    matrices[offset + 12], matrices[offset + 13], matrices[offset + 14], matrices[offset + 15]
                );
            }
        }

        return new RuntimeSkin(jointNodeIndices, inverseBindMatrices);
    }

    private static RuntimeAnimation convertAnimation(AnimationModel animation, List<NodeModel> nodeModels) {
        String name = animation.getName();
        if (name == null) name = "animation";

        List<AnimationModel.Channel> channels = animation.getChannels();
        if (channels == null || channels.isEmpty()) {
            return new RuntimeAnimation(name, 0f, new ArrayList<>());
        }

        List<RuntimeChannel> runtimeChannels = new ArrayList<>();
        float maxDuration = 0f;

        for (AnimationModel.Channel channel : channels) {
            NodeModel targetNode = channel.getNodeModel();
            String pathStr = channel.getPath();
            AnimationModel.Sampler sampler = channel.getSampler();

            int targetNodeIndex = nodeModels.indexOf(targetNode);

            RuntimeChannel.TargetPath targetPath;
            int outputComponents;
            switch (pathStr) {
                case "translation":
                    targetPath = RuntimeChannel.TargetPath.TRANSLATION;
                    outputComponents = 3;
                    break;
                case "rotation":
                    targetPath = RuntimeChannel.TargetPath.ROTATION;
                    outputComponents = 4;
                    break;
                case "scale":
                    targetPath = RuntimeChannel.TargetPath.SCALE;
                    outputComponents = 3;
                    break;
                case "weights":
                    targetPath = RuntimeChannel.TargetPath.WEIGHTS;
                    outputComponents = determineMorphWeightCount(targetNode, nodeModels);
                    if (outputComponents <= 0) {
                        continue;
                    }
                    break;
                default: continue;
            }

            float[] inputTimes = readFloatAccessor(sampler.getInput(), 1);
            float[] outputValues = targetPath == RuntimeChannel.TargetPath.WEIGHTS
                ? readFloatAccessor(sampler.getOutput(), 1)
                : readFloatAccessor(sampler.getOutput(), outputComponents);

            if (inputTimes == null || outputValues == null) continue;

            RuntimeChannel.Interpolation interpolation = RuntimeChannel.Interpolation.LINEAR;
            AnimationModel.Interpolation interp = sampler.getInterpolation();
            if (interp != null) {
                switch (interp) {
                    case STEP: interpolation = RuntimeChannel.Interpolation.STEP; break;
                    case CUBICSPLINE: interpolation = RuntimeChannel.Interpolation.CUBICSPLINE; break;
                }
            }

            if (inputTimes.length > 0) {
                maxDuration = Math.max(maxDuration, inputTimes[inputTimes.length - 1]);
            }

            runtimeChannels.add(new RuntimeChannel(targetNodeIndex, targetPath, inputTimes, outputValues,
                interpolation, outputComponents));
        }

        return new RuntimeAnimation(name, maxDuration, runtimeChannels);
    }

    private static int determineMorphWeightCount(NodeModel targetNode, List<NodeModel> nodeModels) {
        if (targetNode == null) {
            return 0;
        }
        float[] nodeWeights = targetNode.getWeights();
        if (nodeWeights != null) {
            return nodeWeights.length;
        }
        for (MeshModel mesh : targetNode.getMeshModels()) {
            float[] meshWeights = mesh.getWeights();
            if (meshWeights != null) {
                return meshWeights.length;
            }
            List<MeshPrimitiveModel> primitives = mesh.getMeshPrimitiveModels();
            if (!primitives.isEmpty() && primitives.get(0).getTargets() != null) {
                return primitives.get(0).getTargets().size();
            }
        }
        return 0;
    }

    private static AABB computeBoundingBox(List<RuntimeNode> nodes, List<RuntimeMesh> meshes) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        Matrix4f[] globalTransforms = computeStaticGlobalTransforms(nodes);
        Vector4f transformed = new Vector4f();

        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            RuntimeNode node = nodes.get(nodeIndex);
            if (!node.hasMesh()) {
                continue;
            }

            Matrix4f nodeTransform = nodeIndex < globalTransforms.length
                ? globalTransforms[nodeIndex]
                : node.getLocalTransform();
            for (int meshIndex : node.getMeshIndices()) {
                if (meshIndex < 0 || meshIndex >= meshes.size()) {
                    continue;
                }

                RuntimeMesh mesh = meshes.get(meshIndex);
                float[] positions = mesh.getPositions();
                if (positions == null) {
                    continue;
                }

                for (int i = 0; i < positions.length; i += 3) {
                    transformed.set(positions[i], positions[i + 1], positions[i + 2], 1.0f);
                    nodeTransform.transform(transformed);
                    if (transformed.w != 0.0f && transformed.w != 1.0f) {
                        transformed.div(transformed.w);
                    }
                    minX = Math.min(minX, transformed.x);
                    minY = Math.min(minY, transformed.y);
                    minZ = Math.min(minZ, transformed.z);
                    maxX = Math.max(maxX, transformed.x);
                    maxY = Math.max(maxY, transformed.y);
                    maxZ = Math.max(maxZ, transformed.z);
                }
            }
        }

        if (minX == Float.MAX_VALUE) {
            return new AABB(-1, -1, -1, 1, 1, 1);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Matrix4f[] computeStaticGlobalTransforms(List<RuntimeNode> nodes) {
        Matrix4f[] transforms = new Matrix4f[nodes.size()];
        boolean[] computed = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            computeStaticGlobalTransform(i, nodes, transforms, computed);
        }
        return transforms;
    }

    private static Matrix4f computeStaticGlobalTransform(int nodeIndex, List<RuntimeNode> nodes,
                                                         Matrix4f[] transforms, boolean[] computed) {
        if (computed[nodeIndex]) {
            return transforms[nodeIndex];
        }

        RuntimeNode node = nodes.get(nodeIndex);
        Matrix4f transform = new Matrix4f(node.getLocalTransform());
        int parentIndex = node.getParentIndex();
        if (parentIndex >= 0 && parentIndex < nodes.size()) {
            transform = new Matrix4f(computeStaticGlobalTransform(parentIndex, nodes, transforms, computed)).mul(transform);
        }
        transforms[nodeIndex] = transform;
        computed[nodeIndex] = true;
        return transform;
    }

    private static JsonObject parseGltfJson(ResourceLocation location, byte[] modelBytes) {
        try {
            if (location.getPath().endsWith(".glb")) {
                return parseGlbJson(modelBytes);
            }
            JsonElement parsed = JsonParser.parseString(new String(modelBytes, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject parseGlbJson(byte[] modelBytes) {
        if (modelBytes.length < 20) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(modelBytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt();
        int version = buffer.getInt();
        int declaredLength = buffer.getInt();
        if (magic != 0x46546C67 || version != 2 || declaredLength > modelBytes.length) {
            return null;
        }
        while (buffer.remaining() >= 8) {
            int chunkLength = buffer.getInt();
            int chunkType = buffer.getInt();
            if (chunkLength < 0 || chunkLength > buffer.remaining()) {
                return null;
            }
            if (chunkType == 0x4E4F534A) {
                byte[] jsonBytes = new byte[chunkLength];
                buffer.get(jsonBytes);
                JsonElement parsed = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8).trim());
                return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
            buffer.position(buffer.position() + chunkLength);
        }
        return null;
    }

    private static RuntimeExtras extrasFromRoot(JsonObject root) {
        if (root == null) {
            return RuntimeExtras.EMPTY;
        }
        return RuntimeExtras.from(root.get("extras"));
    }

    private static RuntimeExtras extrasFromArray(JsonObject root, String arrayName, int index) {
        JsonObject object = objectFromArray(root, arrayName, index);
        return object != null ? RuntimeExtras.from(object.get("extras")) : RuntimeExtras.EMPTY;
    }

    private static float emissiveStrength(JsonObject root, int materialIndex) {
        JsonObject material = objectFromArray(root, "materials", materialIndex);
        if (material == null || !material.has("extensions") || !material.get("extensions").isJsonObject()) {
            return 0.0f;
        }
        JsonObject extensions = material.getAsJsonObject("extensions");
        if (!extensions.has("KHR_materials_emissive_strength") || !extensions.get("KHR_materials_emissive_strength").isJsonObject()) {
            return 0.0f;
        }
        JsonObject extension = extensions.getAsJsonObject("KHR_materials_emissive_strength");
        JsonElement strength = extension.get("emissiveStrength");
        return strength != null && strength.isJsonPrimitive() ? strength.getAsFloat() : 0.0f;
    }

    private static JsonObject objectFromArray(JsonObject root, String arrayName, int index) {
        if (root == null || !root.has(arrayName) || !root.get(arrayName).isJsonArray()) {
            return null;
        }
        JsonArray array = root.getAsJsonArray(arrayName);
        if (index < 0 || index >= array.size()) {
            return null;
        }
        JsonElement element = array.get(index);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static List<String> stringList(JsonObject root, String key) {
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = root.getAsJsonArray(key);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static String[] morphTargetNames(JsonObject root, int meshIndex) {
        JsonObject mesh = objectFromArray(root, "meshes", meshIndex);
        if (mesh == null || !mesh.has("extras") || !mesh.get("extras").isJsonObject()) {
            return new String[0];
        }
        JsonObject extras = mesh.getAsJsonObject("extras");
        if (!extras.has("targetNames") || !extras.get("targetNames").isJsonArray()) {
            return new String[0];
        }
        JsonArray names = extras.getAsJsonArray("targetNames");
        String[] result = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            JsonElement element = names.get(i);
            result[i] = element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        }
        return result;
    }
}
