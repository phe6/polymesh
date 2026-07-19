# Polymesh Developer Integration Guide

Polymesh is a Forge 1.20.1 client library for rendering glTF 2.0 models in mods (NeoForge 1.20.1
uses the same Forge APIs and works identically). It lets you ship `.gltf` and `.glb` files in your
own mod resources, render them as items, entities, or block entities, and access runtime model
data such as nodes, bones, animation clips, morph targets, and glTF `extras`.

License: `LGPL-3.0-or-later`. Source and releases: <https://github.com/phe6/polymesh>.

## What You Can Use Today

- Static glTF rendering with shared GPU buffers.
- Animated/skinned glTF rendering with persistent GPU buffers and CPU deformation/upload,
  refreshed only when the pose actually changes.
- `.gltf` with relative `.bin` buffers and image files, and `.glb` with embedded textures.
- Node hierarchy, TRS/matrix transforms, skins, inverse bind matrices, and animations.
- Animation channels for translation, rotation, scale, and morph weights; LINEAR, STEP, and
  CUBICSPLINE interpolation; crossfade transitions between clips.
- Morph target position/normal deltas and Blender-style `mesh.extras.targetNames`.
- Runtime node lookup, posed transforms, posed positions, bone overrides, look-at aiming, and
  morph overrides.
- Model, node, and material `extras` metadata for gameplay code.
- Distance-based LOD switching and max-render-distance culling for entities and block entities.
- Basic glTF material support: base color, alpha modes, double-sided rendering.
- Resource reload cleanup for models, textures, and GPU buffers.
- Automatic Iris/Oculus shader-pack compatibility (see Shader Packs below).

Current limits:

- Animated rendering computes deformed vertices on the CPU before uploading (no GPU skinning yet).
- Emissive textures/factors and `KHR_materials_emissive_strength` are **parsed and exposed on
  `RuntimeMaterial` for your own code, but the built-in renderers do not yet render emissive**.
- PBR metallic/roughness, lights, variants, audio, and physics extensions are not rendered.
- No physics runtime, mesh collision, or mesh-driven hitbox system.
- `GltfRenderOptions.strictValidation` is reserved and currently has no effect; validation always
  runs in warning mode (see Validation Warnings).
- Item rendering does not LOD-switch or distance-cull (LOD applies to entities/block entities).

## Add Polymesh To Your Mod

Players install the release jar as a normal mod:

```text
polymesh-1.0.0-forge.jar
```

It bundles its jgltf and Jackson dependencies via jarJar, so players install nothing else.

For your **dev environment**, download `polymesh-1.0.0-dev.jar` (official/Mojang mappings, not
reobfuscated) from the GitHub release into your mod's `libs/` folder:

```gradle
dependencies {
    implementation files('libs/polymesh-1.0.0-dev.jar')

    // Polymesh's runtime dependencies, needed on the dev classpath
    // (the release jar bundles these for players):
    implementation 'de.javagl:jgltf-model:2.0.4'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.13.4.2'
}
```

Then require Polymesh in your `mods.toml`:

```toml
[[dependencies.your_mod]]
modId="polymesh"
mandatory=true
versionRange="[1.0.0,)"
ordering="AFTER"
side="CLIENT"
```

Polymesh itself is client-only in effect (`displayTest="IGNORE_ALL_VERSION"`): servers without it
accept clients that have it and vice versa.

## Put glTF Assets In Your Mod

Polymesh scans every namespace for:

```text
assets/<modid>/models/gltf/*.gltf
assets/<modid>/models/gltf/*.glb
```

Example: `assets/my_mod/models/gltf/robot.glb` becomes model id `my_mod:robot` in code:

```java
new ResourceLocation("my_mod", "robot")
```

For `.gltf`, keep relative buffers and images next to the model:

```text
assets/my_mod/models/gltf/robot.gltf
assets/my_mod/models/gltf/robot.bin
assets/my_mod/models/gltf/robot_basecolor.png
```

Embedded `.glb` images are registered as dynamic Minecraft textures at load time. Absolute URIs,
backslashes, and `..` path segments in resource references are rejected.

## Register A glTF Item

Two steps. First, register the model and options during client setup — this registry is the
single source of truth:

```java
private void onClientSetup(FMLClientSetupEvent event) {
    event.enqueueWork(() -> PolymeshApi.registerItemRenderer(
        MY_ITEM.get(),
        new ResourceLocation("my_mod", "robot"),
        GltfRenderOptions.builder()
            .scale(1.0f)
            .animationClip("Walk")
            .loopAnimation(true)
            .build()
    ));
}
```

