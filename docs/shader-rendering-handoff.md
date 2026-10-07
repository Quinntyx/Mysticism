# Shader/rendering handoff — wave 2

Implementation commit: `fc30ce436955d0d0eab661263a7332dcf61d6330` on
`feat-spirit-volumetrics`. Only client render/mixin source, shader resources, scoped tests
and documentation were changed. No initializer, build/config, common visibility,
network payload, terrain, activity or landmark-core edits; no new dependencies.

## Parent integration

* Add `SpiritBackgroundRendererMixin` to the existing `client` list in
  `src/client/resources/mysticism.client.mixins.json`; retain its package
  `io.github.mysticism.client.mixin`. Parent should use `compatibilityLevel: JAVA_21`.
* Existing registrations remain valid/idempotent: `SpiritWorldRenderer.init()` now
  delegates to `SpiritItemProjectionRenderer.init()`; `SpiritFogVoxels.init()` delegates
  to `ShaderManager.init()` and no longer draws analytic fog boxes.
  Optional consolidated registration: `SpiritWorldClient.init()` (shader, glyphs, sky).
* Missing fog mixin: visible HUD/log diagnostic; volumetric chain refuses to double-fog
  vanilla rendering. With the hook installed, hardware/shader failure uses 40..64-block
  linear fallback. F3+T or dimension re-entry retries.
* Terrain contract: **opaque radius 64 blocks; loading/generation at least 80 blocks**.
  Actual client constants are `SpiritRenderSettings.OPAQUE_RADIUS=64`,
  `MIN_LOADING_RADIUS=80`, `FogHorizons(40,64,80)`; client does not load terrain itself.

## Production implementation and ordering

World-space participating medium uses nonlinear depth unprojection with inverse actual
world view-projection, periodic continuous density, bounded Beer–Lambert integration
and in-scattering. Extinction floor `-ln(0.001)/64` ensures near-opacity by 64 blocks;
clear sky depth gets a full-length ray, not a no-fog shortcut.

Actual cached Fabric/Satin source and mapped Yarn1.21.1 bytecode were inspected.
**Satin's effect event is after the hand render/depth clear**, not a usable scene-depth
hook. Fabulous's transparency postprocessor also clears main depth. Consequently:

1. `START`: matrices/uniforms/FBO preparation.
2. `AFTER_ENTITIES`: glyphs flush directly into main with depth testing/writes.
3. `AFTER_TRANSLUCENT`: snapshot main terrain+glyph depth before Fabulous compositing.
4. `END`: managed fog -> Kuwahara -> saturation -> blit; restore main scene depth.
5. Vanilla clears depth for hand; hand/HUD are outside volumetric processing.

Quality JVM property `mysticism.spirit.quality=low|medium|high` selects 24/40/64 ray
samples and 9/25/49 unique coherent painterly taps (default medium). Independent final
saturation pass fades grayscale to full color over 2.5 monotonic-time seconds.
At most 128 cached/sorted glyphs; incompatible profile fingerprints rejected. No new
model IO/inference, large world scans or blocking waits on tick/render. One snapshot
FBO plus three Satin targets, no per-frame FBO allocation; dimensions/sampler IDs follow
framebuffer resize. Owned resources release on exit/disconnect/re-entry/shutdown.

## Independent review (2026-10-06)

Fixed first glyph entry freezing stale movement-only predictor mirrors: production
`SpiritGlyphFrame` now snapshots actual CCA basis/position on first visibility. Retained
positions/profile rejection and its hard 128-entry budget are exercised directly by tests.
World/player replacement resets the local frame and releases its native buffer; disconnect
clears connection caches. Renderer/resource invalidation re-resolves model safety while
preserving the frozen placement frame.
Suppressed glyph glint to avoid routing its incompatible texture/vertex format through the
atlas cutout buffer. Fixed float wrapping of tiny negative coordinates to keep [0,4096).
Kuwahara now uses integer nearest depth/color fetches and foreground-only gathering,
preventing deeper glyph samples bleeding onto a nearer wall; still <=49 neighborhood taps.
Fog radius, chain ordering and independent 2.5-second saturation layer remain unchanged.

Latest actual checks:

* **PASS**: 99 static resource/order contracts, 1870 CPU/production-placement checks,
  3 GLSL150 fragments (`glslangValidator`); seven processed shader resources byte-match
  reviewed source; `git diff --check`.
