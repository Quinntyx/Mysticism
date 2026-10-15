# Retained runtime integration 0052

Branch: `feat-runtime-sol-merge-0052`; integration target **dev**, never main.
Completed the existing merge, preserving both parent histories and the retained
working-tree work. Parents:

- `88066e1aee8f32a170332d88de8812a23e315318`: truthful navigation/source state,
  loaded-only source backfill, coherent terrain revisions, strict native-v2 inference.
- `7501fa71a1f2f08a9119c5a884bd088d73c0fd5a`: framebuffer lifecycle,
  executable Kuwahara filtering, wider fog and embedded-default inference.

## Integration decisions

The terrain-client conflict keeps the upload-gated revision implementation, not
the older incoming-frame publication path. Collision, occlusion, medium samples
and displayed GPU batches advance together after successful geometry construction.
Pending source/projection packets do not immediately change predicted collision
or fog occupancy. Render-distance-based fog/culling replaces the fixed 64-block
cutoff without changing source-coordinate ownership, octree growth or independent
per-player shallow/deep projections.

The embedded-service/model conflicts retain the strict provider/output validation
from the navigation/source parent: exact pinned native-v2 weights, full 768-vector
shape, finite nonzero unit output and actual tokenizer overflow rejection. No
truncation, v1 remapping or substitute inference was introduced. The probe now
clears the provider override and sets an unusable HTTP endpoint, demonstrating the
normal embedded selection rather than forcing an explicit embedded override.
Weights stay outside the production jar. Offline IntelliJ launch can point
`mysticism.embedding.modelPath` at the exact preprovisioned asset; otherwise
first use provisions its verified cache, with network/disk/memory requirements.

The retained source/navigation behavior includes actionable discovery phases,
walk-request/controller state, guarded saved-anchor restoration and bounded
loaded-only backfill on entry/restore. Backfill seeds are hints, never authority
for ownership, support or exit. Previously loaded chunks can participate without
a new generation event. It does not generate chunks and is not full render-distance
acquisition: the retained backfill is bounded to 25 seeds in a 5x5 neighborhood.

Filtering/fog retain one same-frame scene-depth chain, executable painterly kernel,
actual-framebuffer-size stride, valid fallback sampler bindings, framebuffer
replacement/resize handling and reload/disconnect invalidation. Fog's clear
foreground and opaque horizon scale with the configured 2–32 chunk render radius,
not sparse source coverage; missing distant geometry is not concealed by fog.

## Additional merge regression coverage

- `TerrainRevisionCoherenceTest`: pending shallow transition across an old-frame
  resource rebuild, failed/stale commit preservation, successful retry and stale
  prior-session upload rejection even with equal revision numbers (30 checks).
- `SpiritFogRangeSelfTest`: wider culling must retain committed-only collision and
  public frame access; medium publication follows construction, and packet accept
  must not publish pending occupancy (18,287 CPU/resource checks).
- `buildSelfTest`: both independent optional probes remain registered while normal
  `build` runs runtime/packaging suites without a display or weights prerequisite.

## Actual verification in this owned checkout

Java 21, cached Gradle 8.13, Loom 1.10.5. Final forced canonical build:

```sh
./gradlew --no-daemon --console=plain --max-workers=2 --rerun-tasks build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 1m 24s`, all 15 actionable tasks executed.
No exclusions or substitute classpaths. `runtimeSelfTest` discovered and executed
all **20** main-based suites with assertions enabled:

| Suite | Result |
| --- | --- |
| KuwaharaPassTest | 65 checks |
| SpiritFogRangeSelfTest | 18,287 checks |
| SpiritPostprocessFrameTest | 115 checks |
| SpiritPostprocessResourcesTest | 413 checks, including 6 rejection cases |
| SourceAnchorRestoreTest | 20 checks |
| TerrainRevisionCoherenceTest | 30 checks |
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedEmbeddingSelfTest | 54 fixture checks |
| EmbeddedNativeOutputTest | 3,139 provider/output fixture checks |
| EmbeddedNativeRuntimeSelfTest | Real JNI ABI/build load, not model inference |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| NativeEmbeddingOutputTest | 107 synthetic tensor checks |
| PinnedEmbeddingModelSelfTest | 25 transport/integrity fixture checks |
| LoadedSourceBackfillTest | 78 checks |
| SourceDiscoveryHintsTest | 1,208 checks |
| SpiritNavigationReadinessTest | 44 checks |
| SpiritNavigationStatusTest | 29 checks |
| TerrainFrameAssemblerTest | 140 checks |

