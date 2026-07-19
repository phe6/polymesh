package dev.phe.polymesh.model;

import java.util.List;

public class RuntimeAnimation {
    private final String name;
    private final float duration;
    private final List<RuntimeChannel> channels;

    public RuntimeAnimation(String name, float duration, List<RuntimeChannel> channels) {
        this.name = name;
        this.duration = duration;
        this.channels = channels;
    }

    public String getName() { return name; }
    public float getDuration() { return duration; }
    public List<RuntimeChannel> getChannels() { return channels; }
}