* **PASS, qualified**: visible tmux **%466**, Java21 Gradle offline `compileJava
  compileClientJava selfTest`, exit 0. Temporary main-source copy substitutes exactly
  parent `2577922`'s four Pair migration files (IndexPair/KnnIndex/SimpleKnnIndex/command),
  with no worktree common/config changes. Actual client sources and all five test mains
  passed: render 1870, persistence/network 93, pipeline 133, core 802, disk/NBT 2388.
  Command/init-script details are in `docs/spirit-volumetrics-integration.md`.
* **FAILED**: `verifyProductionJar` attempt stops at `jar` because main/client resources
  duplicate `assets/mysticism/lang/en_us.json`. Remap/production-jar verification not run;
  parent owns the fix. Unmodified baseline common source still needs its Pair migration.
* **NOT RUN**: GPU/live Minecraft, visual wall occlusion, Fabulous, resize/reload/failure
  recovery, actual saturation visuals or performance. No driver/runtime success claimed.

Parent networking followup is required for authoritative save-stable glyph placement:
include projection epoch and realm placement/persisted frame per stable ID with pinned
profile and connection/dimension session identity; guard queued packets by live session,
coordinate initial CCA sync, prune removed vector entries, and resend on re-entry/respawn.
The existing vector cache can grow within a connection; disconnect cleanup is not a
per-connection memory cap. Current positions are **local session-stable only**, not
terrain-authoritatively aligned or save/reload-stable. No placeholder packet/API invented.
Parent still registers the supplied mixin and ensures terrain loading >=80 for opaque64.

## Original implementation validation (before independent review)

* `MYSTICISM_MINECRAFT_CLASSPATH="<existing mapped/runtime jars>" bash
  src/test/java/io/github/mysticism/client/spiritworld/run-render-tests.sh` passed:
  **90 static resource/order checks**, **1843 math checks**, **3 GLSL150 fragments**
  validated with installed `glslangValidator -S frag`.
* Owned client/mixin sources compiled with Java21 `javac -proc:none --release 21`
  against actual cached Yarn/Fabric/Satin APIs. This is not Loom mixin remapping validation.
* Recompiled this worktree's actual test dependencies and ran the existing main-based
  runner: embedding persistence/network **93**, embedding pipeline **133**, landmark
  core **802**, disk/NBT persistence **2388** checks passed, plus render math (5 suites).
  Corrupt-save tests logged expected errors. No real model/client/server was booted.
* Visible interactive tmux pane **%466**, explicit bash, Java21:
  `bash ./gradlew --offline --no-daemon --max-workers=2 compileJava compileClientJava test`
  **failed** at existing common `ai.djl.util.Pair` imports in KnnIndex,
  SimpleKnnIndex and EmbeddingCommand. Client Gradle compile/tests were not reached.
  Parent/safety integration must resolve that baseline issue, not this shader patch.
* `git diff --check` passed. No logs/classes/caches committed.

## Limitations / independent review

No GPU, visual FPS, Fabulous, driver failure/recovery or mixin runtime smoke was executed.
Depth describes opaque terrain/glyphs, not all translucent layers/weather/clouds/Fabulous
auxiliary entity surfaces. Atlas glyphs use depth-writing cutout rendering; built-in
non-atlas renderers get an amethyst fallback. Modded model buffer growth is not hard-byte
capped (starts at 256 KiB). Four full-size color/depth FBOs cost nominally ~63.3 MiB at
1080p, excluding main/Fabulous/driver overhead; Satin may initialize its managed targets
on resource reload outside the spirit world.

Current payload has no authoritative realm placement/epoch. Glyph positions are frozen
within a world/connection session, but re-entry/reload rebuilds a local frame from player
state; save-stable glyph-to-terrain alignment cannot be guaranteed without parent-owned
placement networking. No placeholder payload/API was invented.

Manual review: wall occlusion; sky/glyph/terrain at 8/32/64/80 blocks; camera motion/FOV;
Fast/Fancy/Fabulous; resize/fullscreen/F3+T; quality frame times; timed fade; dimension
exit/re-entry/disconnect; invalid-shader resource-pack fallback/recovery; save/reload
placement comparison. Detailed source references and smoke steps are in
`docs/spirit-volumetrics-integration.md`.