`buildSelfTest` passed decoded metadata, descriptor rejection, packaging (26
rejection cases), verification graph and runner discovery/order/assertion/failure
fixtures. `bash docs/smoke-rebuild-render.sh` also passed (offline GLSL syntax
only). `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar`, including JNI/native/transitive dependency
packaging. Framework `test` is intentionally disabled; `runtimeSelfTest` is the
actual discovered test path. `git diff --check`, staged whitespace checks and
unmerged-index checks passed.

Separate real OpenGL and inference command on this integrated source:

```sh
xvfb-run -a ./gradlew --no-daemon --console=plain --max-workers=2 \
  kuwaharaGpuTest embeddedInferenceProbe \
  -PembeddingModelPath=/home/zlare/docs/src/Mysticism/feat-runtime-sol-merge-0038/build/embedding-inference-probe/nomic-v2.gguf
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 57s`, 2 tasks executed, 6 up-to-date.
The existing model in the referenced parent checkout was read and verified, not
modified; generated JNI/probe outputs belong to this owned checkout. No software
installation, new model download or external inference service was needed.

- **Actual shader execution:** Mesa 26.2.3-arch1.1, OpenGL 4.6 Core, llvmpipe
  (LLVM 22.1.8, software rendering under Xvfb). Real packaged GLSL graph executed
  with recreated attachments/programs at 25/17/33/25 pixel dimensions, RGBA32F
  and Minecraft-style RGBA8. Full enabled chain reduced impulse 0.9 to 0.1888889
  / 0.19215688 respectively; disabled/constant/edge/saturation/transition checks
  passed. Fog transmission/depth/sky/alpha/quality fixtures passed across
  2/8/12/16/32 chunk ranges. This is not hardware-performance or game acceptance.
- **Actual model forward passes:** JNI `b10682-5ea1b124e`, Linux x86-64 CPU,
  exact model SHA256
  `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
  Real tokenizer/readiness/repeated inference, four distinct registry-like
  descriptors, full 768 shape including native tail, finite unit output,
  mutable-cache isolation and token-overflow rejection passed. Native logs
  report `truncated = 0`. Default provider selection succeeded with unusable
  HTTP endpoint. Observed oak/birch cosine 0.86688715, oak/lava 0.8003141:
  smoke observations, not a semantic-quality benchmark.

Retained tmux session `$11`, original origin `%2700`:

- Final canonical build: pane `%2703`, run `139eb1775c0543baa8a2be4f1916a422`, exit 0.
- Real GL/native probes: pane `%2892`, run `0e243c8f7a48412e85eac3613e2c7fdf`, exit 0.
- Offline GLSL syntax: pane `%2899`, run `08cb6fa5da7f404f8934756ce97f34e1`, exit 0.

## Limits and remaining risks

No Minecraft client/server gameplay, IntelliJ launch, multiplayer movement,
actual source-world chunk callbacks, in-game VBO failure/recovery, F3+T/window
resize or Fast/Fancy/Fabulous acceptance was performed. Standalone real shader
execution does not exercise Fabric/Satin callback ordering in Minecraft. Loaded
backfill/source ownership tests are CPU fixtures, not in-game acquisition proof.
Full render-distance terrain acquisition remains outside this bounded backfill
integration; wider fog exposes rather than hides missing distant terrain.

First-use full weight provisioning was not exercised here; transport/integrity
fixtures and the existing exact asset were used. Other packaged native platforms
remain untested. Native warnings about missing newline/mask tokens and no GPU
backend remain despite successful real CPU outputs. Existing API-Guardian/mixin,
Jackson semver, Gradle deprecation, duplicate test-classpath logging providers and
intentional corrupt-persistence fixture warnings remain nonfatal.

This scoped merge is not a claim that all eleven gameplay reports are solved.
Historical parent validation files describe their own checks; this record is the
current integrated unit's evidence and limits.
