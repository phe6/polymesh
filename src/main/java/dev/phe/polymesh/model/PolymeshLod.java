package dev.phe.polymesh.model;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record PolymeshLod(int level, float distanceBlocks, @Nullable ResourceLocation model) {
}
