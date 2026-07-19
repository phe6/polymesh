package dev.phe.polymesh.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelVerifier.class);
    private static final int GL_TRIANGLES = 4;

    private ModelVerifier() {
    }

    public static void verifyPrimitive(ResourceLocation location, int meshIndex, int primitiveIndex, MeshPrimitiveModel primitive) {
        List<String> warnings = new ArrayList<>();
        Map<String, AccessorModel> attributes = primitive.getAttributes();
        AccessorModel positions = attributes.get("POSITION");
        AccessorModel normals = attributes.get("NORMAL");
        AccessorModel tangents = attributes.get("TANGENT");
        AccessorModel uvs = attributes.get("TEXCOORD_0");
        AccessorModel joints = attributes.get("JOINTS_0");
        AccessorModel weights = attributes.get("WEIGHTS_0");

        if (primitive.getMode() != GL_TRIANGLES) {
            warnings.add("primitive mode " + primitive.getMode() + " is unsupported; only TRIANGLES render correctly");
        }
        if (positions == null) {
            warnings.add("missing POSITION accessor");
        } else if (positions.getCount() <= 0) {
            warnings.add("POSITION accessor has no vertices");
        }

        int vertexCount = positions != null ? positions.getCount() : 0;
        checkAccessorCount(warnings, "NORMAL", normals, vertexCount);
        checkAccessorCount(warnings, "TANGENT", tangents, vertexCount);
        checkAccessorCount(warnings, "TEXCOORD_0", uvs, vertexCount);
        checkAccessorCount(warnings, "JOINTS_0", joints, vertexCount);
        checkAccessorCount(warnings, "WEIGHTS_0", weights, vertexCount);

        if ((joints == null) != (weights == null)) {
            warnings.add("skinning data is incomplete; JOINTS_0 and WEIGHTS_0 must both be present");
        }
        if (primitive.getIndices() != null && primitive.getIndices().getCount() % 3 != 0) {
            warnings.add("index count " + primitive.getIndices().getCount() + " is not divisible by 3");
        }
        if (!warnings.isEmpty()) {
            LOGGER.warn("GLTF validation {} mesh {} primitive {}: {}", location, meshIndex, primitiveIndex, String.join("; ", warnings));
        }
    }

    public static void verifyRuntimeModel(ResourceLocation location, RuntimeModel model) {
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < model.getMeshCount(); i++) {
            RuntimeMesh mesh = model.getMeshes().get(i);
            int vertexCount = mesh.getVertexCount();
            if (mesh.getPositions().length != vertexCount * 3) {
                warnings.add("mesh " + i + " has malformed positions");
            }
            if (mesh.getNormals() == null || mesh.getNormals().length != vertexCount * 3) {
                warnings.add("mesh " + i + " has no valid normals");
            }
            if (mesh.getUvs() != null && mesh.getUvs().length != vertexCount * 2) {
                warnings.add("mesh " + i + " has malformed TEXCOORD_0");
            }
            if (mesh.hasTangents() && mesh.getTangents().length != vertexCount * 4) {
                warnings.add("mesh " + i + " has malformed tangents");
            }
            if (mesh.hasSkinning()) {
                if (mesh.getJointIndices().length != vertexCount * 4 || mesh.getJointWeights().length != vertexCount * 4) {
                    warnings.add("mesh " + i + " has malformed skinning arrays");
                }
            }
            if (mesh.getIndices().length % 3 != 0) {
                warnings.add("mesh " + i + " has a non-triangle index count");
            }
            if (mesh.hasGeneratedNormals()) {
                warnings.add("mesh " + i + " is using generated smooth normals");
            }
            if (mesh.hasGeneratedTangents()) {
                warnings.add("mesh " + i + " is using generated tangent fallback");
            }
        }

        // The skinning pipelines cap joint matrices at 256; joints past that are silently ignored.
        for (int i = 0; i < model.getSkinCount(); i++) {
            RuntimeSkin skin = model.getSkins().get(i);
            int jointCount = skin.getJointNodeIndices() != null ? skin.getJointNodeIndices().length : 0;
            if (jointCount > 256) {
                warnings.add("skin " + i + " has " + jointCount + " joints; only the first 256 are applied");
            }
        }

        if (!warnings.isEmpty()) {
            LOGGER.warn("GLTF runtime validation {}: {}", location, String.join("; ", warnings));
        }
    }

    private static void checkAccessorCount(List<String> warnings, String name, AccessorModel accessor, int vertexCount) {
        if (accessor != null && vertexCount > 0 && accessor.getCount() != vertexCount) {
            warnings.add(name + " count " + accessor.getCount() + " does not match POSITION count " + vertexCount);
        }
    }
}
