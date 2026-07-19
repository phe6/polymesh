package dev.phe.polymesh.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether an Iris/Oculus shader pack is currently rendering, without a hard dependency on
 * the shader mod. Mirrors the approach the BlazeRod library (TouchController/armorstand) uses:
 * ask Iris's public API {@code IrisApi.getInstance().isShaderPackInUse()} by reflection, and if
 * Iris is absent (or the API shifted), report false.
 *
 * <p>Why this exists: Polymesh's fast GPU path ({@code SharedGpuMesh}) issues raw GL draws against
 * {@code RenderSystem.getShader()} with hardcoded vertex-attribute indices. When a shader pack is
 * active, Iris replaces the entity {@code ShaderInstance} with an "extended" program that expects
 * Iris's own vertex layout (mc_Entity / mc_midTexCoord / at_tangent) at its own attribute
 * locations, so the raw GL path feeds it wrong data and the model renders incorrectly (flat /
 * mislit). The CPU vertex-consumer path, by contrast, flows through Iris's BufferBuilder mixin,
 * which extends {@code NEW_ENTITY} correctly and preserves our smooth per-vertex normals. So the
 * safe, shipping fix is: when a pack is in use, route through the vertex-consumer path.
 *
 * <p>The result is cached briefly (a shader pack is not toggled mid-frame) to keep the reflection
 * cost off the hot render loop.
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("polymesh");
    private static final long CACHE_NANOS = 50_000_000L; // 50ms: cheap, and far finer than any toggle

    private static Boolean irisPresent;      // null until first probed
    private static MethodHandle isShaderPackInUse; // null if unavailable
    private static boolean resolved;

    private static long cacheStamp = Long.MIN_VALUE;
    private static boolean cachedInUse;

    private IrisCompat() {}

    /**
     * @return true when Iris/Oculus is loaded AND a shader pack is currently active. Always false
     *         when the shader mod is absent or its API could not be reached.
     */
    public static boolean shaderPackInUse() {
        long now = System.nanoTime();
        if (now - cacheStamp < CACHE_NANOS) {
            return cachedInUse;
        }
        cacheStamp = now;
        cachedInUse = probe();
        return cachedInUse;
    }

    private static boolean probe() {
        if (!resolved) {
            resolve();
        }
        if (isShaderPackInUse == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUse.invoke();
        } catch (Throwable t) {
            // API drift or an internal Iris error must never break rendering: treat as "no pack".
            isShaderPackInUse = null;
            return false;
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            irisPresent = ModList.get() != null
                && (ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris"));
        } catch (Throwable t) {
            irisPresent = false;
        }
        if (!Boolean.TRUE.equals(irisPresent)) {
            return;
        }
        // net.irisshaders.iris.api.v0.IrisApi#getInstance()#isShaderPackInUse() — the stable v0 API
        // shared by Iris (Fabric) and Oculus (Forge). Bind it via a cached MethodHandle bound to the
        // singleton instance so each call is a single virtual invoke.
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle handle = lookup.unreflect(apiClass.getMethod("isShaderPackInUse"));
            isShaderPackInUse = handle.bindTo(instance);
            LOGGER.info("Polymesh: Iris/Oculus API detected; shader-pack render compatibility active.");
        } catch (Throwable t) {
            isShaderPackInUse = null;
            LOGGER.warn("Polymesh: Iris/Oculus is loaded but its shader-detection API was unreachable "
                + "({}); GPU meshes will not auto-fall-back under shader packs.", t.toString());
        }
    }
}
