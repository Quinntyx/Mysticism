# Retained runtime integration (dev target)

Branch: `feat-runtime-sol-merge-0058`; target **dev**, never main.

## Preserved work and merge decisions

The checkout arrived at `ad37d5c2aeceb7c4402d179864e38daf7de2a1e1`
with a pending merge of `9b4490e677d7c3cd2b3bb6606e839202ab15f2ad`.
Both histories and all existing staged/uncommitted work were retained. Supplied
previous-session history was `{}`; the actual merge/index, implementation and
retained handoffs were inspected rather than assuming prior validation.
There were no checkout/ancestor `AGENTS.md` instructions.

Resolved four conflicts:

- `LatentPos`: explicit coordinate availability/profile rejection and ordered,
  correction-aware wire sync must coexist. A valid native ZERO is distinct from
  an unavailable/reset coordinate. Ordered sync now carries that distinction
  through the client filter; an unavailable authoritative packet clears lag
  history rather than manufacturing a ready origin. A first valid coordinate is
  accepted exactly. Prediction requires both navigation readiness and a real
  coordinate. Old packets cannot erase or resurrect newer availability.
- `SpiritTerrainService`: retain source-discovery lifecycle/diagnostics and
  loaded-source entry/restore backfill alongside actual mesh support, accepted
  frame provenance, teleport carrier baselines, coverage gating, immediate
  landmark publication and guarded walking acquisition. The single discovery
  attempt owns cancellation/completion; the superseded unused `ownerFuture`
  slot was removed. Per-window ownership proof IO remains separate.
- `SourceLandmarks`: retain per-request progress and embedding-wait reporting
  alongside generation invalidation/restart, fair priority dispatch and wider
  visible sweeps. Already-loaded entry seeds use the actual bounded FRONTIER
  scheduler; overload retains conservative discovery retry areas instead of
  silently dropping required entry work. Refresh-only retries do not substitute
  for discovery of a previously uncatalogued seed.
- `SpiritNavigationService`: retain truthful readiness/origin, request notices,
  physical-support continuity and discovery retry with adaptive walk alignment,
  proof-gated walking commit and correction ordering. Temporary mapping loss
  advances only semantically integrable movement, not the old raw delta.

Preserved independent per-player shallow/deep projection, source-coordinate
ownership/octree growth, actual custom-mesh collision and accepted geometry
revision publication. The upload-gated client frame implementation is retained;
new source/projection data cannot replace displayed collision before upload.

The first parent already contains the normal embedded JNI inference path and
both rendering probes. They were retained, not replaced by the second parent's
historical external-server implementation. In particular, the external-provider
limitation in `runtime-sol-merge-0057-integration.md` describes that earlier
candidate, **not** this integrated runtime. Native v2 profile/pinning, all 768
coordinates, no v1 migration/remapping/truncation and no synthetic fallback are
unchanged. Wider fog is not clamped to current sparse source coverage.

## Cross-parent regressions and fixture corrections

- New discovered `CoordinateAvailabilitySyncTest`: **22 checks** through actual
  CCA packet decoding, with and without the installed client reconciliation
  filter. Covers reset, explicit ZERO, in-flight prediction, reordered resets,
  late valid packets after reset, valid reinitialization, persistence,
  incompatible profile, malformed/truncated input and native tail preservation.
- `LoadedSourceBackfillTest`: **374 checks**, including actual production
  `offerDiscoveryHint` admission and required entry seeds exceeding both bounded
  hint/retry budgets. Every required seed must be immediately scheduled or
  covered by a retained discovery retry; compiled wiring checks the new fair
  dispatch path rather than the superseded queue API.
- Movement reconciliation/ordering/timing fixtures now explicitly construct
  anchored ZERO coordinates. Default/reset coordinates are deliberately
  unavailable; the new wire regression covers that state independently.
- `AsyncEmbeddingCallbackIsolationTest` uses a separate five-second *startup*
  budget for two request-isolation fixtures. Their real 120/300 ms request
  deadlines and distinct preparation-timeout regression remain intact. The
  earlier build exposed scheduler-sensitive 120 ms startup under full rebuild.

## Verification

Completed-source command, using the cached distribution matching the pinned
Gradle 8.13 wrapper (no software installation or dependency changes):

```sh
xvfb-run -a /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain --max-workers=2 --rerun-tasks \
  build kuwaharaGpuTest embeddedInferenceProbe \
  -PembeddingModelPath=/home/zlare/docs/src/Mysticism/feat-runtime-sol-merge-0038/build/embedding-inference-probe/nomic-v2.gguf
```

The model path is an already-provisioned read-only weight input, not a runtime
external-server prerequisite. Normal runtime still provisions/verifies its
pinned model. No source in another checkout was modified.

First integrated pass: exit 0, **42 discovered runtime main suites**, build and
packaging/runner discovery/failure fixtures, remapping and
`verifyProductionJar`; 17 actionable tasks executed. Framework `test` is
intentionally disabled; `runtimeSelfTest` actually discovers and invokes the
main-based suites with assertions, including the new tests. Retained tmux
session `$11`, origin `%2989`, pane `%2990`, run
`426b3e445f884be0959857143bf336da`.

Real offscreen OpenGL execution passed on Mesa llvmpipe 4.6 at
25/17/33/25-pixel target sizes in both RGBA32F and game-style RGBA8 targets.
Kuwahara final impulse changed from 0.9 to 0.1888889 (float) / 0.19215688
(RGBA8). Fog exercised perspective depth, geometry/sky horizons, foreground
clarity and useful distant contrast across all distance tiers and resize cycles.

Real CPU JNI inference passed, not just output-shape mocks or source review:
JNI `b10682-5ea1b124e`; exact weight SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`;
**768 native coordinates retained, no HTTP service**. The probe also exercised
normal embedded-provider readiness and runtime descriptor embeddings. Observed
oak/birch cosine 0.86688715 versus oak/lava 0.8003141 is a smoke observation,
not a corpus-quality claim.

Final repeat after removal of the obsolete owner-future slot also passed:
**exit 0, 57 seconds, all 17 actionable tasks executed**, the same 42 runtime
suites and both real probes, production jar verified. Retained original session
`$11`, origin `%2989`, window `@1800`, pane `%2990`, run
`16503d1e1e854fe491aeb5224c93ee69`. No production/test source changed after
that completed run. Both working-tree and staged `git diff --check` passed.

Existing annotation/mixin-target/Loom/Gradle deprecation diagnostics are
nonfatal. Malformed persistence fixtures deliberately log rejected data; JNI
warns about missing optional vocabulary tokens and the absence of GPU inference.

## Limits

No live Minecraft world, actual `/spirit enter`/`/tp` gameplay, multiplayer
latency, IntelliJ launch, F3+T/window callbacks or asynchronous source-world
chunk lifecycle was exercised. Offscreen software GL is not a hardware-driver
or Minecraft resource-lifecycle test. Model inference was real CPU JNI, not
GPU inference or first-run weight downloading. Source discovery fairness,
revision coherence and movement/support are regression-tested production paths,
not an end-to-end world throughput/landmark quality benchmark. This merge does
not claim live verification of all eleven original symptoms or repair of the
separately retained entry-screen behavior. No build blocker remains on the
verified integrated source.
