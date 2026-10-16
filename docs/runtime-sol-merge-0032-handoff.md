# Retained runtime integration: merge-0032 (pre-native record)

Historical checks for `cdc2de4a2622455bdd8cb078391f968f83529402`.
The earlier HTTP-only blocker below is superseded by the
[native integration and candidate-specific evidence](runtime-sol-merge-0032-native-validation.md).

Owned branch: `feat-runtime-sol-merge-0032`, development work only. This completes
its already-started merge of `f9eff09692e519a336aebccc14527ab9fe675ba6` into
`61d434f93c7181c8ea9fd460c2e20564eaa88d77`. Neither parent history nor retained edits
were reset, stashed or replaced.

## Integrated behavior

- Retains the source unit's generated-only live/saved reader transitions and all
  feature frontier reload retries. Unfinished chunk slices are discarded before
  switching adapters; absent/unproven cells remain UNKNOWN. Reload can supersede
  stale disk reads without losing completed neighboring observations. Source
  anchors, octree ownership/growth and independent player projection are unchanged.
- Retains dimension-scoped suppression of vanilla carrier picking and block
  outlines, including entry packet gaps. Crosshair feedback and peer touch share
  the projected, inverse-affine mesh-occluded target query, never vanilla hit
  entities or source/carrier blocks. Non-spirit picking is unaffected.
- Every engine call uses `EmbeddingProviderSession`, including readiness: exclusive
  ownership extends through validation, the full native-vector snapshot and
  normalization. The lazy lifecycle lock alone is insufficient because releasing
  it on return would expose a borrowed buffer to the next inference before copying.
- Resolves shutdown by separating **stop admission** from **dispose resources**.
  `requestStop()` wakes session waiters and signals the lazy provider without
  running model close. After *all* workers terminate, the engine schedules session
  disposal on the cleanup thread. The returning active owner does not dispose
  merely because stop was requested. This avoids both active tensor destruction
  and blocking idle native close on the server exit thread.
- Preserves lazy factory construction on workers, server identity checks, readiness
  deadlines, process-exclusive factory/inference/teardown leases, independent
  callback reservations and the unopened-session fast path. Cancelled unopened
  launches do not queue behind a hung native close. Failed teardown retains its
  lease and exposes/logs the failure instead of allowing an unsafe reopen.

## Added integration regressions

`EmbeddedInferenceConcurrencyTest` now runs the deliberately unsafe borrowed-buffer
fixture both directly **and inside the production lazy lifecycle wrapper**, checking
24 simultaneous distinct descriptors, full 768-coordinate values (including 767),
cache isolation, validation/error recovery and exactly-once off-thread disposal.
Shutdown during readiness/inference explicitly checks cleanup-thread ownership
rather than accepting disposal on the last worker. `EmbeddedLifecycleTest` checks
serialization at the outer session/worker boundary, not the superseded inner-lock
queue location. The conflicting safety test retains both the drain latch and actual
provider-disposal assertion.

These are fixtures exercising real production ownership/lifecycle code, **not real
model inference**. No v1 migration, truncation, dummy production vectors, dependencies,
model downloads, packaging exclusions or test-discovery changes were introduced.

## Canonical verification

Installed Gradle 8.13 / Java 21, offline, no task exclusions or diagnostic classpath:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
  --offline --no-daemon --console=plain --continue
  build buildSelfTest runtimeSelfTest verifyProductionJar
```

Exit **0**, `BUILD SUCCESSFUL in 40s`; all 14 actionable tasks executed. Original
tmux session `$11`, origin `%2255`, retained pane `%2260`, run
`38170f85083445f4bcb5cffba16c3ebf`.

The runner and compiled top-level test output agree on **10 discovered suites**:

| Suite | Passing checks/assertions |
| --- | ---: |
| SpiritPickingContractSelfTest | 38 |
| SpiritTouchPickingSelfTest | 25 |
| AsyncEmbeddingCallbackIsolationTest | 29 |
| AsyncEmbeddingEngineSafetyTest | 344 |
| EmbeddedInferenceConcurrencyTest | 43959 |
| EmbeddedLifecycleTest | 186 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 136 |
| GeneratedSourceLifecycleTest | 55 |
| SourceFrontierReloadTest | 723 |

`buildSelfTest` passed decoded metadata, descriptor, positive/negative packaging and
runner discovery/order/assertion/failure fixtures. `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar`. The native-close exception and malformed NBT
warnings are expected negative fixtures. Existing API Guardian/mixin/deprecation
warnings remain nonfatal. The exact committed HEAD/push result is in the completion
report (not self-referentially embedded in this commit).

## Real blockers and limits

**In-process embeddings are still blocked.** `EmbeddingHelper` currently supplies
`EmbeddingService::new`, and that provider is HTTP/Ollama. This checkout contains no
in-process adapter/runtime packaging or v2 model assets. The lifecycle/concurrency
integration does not remove its external-server requirement, and no real model or
IntelliJ Minecraft launch was performed. A separately supplied real provider must
use this lazy factory/lease boundary, unwind partially constructed resources on
failure, finish native work/release per-call tensors before returning, and expose
metadata-only `profile()`. Full native v2 coordinates must remain intact. Existing
packaging rules also require the real runtime integration owner's matching update.

No live client/server, GPU effects, Mixin application, movement or real chunk IO
lifecycle was verified. The other reported runtime areas outside this retained
scope are not claimed fixed here. A hung native call/close intentionally prevents
replacement runtimes until it returns or the JVM restarts; serialization trades
throughput for safety. Existing discovery/retry budgets, UNKNOWN-data deferral and
cold synchronous bounded catalogue IO limits remain (see the source-unit handoff).
