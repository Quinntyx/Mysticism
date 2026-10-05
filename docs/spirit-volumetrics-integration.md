# Wave 2 client volumetrics handoff

## Parent registrations (not edited here)

Existing `MysticismClient` calls remain valid/idempotent: `SpiritWorldRenderer.init()` now
registers `SpiritItemProjectionRenderer`; `SpiritFogVoxels.init()` is a compatibility alias
for `ShaderManager.init()` and no longer draws analytic fog boxes. Optional consolidated
registration: `io.github.mysticism.client.spiritworld.SpiritWorldClient.init()` (shader,
glyphs, sky). Do not register both old *render implementations* from another branch.

**Required** addition to `src/client/resources/mysticism.client.mixins.json`:

```json
"compatibilityLevel": "JAVA_21",
"client": ["SpiritBackgroundRendererMixin"]
```

Keep the existing package `io.github.mysticism.client.mixin` and any other parent entries.
New file: `src/client/java/io/github/mysticism/client/mixin/SpiritBackgroundRendererMixin.java`.
This hook disables vanilla analytic fog only for a prepared volumetric spirit frame;
otherwise it supplies a usable 40..64-block linear fallback and matching fog color.
Without registration the postprocess refuses to double-fog and prints a HUD/log diagnostic.
No initializer, manifest, mixin configuration, build, common visibility or payload file was edited.

## Terrain contract and resource bounds

`SpiritRenderSettings`: **opaque radius 64 blocks**, **minimum loading radius 80 blocks**,
`FogHorizons(40, 64, 80)`. Terrain must generate/load collision out to **at least 80**;
80 is a minimum, not this renderer's guarantee that terrain has actually been loaded.
The 40-block visible horizon is for fallback/shared gates; the participating medium has
positive extinction from the camera, not a hollow 40-block void.

Density is a periodic continuous function of world coordinates in [1, 1.5]. Extinction
floor is `-ln(0.001)/64 = 0.107933676234...` per block. The midpoint Beer–Lambert integral
has transmission <= approximately 0.001 by 64 even in the thinnest region. Clear sky depth
is reconstructed into a direction and marched through the same 64-block medium, never
skipped. Camera modulo period is 4096 blocks (integer wave numbers keep density continuous
at positive/negative wrap boundaries and avoid world-border float-coordinate loss).

Quality choice: JVM `-Dmysticism.spirit.quality=low|medium|high`, default medium;
`ShaderManager.setQuality(SpiritRenderSettings.Quality)` is also public. Low/medium/high
use **24/40/64** bounded ray samples and **9/25/49 unique neighborhood taps**, respectively.
Each painterly tap has at most one color/depth fetch, plus center samples; four quadrant
moments share the neighborhood, no randomized rotated kernels or 81-tap arrays. Sky/depth
edge rejection avoids obvious silhouette bleed. Saturation is a separate final program:
monotonic-time smoothstep from grayscale to full saturation over **2.5 seconds** after
entry; independent of tick rate, distance fog, frame count and painterly quality.

At most 128 glyphs, sorted by ID; oversized sets are rejected without enumerating them.
Retained placement/icon cache <=128 entries; fingerprint checks reject incompatible vectors.
Models resolve on first ID appearance, not on tick; no embedding model IO/inference,
world/chunk scans or blocking waits are introduced. Reusable glyph buffer starts at 256 KiB;
Minecraft may grow it for unusually complex modded item models (no hard byte cap claimed).

One full-size color+depth snapshot FBO and three color+depth Satin targets; nominal
RGBA8+32-bit-backed-depth cost ~32 bytes per framebuffer pixel total (~63.3 MiB at 1080p),
excluding the existing main/Fabulous buffers and driver overhead. No per-frame FBO creation.
Resize uses framebuffer pixel dimensions, not GUI-scaled dimensions, rebinds sampler IDs
and snapshots current main depth. Exit/re-entry/disconnect/shutdown release our FBO and
managed chain. Satin remains the reload/resize owner; an external resource reload can
initialize its managed targets even outside the spirit world, retained until release.
Failures produce HUD status and phase/exception/GL-error diagnostics, retain normal terrain
occlusion and linear fog, and retry after re-entry or resource reload (F3+T).

