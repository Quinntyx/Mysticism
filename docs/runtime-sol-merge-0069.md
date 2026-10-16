# Runtime merge-0069: retained combined candidate

## Scope and lineage

In-place merge on `feat-runtime-sol-merge-0069`, intended for `dev`, never `main`.
Both exact accepted tips must be ancestors of the published candidate:

- merge-0056: `67e482dcb5e485229ede8918576a6c95177ffcc0`
- merge-0068: `ca1537f42041fb118a6edb94af483407d51d9ff7`

The interrupted merge's staged and unstaged repairs were retained and reconciled,
not reset, replayed or replaced with a first-parent-only result. Changes were
restricted to the owned checkout. No child agents, model substitutes, model
downloads, v1 remapping, vector truncation or external-server prerequisite.
The final commit identity and verified remote head are in the delivery JSON;
this document does not attempt to embed its own commit hash.

## Integration decisions

- Combine source-coordinate geometry membership/octree ownership and current-profile
  discovered-landmark persistence with independent per-player deep/shallow projection.
  Custom terrain distortion shares projected geometry between mesh rendering, swept
  collision, support and navigation; it is not a shader-only/block-proxy effect.
- Preserve revision/session-qualified mesh assembly, producing-frame projection,
  publication acknowledgment and per-player terrain state. A failed replacement
  upload retains the prior coherent drawable frame and medium rather than closing
  the still-usable buffers or acknowledging terrain that was never installed.
- Combine render-distance source discovery, pre-existing loaded-terrain backfill,
  dimension-qualified ownership, generation invalidation and near/explicit/background
  read lanes. Compact complete observations and bounded negative-coverage retention
  prevent wider discovery from exhausting the old locality reservoir. Coverage tests
  include the maximum horizon and actual collidable published source cells; their
  simulated tick counts are not measured gameplay latency.
- Retain grounded entry, asynchronous support acquisition, latest-destination walk
  arbitration, bounded retry/expiry/recovery, deep/shallow attachment, jump grace,
  landing, step/crouch/edge/swept mesh contact and `/tp` arrival reconciliation.
  Navigation uses physical movement for jump/state decisions and separately admitted
  semantic movement for the projection. Epoch transitions never integrate the
  crossing semantic window.
- Keep prediction render-only: synced coordinates/bases remain ordered authority,
  not a second client simulation mutating CCA components. Preserve interpolation
  and moving-frame continuity. Final review found an actual merge handoff gap:
  both tick callbacks drained correction provenance but ignored it. Server windows
  now consume teleport/depenetration evidence, suppress disputed semantic advance,
  and immediately publish explicit correction revisions in both pose streams.
  Client windows subtract actual depenetration, exclude repositioning, and reset
  stale render prediction on forced authority (even unchanged coordinates), later
  independent basis corrections, component replacement and actual arrival.
  Server/client continuity both admit chosen bursts through the shared eight-block
  reposition bound instead of silently discarding the four-to-eight-block range.
- Keep seamless transition/arrival hooks and rollback ownership independent per
  player; combined mixin application is tested against the actual mapped Minecraft
  classes, not just source-string assertions. This is not a live client journey.
- Reconcile depth/color postprocessing, current custom-mesh depth, real Kuwahara
  execution, wider fog, toggles/status and reload/resize resource contracts. The
  offscreen GL probe executes packaged programs in both float and Minecraft-like
  byte framebuffers; Minecraft callback timing remains a separate acceptance item.
- Preserve the selected native Nomic v2 CPU/JNI provider, exact local-asset discovery,
  tokenizer/preprocessing, full 768-coordinate output, current-profile disk cache,
  async preparation/inference and owning-executor completion delivery. Unsupported
  native/CPU platforms fail actionably before unsafe JNI execution. Normal startup
  does not download weights or require HTTP.

## Validation

