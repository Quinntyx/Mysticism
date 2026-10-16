# Retained merge-0032: native integration reevaluation

Owned branch `feat-runtime-sol-merge-0032`, targeting **dev**, never main.
The earlier [HTTP-only integration record](runtime-sol-merge-0032-handoff.md)
is historical, not the current implementation.

## Preserved histories and combined scope

The retained candidate already includes both recorded original parents:

- `61d434f93c7181c8ea9fd460c2e20564eaa88d77` (source reload + inference ownership)
- `f9eff09692e519a336aebccc14527ab9fe675ba6` (initialization/shutdown + spirit picking)

This reevaluation merges the reviewed native/local-asset/pooling/live-readiness
implementation `bf17b7a3bca4f0bc09db26d9a3a36694846d3fc2` (merge-0063 baseline),
not a replacement first-parent-only patch. The shared discovery/composition probe
from reviewed `e84ff3a1e47f19358f710dcade99264e9ffa6b42` is additionally ported
with server-identity ownership and a complete native drain. Existing commits and edits were preserved;
no reset, stash, software installation, model download, other-checkout edits or
dev/main mutation was performed.

### Source and interactions

- Generated-only live/saved chunk-reader transitions, truthful UNKNOWN cells,
  unfinished-slice invalidation, all feature-frontier reload retries and completed
  neighboring observations are retained alongside the incoming vertical scheduler.
  Chunk unload/reload invalidates/requeues its vertical observations without losing
  the resumable structural frontiers. Readiness admission gates remain live.
- Independent per-player deep/shallow projection, source-coordinate octree
  ownership/growth and custom terrain collision/render algorithms are unchanged.
- Vanilla spirit carrier picking/outlines remain suppressed, including packet
  entry gaps. Projected inverse-affine, mesh-occluded peer touch and crosshair
  feedback remain shared; non-spirit vanilla interactions are unaffected.

### Actual default native lifetime

- `EmbeddingHelper` now lazily selects `EmbeddingProviders.create()` on a worker.
  The default is local CPU/JNI inference, never HTTP fallback or auto-provisioning.
  The helper keeps server-owner identities and its 15-minute embedded preparation
  budget (30 seconds for explicitly selected HTTP).
- `LocalNomicV2ModelAsset` verifies the exact complete GGUF before and after native
  allocation. Model/tokenizer identity, native MEAN pooling, document prefix,
  stable full-768 normalization and schema 2 are retained. No v1 migration,
  truncation, substitute model or dummy production vectors are introduced.
- The outer `EmbeddingProviderSession` serializes readiness and every complete
  inference/output snapshot. Full native normalization occurs before exclusive
  ownership is released. A definitive model/output failure revokes the session
  **before** a waiting request can enter the damaged runtime.
- `requestStop()` is only bounded cancellation, propagated through the lazy owner
  into the native provider. Stop wakes waiters and revokes readiness/cache/pending
  requests without closing JNI under active work or waiting on the server thread.
  Returning workers do not dispose merely because stop was signalled.
- After every worker drains, the cleanup owner calls native `close()` synchronously
  to completion. The process-exclusive lease covers factory, inference and actual
  native teardown. The imported provider's previous detached future disposal
  would have released that lease early; it is removed here. Empty cancelled
  launches still bypass a cleanup queue blocked by another runtime's hung close.
- A native teardown failure poisons the lease, including failure closing a
  candidate rejected by post-allocation integrity/cancellation checks. That cause
  is retained and reported, not hidden by an apparently successful later close.
  Failed readiness or live model loss remains FAILED with the real cause rather
  than being presented as ordinary STOPPED.

## Regression/probe extensions

- `EmbeddedLifecycleIntegrationTest`: blocking native close, post-load candidate
  cleanup failure, process-lease poisoning, deadline cancellation during a held
  native loader and no admission after stop. Fixtures do not load a model.
- Borrowed-buffer tests retain both direct and production lazy-wrapper paths;
  invalid model output now checks terminal session revocation without losing
  generic descriptor-error recovery or full coordinate/cache isolation coverage.
- Readiness fixtures distinguish a failed operational engine from explicit stop;
  native timeout fixtures wait for the actual stop signal, not callback scheduling.
- The explicit real-weight probe now exercises the actual helper default factory
  and identity path across **three world identities in one JVM**: concurrent
  different descriptors compared at all 768 coordinates against direct native
  references, cache mutation isolation, pending-request retirement, late old-owner
  stop isolation, complete JNI teardown and reopen. The additional shared-source
  probe checks real cave landmark composition at every coordinate against an
  independent weighted/L2 reference, readiness-gated discovery admission (without
  blocking physical reads), no fabricated source anchors, and shutdown revocation
  of cached inference and fresh discovery. These are not a Minecraft launch.

## Checks actually performed

Environment: Java 21, installed Gradle 8.13, original tmux session `$11`, origin
`%3573`; commands use the required `tmux-jobs` helper with argv and bounded monitors.
No substitute model, service or provisioning flag was used.

The final canonical rerun, including the real native lifecycle/discovery probe, executed:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
  --offline --no-daemon --console=plain --continue --rerun-tasks
  build buildSelfTest runtimeSelfTest verifyProductionJar embeddedInferenceProbe
  -PembeddingProvision=false
