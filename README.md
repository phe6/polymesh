# Polymesh

Polymesh is a Forge/NeoForge 1.20.1 client runtime for rendering glTF 2.0 models from mod resources.

- **Developer guide:** [docs/POLYMESH_PROJECT.md](docs/POLYMESH_PROJECT.md)
- **Releases:** <https://github.com/phe6/polymesh/releases>
- **Issues:** <https://github.com/phe6/polymesh/issues>

## Packaging

- Runtime library jar: `polymesh-1.0.0-forge.jar`
- glTF assets are discovered under `assets/<modid>/models/gltf/*.gltf` and `*.glb`.

## Item Registration

Register the item normally, then register its glTF model during client setup:

```java
PolymeshApi.registerItemRenderer(item, new ResourceLocation("mymod", "model_name"),
    GltfRenderOptions.builder()
        .scale(1.0f)
        .animationClip("Walk")
        .loopAnimation(true)
        .build());
```

The item still needs a custom renderer from `initializeClient`; return `new GltfItemRenderer(theItem)` there — it reads the model id and options registered above, so they live in exactly one place.

## Runtime Notes

- Static non-skinned meshes are uploaded once to shared GPU buffers and reused by item instances.
- Skinned or morph-target item meshes use persistent VAO/VBO/EBO objects with dynamic vertex-buffer refreshes keyed by animation pose version. This avoids per-frame `VertexConsumer` triangle emission and only recomputes deformed vertices when animation, skinning, or morph pose changes.
- Normals from glTF are preserved. Missing normals are generated once at load time.
- Tangents from glTF are preserved. Missing tangents are generated when UVs are available for runtime data consumers.
- Embedded `.glb` images are registered as dynamic Minecraft textures during model load.
- Resource reload clears GPU buffers and rebuilds loaded models.
- Item, entity, and block-entity renderers all share the same render backend (`PolymeshRenderer`), which prefers the GPU static/animated paths and falls back to the CPU vertex-consumer path when needed (e.g. under an active shader pack).
- glTF `extras`, extension lists, node names, morph target names, and posed node transforms are kept available for game logic.

## Supported Features

- glTF 2.0 `.gltf` with relative buffers/images
- binary `.glb`
- node hierarchy and TRS/matrix transforms
- materials with base color texture/factor and alpha modes
- skins, inverse bind matrices, morph targets, and animation channels for item rendering
- runtime node/bone lookup for attachment points, hitbox drivers, and gameplay code
- named morph target lookup and runtime morph weight overrides
- glTF `extras` metadata on models, nodes, and materials

## Shader Notes

Polymesh preserves the normals authored in the glTF asset and renders through vanilla-style entity render states. It does not claim shader-pack-side smooth-normal fixes; shader packs that derive lighting from their own geometry pipeline may still need shader-side support.

## License

Polymesh — a glTF 2.0 model rendering library for Minecraft Forge/NeoForge 1.20.1
Copyright (C) 2026 Phe

This library is free software: you can redistribute it and/or modify it under the terms of the
GNU Lesser General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License along with this library
(see [LICENSE.LESSER](LICENSE.LESSER) and [LICENSE](LICENSE)). If not, see
<https://www.gnu.org/licenses/>.

Bundled dependencies: [jgltf](https://github.com/javagl/JglTF) (MIT) and
[Jackson](https://github.com/FasterXML/jackson) (Apache-2.0) are repackaged inside the forge jar
under their own licenses.