Original tmux session `$11`, origin `%3734`; installed Java 21, Gradle 8.13.
All long commands use the required `tmux-jobs` helper, explicit argv, bounded
monitoring and retained pane/run handles. Existing completed validation from the
recovered session was inspected before further work:

- Pane `%3737`, run `29df153c0de940fb81a57be7165204a1`, exit 0:
  canonical build, packaging, 146 discovered runtime classes, actual GL and exact
  native inference passed before the final correction-handoff repair.
- Pane `%3738`, run `8a239a77cd144d728b72d8ade3598c57`, exit 1:
  actual native and GL probes passed; 146 runtime classes passed and the new server
  regression failed because its fixture compared `.42` against `64.42 - 64` using
  exact floating-point equality. Replaced that assertion with a tight displacement
  tolerance. This failed invocation is not counted as a passing build.

Final combined validation (no model-path override):

```sh
/usr/bin/env JAVA_HOME=/usr/lib/jvm/java-21-openjdk xvfb-run -a \
  ./gradlew --offline --no-daemon --console=plain --rerun-tasks \
  build kuwaharaGpuTest embeddedInferenceProbe -PembeddingProvision=false
```

- Pane `%3738`, run `178e66d64d7148d3a0b59c2b4079a0cf`, exit **0**;
  `BUILD SUCCESSFUL in 2m 6s`, **17 tasks executed** (not up-to-date skips).
- **147 discovered main-based runtime classes passed**, including the new
  `RenderPredictionCorrectionTest` (19 production-handoff checks) and expanded
  `SpiritBasisEvolverTickMovementTest` (42 checks). Build/runner discovery and
  packaging fixtures passed, including 27 invalid-artifact rejection cases;
  `verifyProductionJar` passed. The disabled JUnit action is not counted.
- Actual JNI **b10682-5ea1b124e** CPU execution passed on the normal read-only
  local/cache asset path, with no model-path override and an intentionally unusable
  HTTP endpoint. Exact authorized asset: **957,680,480 bytes**, SHA-256
  `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
  Real landmark/multilingual tokenization and repeated pooled outputs retained all
  **768 coordinates**, independently checked normalization and native tail;
  overlength inputs were rejected, not truncated. Default helper/cache restart,
  composition and owning-client-executor callbacks passed, with both helper
  generations executing real native readiness. No HTTP service or weights download.
- Actual offscreen OpenGL **4.6 Core**, Mesa **26.2.3-arch1.1**, **llvmpipe LLVM
  22.1.8**, under Xvfb. Packaged Kuwahara/fog programs executed across 25/17/33/25
  pixel resize targets in RGBA32F and Minecraft-like RGBA8. Enabled final impulse
  changed `.9 -> .1888889` / `.19215688`; wider fog retained foreground/distant
  contrast and geometry/sky horizon in all tested tiers. This is software-rendered
  OpenGL execution, not Minecraft gameplay or physical-GPU verification.
- Production artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`, SHA-256
  `5e10a028431b0eb212d95fa22db326fb6033bf5996dee32c9d882d27371c9639`.
- `git diff --check` and conflict-marker inspection passed before final staging.
  The full canonical command above was run without excluded verification tasks.

## Coverage limits / remaining acceptance

No live Minecraft gameplay, IntelliJ launch, multiplayer latency session,
loading-screen timing, physical-GPU capture or full-world throughput benchmark
was performed. Tests/probes do not prove all eleven symptoms resolved in gameplay.
Acceptance should exercise supported entry/exit/re-entry, sustained/reversing flight,
walking approach/expiry/recovery, jump/landing/step/crouch, `/tp` terrain arrival,
independent simultaneous deep/shallow players, live discovery and resource resize/
reload. The native model must remain locally available; it is intentionally not
bundled. Native missing newline/mask-token and CPU/no-GPU warnings do not invalidate
successful tested forward passes, but no semantic-quality assessment is claimed.
Gradle deprecation warnings concern Gradle 9 compatibility, not this Gradle 8.13 build.