```

**PASS**, exit 0, **48 seconds; all 16 actionable tasks executed**.
Retained pane `%3604`, run `0cd965f3b0404f94808262cfc6999868`.
Earlier canonical builds also passed (35 seconds, run
`7ba55a65706543879e9b1ef979fd695f`; 33 seconds with all 15 actionable tasks rerun,
run `3df50742a6524f3782715b79f0467db9`, same pane). The final combined rerun includes
the complete real-model probe, shared-source composition/admission checks,
timeout-fixture scheduling repair and HTTP-specific preparation budget refinement.
`buildSelfTest`, runtime discovery/runner fixtures, all **24 discovered** runtime
suites and the remapped `build/libs/mysticism-1.0-SNAPSHOT.jar` verifier passed.

| Suite | Actual checks/assertions |
| --- | ---: |
| SpiritPickingContractSelfTest | 38 |
| SpiritTouchPickingSelfTest | 25 |
| AsyncEmbeddingCallbackIsolationTest | 29 |
| AsyncEmbeddingEngineSafetyTest | 344 |
| EmbeddedDiscoveryReadinessSelfTest | 956 |
| EmbeddedEmbeddingSelfTest | 57 |
| EmbeddedInferenceConcurrencyTest | 43967 |
| EmbeddedLifecycleIntegrationTest | 38 |
| EmbeddedLifecycleTest | 186 |
| EmbeddedNativeOutputTest | 3147 |
| EmbeddedNativeRuntimeSelfTest | JNI ABI b10682-5ea1b124e, not inference |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 136 |
| EmbeddingReadinessSelfTest | 52 |
| LandmarkEmbeddingPoolingTest | 808 |
| LocalNomicV2ModelAssetTest | 47 |
| NativeEmbeddingOutputTest | 107 |
| NomicV2ModelAssetsTest | 113 |
| PinnedEmbeddingModelSelfTest | 25 |
| GeneratedSourceLifecycleTest | 55 |
| SourceFrontierReloadTest | 723 |
| VerticalDiscoveryTest | 8900 |
| VerticalSchedulerTest | 43324 |
| DiscoveryReadinessSelfTest | 18 |

The deliberate native-close exceptions and malformed persistence traces are
negative fixtures. Existing annotation/Mixin, SLF4J binding, Jackson version and
Gradle deprecation warnings remain nonfatal.

## Real model and helper lifecycle verification

The authorized exact asset is available at
`/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf`
(**957680480 bytes**, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`).
It is not missing now; prior missing-asset failures are historical. The local
runtime verifies complete bytes both before and after native allocation; no model
path, dimensions or substitute was introduced for this check.

Using the same installed Gradle executable and original session/origin:

```text
gradle --offline --no-daemon --console=plain embeddedInferenceProbe
  -PembeddingProvision=false
```

An earlier standalone run **passed**, exit 0, 21 seconds; 7 actionable tasks,
1 executed and 6 up-to-date (pane `%3622`, run `02b3540c43de424cb0027392a68689cf`).
The final combined rerun above additionally passed the shared-source probe, with
no up-to-date actionable tasks. Final output includes:

```text
Real landmark tokenizer/pooled-output checks passed for 4 texts
  (all 768 coordinates compared; no semantic-quality claim)
REAL in-process inference passed; JNI=b10682-5ea1b124e,
  SHA256=a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f,
  dimensions=768, all coordinates retained, no HTTP service
REAL helper lifecycle passed: 3 world identities in one JVM,
  concurrent full-768 output comparisons, retiring-request cancellation,
  late-stop isolation, completed JNI teardown/reopen
REAL shared EmbeddingHelper readiness -> discovery admission -> cave landmark
  composition passed; all 768 coordinates checked against independent weighted/L2
  reference; no HTTP service or source anchor fabricated
REAL shared lifecycle shutdown revoked fresh discovery/inference readiness
  (not a simulated native fault)
```

This is **this retained candidate's real execution**, not inherited parent evidence.
It uses the production helper's default factory, never a test-injected provider or
HTTP service. Native CPU inference, repeated complete teardown and reopening passed.
The observed oak/birch cosine was 0.86688715 and oak/lava 0.8003141; these are not a
semantic-corpus quality claim. Overflow input is rejected, not truncated.

The runtime reports missing newline/mask vocabulary and speculative-decoding/GPU
warnings for the exact approved artifact; none were treated as permission to
reconvert/change it. CPU embeddings still passed the full-output checks. Several
initial launches failed before starting with transient helper `Job workspace busy;
retry later`; the later canonical/probe jobs above launched and completed normally.
No raw-shell/background/split fallback was used.

## Limits

No live client/server/IntelliJ launch, GPU effects, movement, real chunk IO,
render-distance performance or cross-platform model execution was verified.
There is no remaining asset, HTTP-service or executed-check blocker for this unit.
A hung native call or
failed close intentionally blocks replacement runtimes until it returns or the
JVM restarts; serialization trades throughput for safe native ownership. Exact
asset verification costs two whole-file reads per initialization. Vertical source
sampling remains bounded, not an exhaustive cave census. The other reported
runtime problems outside this retained combined scope are not claimed fixed.
