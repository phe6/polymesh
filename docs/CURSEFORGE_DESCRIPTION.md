# Polymesh

Polymesh is a NeoForge 1.20.1 library for mods that want to use real glTF 2.0 models in Minecraft.

Instead of converting everything into Minecraft cubes or a custom model format, Polymesh loads `.gltf` and `.glb` assets from your mod resources and renders them at runtime. It is built for high-detail items, animated models, Blender-made assets, and future gameplay systems that need access to model bones, morph targets, and metadata.

## What It Does

- Loads glTF 2.0 `.gltf` and `.glb` files from mod assets.
- Renders static item models through shared GPU buffers.
- Renders animated/skinned item models with persistent GPU buffers.
- Supports glTF node hierarchy, transforms, skins, inverse bind matrices, and animations.
- Supports morph targets / shape keys, including named morph targets exported by Blender.
- Preserves glTF normals and generates missing normals when needed.
- Preserves tangents and generates fallback tangents when UVs are available.
- Loads embedded `.glb` textures and relative `.gltf` image files.
- Exposes runtime model data for gameplay code: nodes, bones, posed transforms, morph weights, and glTF `extras`.

## Who It Is For

Polymesh is mainly for mod developers.

Use it if you want:

- Blender-authored models in Minecraft.
- Better mesh support than cube-only formats.
- Animated glTF items or entities.
- Runtime bone access for attachments, effects, hitboxes, or gameplay logic.
- A library that can grow into a full rendering and model-runtime layer.

Players only need to install Polymesh when another mod depends on it.

## Current Status

Polymesh already supports static glTF item rendering, animated/skinned item rendering, model metadata, morph targets, and resource reload cleanup.

Entity and block renderer helpers are included and share the same GPU-backed render path as items. Physics, collision, and deeper gameplay integration are planned future work.

## Notes

Polymesh keeps glTF-authored normals intact and works with Iris/Oculus shader packs: it auto-detects an active pack and routes through the shader-compatible vertex path, so smooth-shaded models stay smooth. If a model looks faceted under a pack, check the pack's own entity-normal setting — some derive normals from screen geometry rather than trusting vertex normals.

Target version: NeoForge/Forge 1.20.1.

License: LGPL-3.0-or-later.