Second, return a `GltfItemRenderer` from `initializeClient`, using the `GltfItemRenderer(Item)`
constructor — it reads the model id and options you registered above, so nothing is duplicated:

```java
public class RobotItem extends Item {
    public RobotItem() {
        super(new Item.Properties());
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GltfItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new GltfItemRenderer(RobotItem.this);
                }
                return renderer;
            }
        });
    }
}
```

If no `animationClip` is set and the model has animations, the first animation plays
automatically. An item with no registration renders a small red debug cube and logs one error —
see Troubleshooting.

(`GltfItemRenderer(ResourceLocation, GltfRenderOptions)` also exists for renderers not tied to a
registered item.)

## GltfRenderOptions Reference

All options, with defaults:

| Option | Default | Effect |
|---|---|---|
| `scale(float)` | `1.0` | Final model scale applied by the renderer. |
| `animationClip(String)` | `null` | Clip to start automatically (`null` = first clip, if any). |
| `loopAnimation(boolean)` | `true` | Whether the auto-started clip loops. |
| `animationSpeed(float)` | `1.0` | Playback speed multiplier. `0` freezes; negative plays a looping clip in reverse. |
| `animationTransitionSeconds(float)` | `0` | Crossfade duration used when the renderer starts/switches clips. |
| `tint(int)` | `0xFFFFFFFF` | ARGB tint multiplied into vertex color. |
| `preferGpuStaticMeshes(boolean)` | `true` | Use shared GPU buffers for fully static models. |
| `preferGpuAnimatedMeshes(boolean)` | `true` | Use persistent GPU buffers for skinned/morph models. |
| `shaderCompatMode(ShaderCompatMode)` | `AUTO` | `AUTO` falls back to the vertex-consumer path under an active shader pack; `FORCE_GPU`/`FORCE_CPU` override (debugging). |
| `lodMode(LodMode)` | `AUTO` | `AUTO` picks LOD by distance, `DISABLED` always uses the base model, `FORCE_LEVEL` pins a level. |
| `forceLodLevel(int)` | — | Sets `lodMode` to `FORCE_LEVEL` and pins that LOD level. |
| `maxRenderDistance(float)` | unlimited | Entities/block entities beyond this distance (blocks) are not rendered at all. |
| `strictValidation(boolean)` | `false` | Reserved; currently no effect. |

## Item Placement And Scale

Polymesh uses model bounds to place item models:

- GUI items are centered on the full model bounds.
- Ground, hand, head, and fixed contexts are centered horizontally and placed on the model's
  bottom bound.
- The renderer cancels Minecraft's vanilla custom-item offset before applying display transforms.

If a model looks too large or small, adjust `GltfRenderOptions.scale()`. Polymesh applies no
hidden unit conversion — keep Blender units intentional.

## Entity And Block Entity Renderers

`GltfEntityRenderer` / `GltfBlockEntityRenderer` (and their `*Factory` helpers) share the same
render backend as items: GPU static/animated paths, shader-pack fallback, LOD, and distance
culling. Each entity id / block position gets its own animation controller and GPU buffers,
automatically released after ~60 seconds unseen.

```java
// Entity renderer registration:
EntityRenderers.register(MY_ENTITY_TYPE.get(),
    GltfEntityRendererFactory.create(new ResourceLocation("my_mod", "robot"),
        GltfRenderOptions.builder().scale(1.0f).animationClip("Walk").build()));

// Block entity renderer registration:
BlockEntityRenderers.register(MY_BE_TYPE.get(),
    GltfBlockEntityRendererFactory.create(new ResourceLocation("my_mod", "robot"), 1.0f));
```

Query a per-instance controller for gameplay-driven animation:

```java
AnimationController controller = myGltfEntityRenderer.getAnimationController(entity);
if (controller != null) {
    controller.play("Attack", false, 0.15f);
}
```

For living entities the model yaw follows the interpolated body rotation (`yBodyRot`), matching
vanilla mob rendering; override `renderYawDegrees` to customize.

## Level Of Detail (LOD)

Two ways to declare LOD variants of a model:

**1. In the glTF itself**, via model-level `extras`:

```json
{
  "asset": { "version": "2.0" },
  "extras": {
    "polymesh": {
      "lods": [
        { "level": 1, "distance": 16.0, "model": "my_mod:robot_lod1" },
        { "level": 2, "distance": 48.0, "model": "my_mod:robot_lod2" }
      ]
    }
  }
}
```

`distance` (alias `distanceBlocks`) is the camera distance in blocks at which that level becomes
active; the highest matching level wins. `model` names another loaded glTF model to swap in.

**2. In code**, when you cannot edit the asset:

```java
PolymeshApi.registerLodModel(new ResourceLocation("my_mod", "robot"),
    new ResourceLocation("my_mod", "robot_lod1"),
    new ResourceLocation("my_mod", "robot_lod2"));
```

Registered models are used for levels 1, 2, … when the glTF extras don't name a model for the
selected level.

LOD selection and `maxRenderDistance` culling apply to **entity and block-entity rendering only**;
items always use the base model.

## Access A Loaded Model

```java
RuntimeModel model = PolymeshApi.loadModel(new ResourceLocation("my_mod", "robot"));
if (model != null) {
    int nodeIndex = model.getNodeIndex("RightHand");
    RuntimeNode hand = model.getNode("RightHand");
}
```

Useful model data: `getNodes()`, `getMeshes()`, `getSkins()`, `getAnimations()`,
`getMaterials()`, `getBoundingBox()`, `getLods()`, `getExtras()`, `getExtensionsUsed()`,
`getExtensionsRequired()`.

Don't hold `RuntimeModel` references across resource reloads — query again after a reload
(see Resource Reload Behavior).

## Drive Animations Yourself

Create an `AnimationController` per logical instance that needs its own pose:

```java
RuntimeModel model = PolymeshApi.loadModel(new ResourceLocation("my_mod", "robot"));
AnimationController controller = PolymeshApi.createAnimationController(model);

controller.play("Walk", true);
controller.tick(deltaSeconds);

Matrix4f[] pose = controller.computeGlobalTransforms(partialTick);
```

Supported channels: `translation`, `rotation`, `scale`, `weights`.
Supported interpolation: `LINEAR`, `STEP`, `CUBICSPLINE`.

### Crossfades

```java
// Blend from the current pose into "Run" over 0.25 seconds:
controller.play("Run", true, 0.25f);

// Moving -> stationary switches look better fading from a frozen snapshot of the
// current pose, so limbs stop with the body instead of cycling through the fade:
controller.play("Idle", true, 0.25f, true);
```

Interrupting an in-flight transition snapshots the on-screen pose and fades from it, so rapid
state flips don't pop. Re-entering a looping clip resumes its phase instead of restarting at
frame 0.

### Speed, time, state

```java
controller.setSpeedMultiplier(2.0f);   // double speed; 0 freezes; negative = reverse (looping clips)
controller.setAnimationTime(1.5f);     // seek (clears any in-flight transition)
controller.stop();
controller.isPlaying();
controller.getAnimationName();
controller.getPoseVersion();           // increments when the pose changes; cheap dirty-check
```

## Use Bones And Attachment Points

Nodes act as bones, sockets, or attachment points. Name them clearly in Blender, then query by
name:

```java
Matrix4f handTransform = controller.getNodeGlobalTransform("RightHand", partialTick);
Vector3f handPosition = controller.getNodeGlobalPosition("RightHand", partialTick);
```

Override a node pose (additive over the active clip):

```java
controller.setBoneTransform("Head", null,
    new Quaternionf().rotateY((float) Math.toRadians(20.0)), null, true);
```

Aim a node's local +Z axis at a target in model space:

```java
// Re-aims relative to the animated pose each call (head-bob and sway push the gaze around):
controller.lookAt("Head", targetPosition, 140.0f, 80.0f, 0.25f);

// Holds the gaze steady even while the underlying clip animates the bone — usually what
// you want for "look at the player" behavior:
controller.lookAtStable("Head", targetPosition, 140.0f, 80.0f, 0.25f);
```

Yaw/pitch limits are degrees; `smoothing` is 0 (snap) to 1 (retain most of the previous
rotation). Clear overrides with `clearBoneTransform(name)` / `clearBoneTransforms()`.

Use these for held-item sockets, muzzle flashes, particle emitters, hitbox anchors, and
animation-driven gameplay checks.

## Use Morph Targets / Shape Keys

Polymesh loads morph target deltas and `weights` animation channels. If your exporter writes
Blender shape key names into `mesh.extras.targetNames`, look them up by name:

