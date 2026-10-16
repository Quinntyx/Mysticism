# Retained filtering/fog/embedded-runtime integration

Branch: `feat-runtime-sol-merge-0044`, development target only.

## Preserved work and resolution

Completed the already-started merge without resetting, stashing, or discarding
existing work. Retained parents:

- `9539d4927d457a6ef0f367c92327d93b5d71b526`: framebuffer/sampler lifecycle and useful wider fog.
- `45c467e068615e30c1d529d7c4a8247577d033c7`: executable Kuwahara filtering and real embedded v2 inference.

The ShaderManager conflict required **both** the render-option-based `fogRange`
and the default-enabled `kuwaharaEnabled` state, not a choice between them.
One shared fog/filter/transition chain still consumes same-frame scene depth
once, behind the retained surface-identity/readiness gate. Startup, main-target
replacement, real pixel resize, reload, disconnect and sampler rebinding retain
the resource parent's handling. Live quality controls fog steps and filter
radius; actual framebuffer height controls painterly sampling stride.

Fog keeps the foreground clear through half the configured render radius and
retains useful terrain contrast farther out, reaching its opaque horizon only
at that radius. The option is clamped to 2–32 chunks. Neither fog nor mesh culling
shrinks to sparse source coverage. This does not fix terrain acquisition.

Embedded JNI remains the default provider, even with an HTTP endpoint configured.
Exact pinned native-v2 GGUF weights/tokenizer are required. First use may provision
the exact asset asynchronously; an explicit model path supports offline launches.
The model is not bundled, and no external inference server is a prerequisite.
Native 768-dimensional output, token overflow rejection, and fresh schema-v2
persistence remain intact; no v1 migration or truncation was introduced.

## Additional integration regressions

- `SpiritFogRangeSelfTest` requires the executable chain after one-shot depth
  consumption and live filter/fog uniforms, including framebuffer-height stride.
- `KuwaharaGlProbe` measures actual GLSL fog transmission using paired black/white
  draws, real nonlinear perspective depth, geometry and sky, 2/8/12/16/32 chunks,
  and LOW/MEDIUM/HIGH quality. It checks clear foreground, useful distant contrast,
  shared opaque horizon, finite output, and alpha coverage over cleared main color.
- The GPU probe now deletes/recreates programs and attachments at 25/17/33/25
  pixel dimensions, in both RGBA32F and Minecraft-style RGBA8. This supplements,
  but does not replace, production frame-gate/resource tests or game callbacks.

## Actual checks in this checkout

Java 21, cached Gradle 8.13/Loom 1.10.5. Exact command:

```sh
xvfb-run -a ./gradlew --no-daemon --console=plain --max-workers=2 --rerun-tasks build kuwaharaGpuTest embeddedInferenceProbe
```

Exit 0, `BUILD SUCCESSFUL in 1m 18s`, 17 tasks executed. No excluded suites,
substitute classpath, model download, or external inference service. The exact
preprovisioned model was copied into the ignored checkout-local
`build/embedding-inference-probe/nomic-v2.gguf`; the probe verifies size and hash.

`runtimeSelfTest` discovered and executed all **11** main-based classes:

| Suite | Result |
| --- | --- |
| KuwaharaPassTest | 65 checks |
| SpiritFogRangeSelfTest | 18,284 checks |
| SpiritPostprocessFrameTest | 115 checks |
| SpiritPostprocessResourcesTest | 413 checks, including 6 rejection cases |
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedEmbeddingSelfTest | 54 fixture checks, not model inference |
| EmbeddedNativeRuntimeSelfTest | real JNI ABI/build check, not model inference |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| PinnedEmbeddingModelSelfTest | 25 transport/integrity fixture checks |

Framework `test` is intentionally disabled; the discovered runner is the actual
current test path. `buildSelfTest` passed packaging/descriptor, decoded metadata,
verification graph, and runner discovery/order/assertion/failure regressions.
`verifyProductionJar` passed on `build/libs/mysticism-1.0-SNAPSHOT.jar`, including
nested JNI/native/transitive runtime packaging checks.

**Actual OpenGL execution:** Mesa 26.2.3-arch1.1, OpenGL 4.6 Core, llvmpipe
(LLVM 22.1.8, software rendering under Xvfb), not a hardware-performance claim.
At each of the four sizes in both formats the enabled full post-chain filtered
the 0.9 impulse to 0.1888889 (RGBA32F) / 0.19215688 (RGBA8). Existing constant,
hard-edge, disabled-filter, saturation and transition fixtures passed. All new
fog depth/distance/quality/alpha fixtures passed.

**Actual model inference:** llama JNI build `b10682-5ea1b124e`, CPU execution;
GGUF SHA256 `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
Real tokenizer, readiness forward pass, repeated inference, four distinct
registry-like descriptors, all 768 coordinates including nonzero native tail,
normalization, cache copy isolation and overflow rejection passed. The provider
was selected by the ordinary default path with no provider override and a
purposefully unusable HTTP endpoint. Oak/birch cosine 0.86688715, oak/lava
0.8003141: observed outputs, **not** a semantic corpus-quality claim.

Retained tmux session `$11`, original pane `%2583`:

- Local model copy: pane `%2596`, run `f6648cce028844868de5a0e58c0d22f2`, exit 0.
- Combined build/probes: pane `%2597`, run `fab5e8d984f0488e862012df149036f6`, exit 0.
- `bash docs/smoke-rebuild-render.sh`: pane `%2614`, run
  `13c8dc14ddc44a0db7fbc5e18bf02edf`, exit 0 (offline GLSL syntax).

`git diff --check` and staged whitespace/conflict checks passed.

## Limits and remaining risks

No Minecraft/IntelliJ client launch, gameplay, F3+T/window/GUI resize smoke,
Fast/Fancy/Fabulous acceptance, physical-GPU or performance check was performed.
Standalone shader execution does not exercise Fabric/Satin callback timing.
Native inference was tested on Linux x86-64 CPU only; other packaged platforms
remain launch/inference acceptance work. First-use network weight provisioning
was not exercised with the full model (only transport/integrity fixtures).

Existing nonfatal Loom source-remapping `javax.annotation.Nullable` diagnostics,
API/mixin/Gradle deprecation warnings and expected corrupt-persistence fixture
warnings remain. Native logs report missing vocab newline/mask tokens and no GPU
backend; real forward passes nevertheless completed successfully on CPU.

The independent source-locality/acquisition defect remains: wider fog exposes
missing distant terrain rather than concealing it. This scoped integration does
not claim to fix all eleven reported runtime problems. Independent per-player
deep/shallow projection, source-coordinate octree ownership/growth, terrain
collision/render publication, and native-v2 storage contracts were not rewritten.
Historical parent validation documents retain their original observations; this
file records the current integrated checkout rather than retroactively changing
their claims.
