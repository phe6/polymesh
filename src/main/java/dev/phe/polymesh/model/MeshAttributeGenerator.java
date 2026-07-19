package dev.phe.polymesh.model;

import org.joml.Vector3f;

final class MeshAttributeGenerator {
    private MeshAttributeGenerator() {
    }

    static float[] generateSmoothNormals(float[] positions, int[] indices) {
        int vertexCount = positions.length / 3;
        float[] normals = new float[vertexCount * 3];
        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f faceNormal = new Vector3f();

        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            if (!validVertex(i0, vertexCount) || !validVertex(i1, vertexCount) || !validVertex(i2, vertexCount)) {
                continue;
            }

            edge1.set(
                positions[i1 * 3] - positions[i0 * 3],
                positions[i1 * 3 + 1] - positions[i0 * 3 + 1],
                positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            );
            edge2.set(
                positions[i2 * 3] - positions[i0 * 3],
                positions[i2 * 3 + 1] - positions[i0 * 3 + 1],
                positions[i2 * 3 + 2] - positions[i0 * 3 + 2]
            );
            edge1.cross(edge2, faceNormal);
            if (faceNormal.lengthSquared() <= 1.0e-12f) {
                continue;
            }

            addNormal(normals, i0, faceNormal);
            addNormal(normals, i1, faceNormal);
            addNormal(normals, i2, faceNormal);
        }

        for (int i = 0; i < vertexCount; i++) {
            Vector3f normal = new Vector3f(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
            if (normal.lengthSquared() <= 1.0e-12f) {
                normal.set(0.0f, 1.0f, 0.0f);
            } else {
                normal.normalize();
            }
            normals[i * 3] = normal.x;
            normals[i * 3 + 1] = normal.y;
            normals[i * 3 + 2] = normal.z;
        }

        return normals;
    }

    static float[] generateTangents(float[] positions, float[] normals, float[] uvs, int[] indices) {
        int vertexCount = positions.length / 3;
        float[] tangents = new float[vertexCount * 4];
        Vector3f[] tan1 = new Vector3f[vertexCount];
        Vector3f[] tan2 = new Vector3f[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            tan1[i] = new Vector3f();
            tan2[i] = new Vector3f();
        }

        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            if (!validVertex(i0, vertexCount) || !validVertex(i1, vertexCount) || !validVertex(i2, vertexCount)) {
                continue;
            }

            float x1 = positions[i1 * 3] - positions[i0 * 3];
            float x2 = positions[i2 * 3] - positions[i0 * 3];
            float y1 = positions[i1 * 3 + 1] - positions[i0 * 3 + 1];
            float y2 = positions[i2 * 3 + 1] - positions[i0 * 3 + 1];
            float z1 = positions[i1 * 3 + 2] - positions[i0 * 3 + 2];
            float z2 = positions[i2 * 3 + 2] - positions[i0 * 3 + 2];

            float s1 = uvs[i1 * 2] - uvs[i0 * 2];
            float s2 = uvs[i2 * 2] - uvs[i0 * 2];
            float t1 = uvs[i1 * 2 + 1] - uvs[i0 * 2 + 1];
            float t2 = uvs[i2 * 2 + 1] - uvs[i0 * 2 + 1];
            float determinant = s1 * t2 - s2 * t1;
            if (Math.abs(determinant) <= 1.0e-12f) {
                continue;
            }

            float r = 1.0f / determinant;
            Vector3f sdir = new Vector3f(
                (t2 * x1 - t1 * x2) * r,
                (t2 * y1 - t1 * y2) * r,
                (t2 * z1 - t1 * z2) * r
            );
            Vector3f tdir = new Vector3f(
                (s1 * x2 - s2 * x1) * r,
                (s1 * y2 - s2 * y1) * r,
                (s1 * z2 - s2 * z1) * r
            );

            tan1[i0].add(sdir);
            tan1[i1].add(sdir);
            tan1[i2].add(sdir);
            tan2[i0].add(tdir);
            tan2[i1].add(tdir);
            tan2[i2].add(tdir);
        }

        for (int i = 0; i < vertexCount; i++) {
            Vector3f n = new Vector3f(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
            Vector3f t = new Vector3f(tan1[i]);
            if (t.lengthSquared() <= 1.0e-12f) {
                fallbackTangent(n, t);
            } else {
                t.sub(new Vector3f(n).mul(n.dot(t))).normalize();
            }

            Vector3f bitangent = new Vector3f();
            n.cross(t, bitangent);
            float handedness = bitangent.dot(tan2[i]) < 0.0f ? -1.0f : 1.0f;
            tangents[i * 4] = t.x;
            tangents[i * 4 + 1] = t.y;
            tangents[i * 4 + 2] = t.z;
            tangents[i * 4 + 3] = handedness;
        }

        return tangents;
    }

    private static boolean validVertex(int index, int vertexCount) {
        return index >= 0 && index < vertexCount;
    }

    private static void addNormal(float[] normals, int vertexIndex, Vector3f normal) {
        normals[vertexIndex * 3] += normal.x;
        normals[vertexIndex * 3 + 1] += normal.y;
        normals[vertexIndex * 3 + 2] += normal.z;
    }

    private static void fallbackTangent(Vector3f normal, Vector3f tangent) {
        Vector3f axis = Math.abs(normal.y) < 0.9f
            ? new Vector3f(0.0f, 1.0f, 0.0f)
            : new Vector3f(1.0f, 0.0f, 0.0f);
        axis.cross(normal, tangent).normalize();
    }
}