```java
int smile = controller.getMorphTargetIndex("Face", "Smile");
controller.setMorphWeight("Face", smile, 1.0f);
// or directly:
controller.setMorphWeight("Face", "BlinkLeft", 1.0f);
// clear:
controller.clearMorphWeights("Face");
controller.clearMorphWeights();
```

Good uses: facial expressions, damage states, charging effects, customization sliders,
Blender-authored shape key animations.

## Pass Blender Metadata With `extras`

glTF `extras` are preserved on the model root, nodes, and materials:

```java
RuntimeNode node = model.getNode("Muzzle");
String socketType = node.getExtras().getString("socket");
float radius = node.getExtras().getFloat("hit_radius", 0.25f);
```

Suggested patterns:

```json
{ "socket": "weapon_muzzle", "hitbox": "head", "hit_radius": 0.35, "particle": "spark" }
```

This is the cleanest way to pass Blender-authored gameplay hints into your mod without inventing
a sidecar file. (The `extras.polymesh` object on the model root is reserved for Polymesh itself —
currently the LOD schema above.)

## Materials And Textures

Rendered material fields:

- base color texture and factor
- alpha mode `OPAQUE` / `MASK` / `BLEND` and alpha cutoff
- double-sided flag

Parsed and exposed on `RuntimeMaterial` for your own code, but **not yet rendered** by the
built-in renderers: emissive texture, emissive factor, `KHR_materials_emissive_strength`.

Texture sources, in resolution order: embedded `.glb` images; relative `.gltf` image URIs;
`assets/<modid>/textures/gltf/...`; direct `namespace:path` strings in material data.

Render types map to vanilla-style entity states: solid, cutout/no-cull (MASK or double-sided),
and translucent (BLEND).

## Shader Packs (Iris/Oculus)

The fast GPU path issues raw GL draws that a shader pack's extended entity program cannot
consume, so with `ShaderCompatMode.AUTO` (default) Polymesh detects an active pack (via the Iris
v0 API by reflection — no hard dependency) and routes through the CPU vertex-consumer path, which
Iris extends correctly and which preserves smooth per-vertex normals. If a model still looks flat
under a pack, check the pack's own entity-normal mode (some derive normals from screen-space
geometry instead of trusting vertex normals) — that is a pack setting, not a Polymesh one.

## Validation Warnings

Models are validated at load time; issues are logged once per model, not per frame:

- unsupported primitive modes (only TRIANGLES render)
- missing `POSITION`, mismatched accessor counts
- incomplete `JOINTS_0` / `WEIGHTS_0` pairs
- index counts not divisible by 3
- skins with more than 256 joints (only the first 256 are applied)
- generated normals or tangents (authored ones are better)
- malformed runtime arrays

## Resource Reload Behavior

On client resource reload Polymesh closes GPU buffers, clears dynamic animated buffers, reloads
texture mappings, and reloads glTF models. Do not keep `RuntimeModel` references across reloads;
query `PolymeshApi.loadModel` again.

## Troubleshooting

- **Red debug cube instead of the model** — the model id was not found (typo, wrong namespace,
  file outside `models/gltf/`), or the item was never passed to
  `PolymeshApi.registerItemRenderer`. The log contains one line per missing id:
  `GLTF model not found: <id>. Loaded models: [...]` — the list shows every id that *did* load.
- **Model renders but is tiny/huge or floats** — adjust `scale()`; check the model's origin and
  bounds in Blender (placement uses the bounding box).
- **Animation doesn't play** — clip names are case-sensitive; check `model.getAnimations()` for
  the exported names. Blender's NLA track names are what the glTF exporter writes.
- **Flat shading under a shader pack** — see Shader Packs above.
- **Textures missing on `.gltf`** — relative URIs must resolve next to the model file, with no
  `..`, no backslashes, no absolute paths.

## Practical Blender Export Advice

- Export glTF 2.0; prefer `.glb` for simple packaging, `.gltf` when you want inspectable
  buffers/textures.
- Keep model scale intentional; Polymesh does not secretly convert units.
- Name important bones/nodes clearly: `Head`, `RightHand`, `Muzzle`, `BackSocket`.
- Use shape key names if you want named morph access.
- Use `extras` for sockets, hitbox hints, effect points, and gameplay tags.
- Triangulate or ensure exported primitives are triangles.
- Export normals; missing normals are generated, but authored normals are better.
