package dev.phe.polymesh.model;

public class RuntimeMaterial {
    public enum AlphaMode {
        OPAQUE,
        MASK,
        BLEND
    }

    private final String name;
    private final String diffuseTexture;
    private final String emissiveTexture;
    private final String normalTexture;
    private final String metallicRoughnessTexture;
    private final String occlusionTexture;
    private final float[] diffuseColor;
    private final float[] emissiveColor;
    private final float emissiveStrength;
    private final float roughnessFactor;
    private final float metallicFactor;
    private final AlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;
    private final RuntimeExtras extras;

    public RuntimeMaterial(String name, String diffuseTexture, String emissiveTexture,
                          float[] diffuseColor, float[] emissiveColor,
                          float emissiveStrength, AlphaMode alphaMode,
                          float alphaCutoff, boolean doubleSided) {
        this(name, diffuseTexture, emissiveTexture, diffuseColor, emissiveColor, emissiveStrength,
            alphaMode, alphaCutoff, doubleSided, RuntimeExtras.EMPTY);
    }

    public RuntimeMaterial(String name, String diffuseTexture, String emissiveTexture,
                          float[] diffuseColor, float[] emissiveColor,
                          float emissiveStrength, AlphaMode alphaMode,
                          float alphaCutoff, boolean doubleSided, RuntimeExtras extras) {
        this(name, diffuseTexture, emissiveTexture, null, null, null, diffuseColor, emissiveColor,
            emissiveStrength, 1.0f, 1.0f, alphaMode, alphaCutoff, doubleSided, extras);
    }

    public RuntimeMaterial(String name, String diffuseTexture, String emissiveTexture,
                          String normalTexture, String metallicRoughnessTexture, String occlusionTexture,
                          float[] diffuseColor, float[] emissiveColor, float emissiveStrength,
                          float roughnessFactor, float metallicFactor, AlphaMode alphaMode,
                          float alphaCutoff, boolean doubleSided, RuntimeExtras extras) {
        this.name = name;
        this.diffuseTexture = diffuseTexture;
        this.emissiveTexture = emissiveTexture;
        this.normalTexture = normalTexture;
        this.metallicRoughnessTexture = metallicRoughnessTexture;
        this.occlusionTexture = occlusionTexture;
        this.diffuseColor = diffuseColor;
        this.emissiveColor = emissiveColor;
        this.emissiveStrength = emissiveStrength;
        this.roughnessFactor = roughnessFactor;
        this.metallicFactor = metallicFactor;
        this.alphaMode = alphaMode;
        this.alphaCutoff = alphaCutoff;
        this.doubleSided = doubleSided;
        this.extras = extras != null ? extras : RuntimeExtras.EMPTY;
    }

    public String getName() { return name; }
    public String getDiffuseTexture() { return diffuseTexture; }
    public String getEmissiveTexture() { return emissiveTexture; }
    public String getNormalTexture() { return normalTexture; }
    public String getMetallicRoughnessTexture() { return metallicRoughnessTexture; }
    public String getOcclusionTexture() { return occlusionTexture; }
    public float[] getDiffuseColor() { return diffuseColor; }
    public float[] getEmissiveColor() { return emissiveColor; }
    public float getEmissiveStrength() { return emissiveStrength; }
    public float getRoughnessFactor() { return roughnessFactor; }
    public float getMetallicFactor() { return metallicFactor; }
    public AlphaMode getAlphaMode() { return alphaMode; }
    public float getAlphaCutoff() { return alphaCutoff; }
    public boolean isDoubleSided() { return doubleSided; }
    public RuntimeExtras getExtras() { return extras; }

    public static RuntimeMaterial defaultMaterial() {
        return new RuntimeMaterial(
            "default",
            null,
            null,
            new float[] { 1.0f, 1.0f, 1.0f, 1.0f },
            new float[] { 0.0f, 0.0f, 0.0f },
            0.0f,
            AlphaMode.OPAQUE,
            0.5f,
            false
        );
    }
}