## Actual 1.21.1 ordering / attachment investigation

Inspected the cached **Fabric rendering-v1 5.0.5+df16efd019 / Satin 2.0.0 source JARs** and
mapped Yarn **1.21.1+build.1** Minecraft bytecode (`javap -c -p`). Relevant source paths:

* Fabric `net/fabricmc/fabric/mixin/client/rendering/WorldRendererMixin.java`:
  `START` at render HEAD, `AFTER_ENTITIES` before block entities, `AFTER_TRANSLUCENT`
  before cloud-mode lookup, `END` at WorldRenderer.render RETURN.
* Satin `org/ladysnake/satin/mixin/client/event/GameRendererMixin.java`:
  `ShaderEffectRenderCallback` is after `drawEntityOutlinesFramebuffer()` in
  **GameRenderer.render**, NOT before the hand inside `GameRenderer.renderWorld`.
* Minecraft `GameRenderer.renderWorld`: WorldRenderer.render -> clear GL depth (256)
  -> renderHand. Thus Satin's normal event sees cleared/hand depth, unsuitable for world fog.
* Minecraft `WorldRenderer.render`: AFTER_TRANSLUCENT is before Fabulous's transparency
  postprocessor. Its final vanilla blit clears main depth, too. `Framebuffer.copyDepthFrom`
  performs a depth-buffer blit; `getDepthAttachment()` is the GL texture ID, not a sampler
  location. `SimpleFramebuffer(..., true, ...)` supplies compatible snapshot depth.
* Satin `ResettableManagedShaderEffect.setup` resizes targets and reacquires uniforms;
  `setSamplerUniform` binds all matching pass samplers. `render` drives the vanilla chain.
  Minecraft `PostEffectPass.render` clears every output target, including main depth.

Production order therefore is:

1. START: capture/invert actual projection * camera-relative world view matrix; prepare uniforms/FBO.
2. AFTER_ENTITIES: draw/flush glyphs directly to main with LEQUAL depth test and depth writes.
   Atlas icons are deliberately routed away from item-translucent/Fabulous's auxiliary target.
3. AFTER_TRANSLUCENT: snapshot main terrain+glyph depth **before Fabulous composite clears it**.
4. END: bind this stable snapshot, render managed **fog -> Kuwahara -> saturation -> blit**,
   restore main scene depth/viewport/depth state. Do NOT use Satin's later render event.
5. Vanilla clears depth and draws hand; screen overlays/HUD happen later and are not volumetric.

Sky is immediate shader geometry using `context.positionMatrix()` (Fabric sky callback has
no MatrixStack yet), with depth writes disabled. It leaves depth=1 for the full-length sky
ray. Fog output alpha is 1 even when vanilla clear-color alpha was 0, so final vanilla blit
cannot discard sky fog. Built-in non-atlas item renderers use an amethyst fallback icon;
transparent item icons are represented as cutout depth-writing glyphs, not layered glass.

## Executed checks (no runtime/GPU success claim)

* `MYSTICISM_MINECRAFT_CLASSPATH="<existing mapped/runtime jars>" bash
  src/test/java/io/github/mysticism/client/spiritworld/run-render-tests.sh`:
  **90 static resource/order contracts**, **1843 math checks**, **3 GLSL150 fragments**
  validated by installed `glslangValidator -S frag`. Test CPU reference covers density floor,
  opacity cap/all qualities, energy conservation, negative wrapping, depth/matrix roundtrip,
  sky rays, actual time-fade settings, frozen projection and profile rejection. It is not
  execution of the GLSL on a GPU. Script reports explicit NOT RUN without mapped classpath.
