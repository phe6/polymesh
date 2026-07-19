package dev.phe.polymesh.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PolymeshRenderStats {
    private int visibleInstances;
    private int culledInstances;
    private int drawCalls;
    private int bufferUploads;
    private final Map<Integer, Integer> lodDistribution = new HashMap<>();

    public void clear() {
        visibleInstances = 0;
        culledInstances = 0;
        drawCalls = 0;
        bufferUploads = 0;
        lodDistribution.clear();
    }

    public void recordVisible(int lodLevel) {
        visibleInstances++;
        lodDistribution.merge(lodLevel, 1, Integer::sum);
    }

    public void recordCulled() {
        culledInstances++;
    }

    public void recordDrawCall() {
        drawCalls++;
    }

    public void recordBufferUpload() {
        bufferUploads++;
    }

    public int visibleInstances() {
        return visibleInstances;
    }

    public int culledInstances() {
        return culledInstances;
    }

    public int drawCalls() {
        return drawCalls;
    }

    public int bufferUploads() {
        return bufferUploads;
    }

    public Map<Integer, Integer> lodDistribution() {
        return Collections.unmodifiableMap(lodDistribution);
    }
}
