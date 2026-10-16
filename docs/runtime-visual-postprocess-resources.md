# Retained runtime unit: postprocess resource lifecycle

Scope: filtering/fog render resources only, on `feat-runtime-visual-postprocess-resources`.
The retained provider-timeout session made no edits; the checkout was clean on resumption.
Existing commits and the approved per-observer projection/terrain design are preserved.
No navigation, source ownership, collision, or embedding implementation is changed here.

## Repairs

* `ShaderManager` reconciles Satin's full-size targets with the **actual main framebuffer**,
  not just window resize events. If the main framebuffer object is replaced, reconstruct
  the processor: Minecraft's processor retains the main target passed to its constructor.
* `SpiritPostprocessFrame` gates depth capture and postprocessing on framebuffer identity,
  dimensions and color/depth/FBO names. New frames, Satin setup, resource reload, failure,
  release and disconnect invalidate the gate. A missing capture, resized/replaced surface,
  or a second END callback cannot consume old depth or restore it onto a different surface.
* Snapshot allocation/resizing and complete chain preparation restore main framebuffer
  binding in `finally`, including failure paths. Source-scene snapshot allocation does too.
* Occupancy atlas allocation/binding/deletion now uses Minecraft's texture-state-aware
  APIs instead of bypassing its binding cache. CPU occupancy survives GPU-only invalidation.
  Every prepared frame binds a valid OccupancySampler even when HasOccupancy is zero,
  so Satin's retained sampler values cannot leave a deleted atlas bound after reload.
* The shader syntax smoke now includes Kuwahara and saturation, not just fog/transition.

## Regression discovery / validation

Current build discovery uses `src/runtimeTest/java` (older BUILDING.md descriptions of
`src/test/java`/`selfTest` predate the retained runtime runner). No JUnit/new dependency.

* `SpiritPostprocessFrameTest`: 95 checks of the production gate, including startup with
  no capture, one-shot consumption, resize, individual attachment recreation, GL-name
  reuse by a different framebuffer, reload with all names reused, invalid surfaces,
  replacement of an unfinished frame and 64 successive valid resized frames.
* `SpiritPostprocessResourcesTest`: 394 checks of **processed classpath assets**: resizable
  targets, no input/output feedback, pipeline order, vertex attributes, fragment identifiers,
  GLSL uniforms/samplers versus descriptor names/types/counts and finite startup values.
  Six corrupted-resource rejection cases also exercise the checker.
* Canonical command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --no-daemon --console=plain build`.
  Runs all six discovered runtime main classes (the two above and four existing embedding
  regressions), packaging/descriptor/runner fixtures and remapped production-jar verification.
* `bash docs/smoke-rebuild-render.sh` passes cached offline GLSL syntax validation of the
  guidebook stages and all four postprocess fragments. This is not a driver link test.
* `git diff --check` passes.

Development iterations included two compile failures (wrong TextureUtil package, then
using setup on Satin's public interface); both were corrected, without adding dependencies.
Existing API Guardian annotation, mixin visibility, deprecation and Gradle 9 warnings remain.
Expected malformed-vector/geometry rejection logs in embedding persistence fixtures are not
runtime test failures.

## Remaining validation

No Minecraft client launch/gameplay, real GPU framebuffer resize/reload, graphics-mode
compatibility, visual quality/FPS, or model inference was exercised. The automated tests
validate the production lifecycle gate and packaged resource contracts, not Fabric callback
ordering on a running client. Follow up with startup, enter/leave, F3+T and window resize on
Fast/Fancy/Fabulous and any supported third-party renderer. Native v2 embeddings and the
other ten reported runtime areas remain independently owned, unchanged by this unit.