* Java21 `javac -proc:none --release 21` of all owned render/mixin files against real cached
  Yarn/Fabric/Satin artifacts passed (optional API annotation warnings). This bypasses Loom
  annotation processing and does not prove mixin remapping or runtime attachment behavior.
* Existing four test mains were recompiled with this worktree's real production test
  dependencies (embedding/vector/landmark/world state/region, payload, latent components),
  then `java -ea ... io.github.mysticism.build.SelfTestRunner /tmp/mysticism-volumetrics-classes`:
  embedding persistence/network **93**, pipeline **133**, landmark core **802**, real
  disk/NBT **2388** checks passed, plus the new render math (5 main-based suites total).
  Corrupt-save tests intentionally log expected errors. No real model, Minecraft server or
  client was booted. Cached mapped/runtime jars were used; no dependencies were installed.
* Interactive tmux pane **%466**: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk bash ./gradlew
  --offline --no-daemon --max-workers=2 compileJava compileClientJava test`:
  **FAILED** at common compile, existing `ai.djl.util.Pair` imports in KnnIndex,
  SimpleKnnIndex and EmbeddingCommand; client compile/tests were not reached. Not changed
  here (parent/safety integration). A raw whole-project javac attempt also cannot apply
  Loom's injected CCA `getComponent` API; scoped compile above uses actual mapped APIs.
* `git diff --check` passed. No logs/classes/caches committed.

## Manual smoke checklist (NOT executed here)

After parent registers the mixin and integrates common fixes, run the Java21 client in an
interactive tmux pane using the above Gradle flags and `runClient` (display output, no log
redirection). Record GPU/driver, resolution, graphics mode, view distance and FPS/frame time.

1. Enter spirit world with real generated opaque terrain; place glyph in front of/behind a
   solid wall, look around/through cutout leaves and along silhouettes. Hidden glyphs must
   never appear through opaque walls. Strafe, change FOV, bob, use third person; world-space
   density stays attached to the world, not to screen/camera motion.
2. Compare sky/terrain/glyph at 8, 32, 64, 80 blocks. At 64, near-opaque fog hides loading
   transitions; beyond 80 terrain/collision must still exist according to terrain service.
   Sky is fogged; hand and HUD remain legible. No old voxel-box/second analytic fog layer.
3. Test Fast/Fancy/Fabulous. Resize/fullscreen/GUI scale changes, minimize/restore and F3+T
   while standing beside a wall. No stale depth IDs, flipped rays, black frame or post-chain
   framebuffer feedback. Test low/medium/high quality and compare frame time, not just FPS.
4. Observe 2.5-second saturation fade; pause, exit/re-enter, disconnect/reconnect, reload
   resources and stop integrated server. No old-session glyphs or world frame processed in menus.
5. Temporarily override fog fragment with invalid GLSL via a resource pack; F3+T must show
   fallback status and meaningful log, leave usable terrain/glyph occlusion; remove override,
   F3+T and verify recovery. Test an unsupported GL/FBO environment if available.
6. Save/quit/reload world and compare terrain authoritative placement/IDs. Existing glyph
   payload lacks authoritative realm coordinates/epoch: local glyph frame is frozen **within
   a connection/world session**, but resets from current player latent basis/position on
   re-entry. Save-stable glyph-to-terrain alignment cannot be promised without parent-owned
   placement/epoch networking. No payload or common placement API was invented here.

Remaining limitations: GPU/Fabulous/driver/mixin runtime and visual performance untested;
main depth describes opaque terrain/glyphs, not multiple translucent layers, weather, clouds
or every Fabulous auxiliary entity surface. Arbitrary mod model atlas assumptions and
external shader/renderer compatibility need smoke testing. No actual terrain loading is
implemented by this client patch. No persisted authoritative glyph placement is available in
current payload, so local frozen glyph session positions are not a save/reload world contract.
