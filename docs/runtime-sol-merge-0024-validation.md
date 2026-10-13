# Retained embedded inference + Kuwahara integration

Owned branch: `feat-runtime-sol-merge-0024`; integration target: **dev, never main**.
This completes the already-started merge of retained immutable parents
`ca2addcb52dc1bd3b46058045911c1e9cd8ba221` (default embedded inference) and
`7d1c01407b364e48456d3e5e809651025a1f7431` (Kuwahara repair). Existing commits and
staged changes were preserved. The only conflict was in `scripts/selftest.gradle`;
both independent probe tasks are retained, not replaced with one another.

## Integrated behavior / additional regression coverage

- Normal embedding selection remains real in-process CPU/JNI inference using the
  exact pinned native v2 GGUF/tokenizer. No service prerequisite, dummy vectors,
  v1 migration, truncation or profile change. Asset preparation/readiness remains
  asynchronous and readiness executes a real forward pass.
- Painterly filtering rejects misleading single-tap zero-variance quadrants,
  weighs real neighborhoods by coverage, and applies resolution-aware stride.
  Live per-frame uniforms and the independent enable/disable switch are retained.
  Existing Satin world callbacks and registered fog mixin were inspected.
- New `buildSelfTest` integration assertions require both optional probes with
  their correct executable main, assertions and compiled runtime dependencies.
  They require normal build to retain runtime/packaging/artifact suites, without
  introducing GPU/model-weight requirements into headless CI. Both negative
  controls below demonstrate rejection rather than only a positive-path check.
- The real inference probe now clears the provider override: it verifies **normal
  default selection**, with an intentionally unreachable HTTP endpoint.
- Per-player deep/shallow projection, source-coordinate octree ownership/growth,
  collision/rendering policy and native v2 fingerprint are unchanged by this unit.

## Actual verification (2026-10-13)

All commands used the original tmux connection/session `$11`, origin `%2215`,
the required `tmux-jobs` executable, argument vectors and bounded monitoring.
Completed panes remain retained. No software was installed.

### Canonical build and discovery

`./gradlew --no-daemon --console=plain --rerun-tasks build` — **PASS**, exit 0,
46 seconds, all 15 tasks executed. Final retained pane `%2225`, run
`913e5bcf56224dac89e4fa820b49ed7f`.

Eight discovered suites executed with assertions enabled, not just compiled:

1. `KuwaharaPassTest`: 65 resource/settings checks.
2. `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
3. `AsyncEmbeddingEngineSafetyTest`: 184 checks.
4. `EmbeddedEmbeddingSelfTest`: 54 fixture/lifecycle/default-selection checks.
5. `EmbeddedNativeRuntimeSelfTest`: real JNI ABI `b10682-5ea1b124e`; not a forward pass.
6. `EmbeddingPersistenceTest`: 90 assertions.
7. `EmbeddingPipelineTest`: 135 assertions.
8. `PinnedEmbeddingModelSelfTest`: 25 asset-integrity/transport fixture checks.

Build metadata/descriptor/packaging rejection fixtures, test-runner discovery,
ordering, enabled-assertions and failure-propagation fixtures, both compilations,
resource processing, remapping and `verifyProductionJar` passed. Expected malformed
persistence diagnostics, annotation/deprecation and development-classpath SLF4J
warnings do not represent hidden failing suites. Historical discarded shared-frame
prototype suites remain outside the documented current discovery roots.

### Real offscreen filtering and real default-provider inference

Final combined run on the completed source:

```sh
xvfb-run -a ./gradlew --no-daemon --console=plain --rerun-tasks \
  build kuwaharaGpuTest embeddedInferenceProbe
```

**PASS**, exit 0, 39 seconds, all 17 tasks executed. Retained pane `%2231`, run
`efb12273760e434cb74a7c10a41035f9`.

- Mesa llvmpipe/OpenGL 4.6: **60,392 checks each** for RGBA32F and game-format
  RGBA8. Actual GLSL draws/readback cover quality radii, sloped texture filtering,
  silhouettes/occlusion, sky edges, alpha, borders, disabled pass-through, stride
  1..4 and the full four-pass graph. Enabled final impulse changed
  `.9 -> .1888889` (float) / `.19215688` (RGBA8).
- The exact 957,680,480-byte GGUF with SHA256
  `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`
  was verified. Real JNI tokenizer/forward passes and asynchronous default-provider
  readiness ran successfully. All 768 coordinates, normalization, repeated
  inference, nonidentical text output, copied cache and actual overflow rejection
  passed. Native logs report `truncated = 0`. Observed cosine oak/birch `.86688715`,
  oak/lava `.8003141` (not a Minecraft corpus-quality claim). No inference service
  was used; an unreachable HTTP endpoint did not affect default selection.
- Initial explicit provisioning also succeeded:
  `./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=true`,
  exit 0 in 10m4s including Gradle overhead and inference; pane `%2232`, run
  `0896681b24dc48148720532fb87c666f`. Exact weights were atomically published under
  this checkout's ignored `build/embedding-inference-probe/`. The parent record's
  earlier download-timeout blocker is therefore **not** a current inference blocker.
  The initial run preceded clearing the probe's explicit provider override; the
  final combined run above verified default selection using the same exact asset.
- Production ZIP comparison: all **14 shader resources byte-identical** to source;
  optional probe and regression classes absent. Remapped production verifier passed.

### Verification-graph negative controls

Owned ignored init scripts under `build/retained-verification-negative/` mutate
only a disposable Gradle invocation's task configuration, not production source:

- `wrong-probe.gradle`: replacing the GPU task's main with the inference main
  fails `buildSelfTest` with `Invalid retained verification probe: kuwaharaGpuTest`;
  expected exit 1, pane `%2243`, run `6eea43c34cab49eca5a885d6ce20ea25`.
- `default-gpu.gradle`: adding the GPU probe as a normal build dependency fails
  with `Build must run all deterministic runtime/packaging suites, not optional
  inference/GPU probes`; expected exit 1, pane `%2244`, run
  `96a3ccd7c37f4db598152806bb5f481a`.

## Limits / remaining acceptance

**No Minecraft gameplay or IntelliJ Minecraft launch was performed.** Offscreen
shader execution does not prove live Satin callbacks or physical-GPU performance.
Linux x86_64 real model inference does not prove other platforms. First runtime
use still needs the exact preprovisioned asset or network access, sufficient disk
and native memory; the bounded download can fail on slower networks. No current
build/model-inference blocker remains. Other reported runtime problems are outside
this assigned two-part unit and are not claimed fixed by this integration.
