# Retained async embedding unit

Branch: `feat-runtime-embeddings-embedded-async`; integration target: dev, never main.
Baseline: `bfbce90`. The retained session ended on provider timeouts without edits;
its history and the clean starting Git status were inspected. Existing commits
were preserved. No other worktree/ref was edited and no agents were delegated.

## Real behavior changed

- Weighted descriptor composition no longer executes cache-hit vector copying,
  normalization, chained descriptor processing or final weighted arithmetic on a
  calling server/render tick. Two daemon composition workers, a 64-task bounded
  queue, and eight whole-operation reservations provide explicit backpressure;
  there is no caller-runs policy or common-pool arithmetic fallback.
- Composite results reserve the existing process-wide isolated completion
  delivery capacity before work starts. Publication does not occupy composition
  workers. Failures/rejected submissions release their reservations. Standard
  CompletableFuture semantics still apply: late attachment, cancellation and
  waiter assistance can execute callbacks on callers. A critical-thread consumer
  must use an explicit executor (as the UI feedback does).
- Cancelling a composition detaches the caller without cancelling shared model
  requests. After the current descriptor settles, the private chain skips the
  remaining descriptors/final arithmetic and releases its work reservation.
- A composition captures one engine generation, not a fresh global helper
  lookup for each descriptor. A world replacement cannot silently switch an
  old composition to the replacement provider halfway through.
- Each helper readiness subscriber obtains its own engine delivery reservation
  instead of sharing copies of one future/completion stack. A blocking listener
  cannot hold unrelated accepted readiness listeners hostage.
- Integrated startup now observes common-owned readiness instead of initializing
  the provider a second time. Every world gets client-executor success/failure
  feedback. The actual Yarn 1.21.1 SystemToast API is used; readiness means the
  provider has passed its readiness check, **not** that terrain/index generation
  has completed. Stop/replacement/client shutdown suppress stale queued notices
  and release callbacks that captured the old server. Rejected UI delivery is
  logged without retaining the world or running UI work inline.
- Player-facing command/toast failures unwrap CompletionException/ExecutionException
  and preserve bounded actionable provider causes, rather than just displaying
  a wrapper class name.

Projection, source-coordinate ownership/octree growth, terrain collision/rendering,
native v2 dimensions/profile, pooling and persistence policy were not changed.
There is no v1 migration, truncation, fake production vector or new server requirement.

## Discovery and verification

New main-based suites live in `src/runtimeTest/java`, which the existing canonical
`runtimeSelfTest` runner discovers. No JUnit/plugin/dependency changes were made.

Canonical installed Gradle 8.13 / Java 21 commands, always through the required
`tmux-jobs` helper in original session `$11`, original pane `%1943`:

```text
/usr/bin/env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain build

same argv with --rerun-tasks before build
```

Initial normal and forced builds passed (exit 0); the forced rerun executed all 14 actionable tasks, including
common/client compilation, remapping, all discovered runtime suites,
`buildSelfTest` and `verifyProductionJar`. Runtime runner reported six classes:

| Suite | Actual assertions/checks on forced rerun |
|---|---:|
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingCompositionTest | 82 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessFeedbackTest | 17 |

Coverage includes all-cache-hit work/thread boundaries and snapshotting, native
768-coordinate weighted output, blocked subscriber isolation, whole-composition
saturation/recovery, inference exceptions and executor rejection, cancellation
without shared-request cancellation, skipping remaining private descriptors,
provider generation binding, independently delivered helper readiness, explicit
UI mailbox delivery, world-stop/replacement/late-stop suppression, callback
release and bounded root-cause diagnostics. Tests use fixture providers/vectors,
not a dummy production provider or model forward pass.

Packaging coverage passed: decoded Java metadata (literal/Unicode forms), 14
descriptor-only rejection fixtures, valid archive/stable intermediary names,
19 invalid-artifact rejection cases, runner discovery/order/assertions/failure/
missing-main/empty-set fixtures and remapped production artifact validation.
`test` is intentionally disabled by the project; `runtimeSelfTest` is the actual
runner. Discarded historical suites outside the configured runtime source roots
were not represented as discovered or executed.

A subsequent repeat exposed a race in the **test fixture**: `CompletableFuture.get`
helped execute its blocking dependent callback on the test waiter, and the gate
timed out. The fixture now waits for reserved delivery entry before reading that
result, rather than assuming get never helps dependent callbacks. The failure
was observed, not suppressed by weakening thread/callback assertions; production
UI delivery already explicitly uses its owning executor. Final rerun outcome
and retained handle: final `--rerun-tasks build` **PASS**, exit 0, all 14 tasks
executed and all six suites passed with the counts above. Retained session `$11`,
window `@1272`, pane `%1969`, run `29360190344041d79f80515152fa63fe`.
(The failed repeat used the same pane with superseded run
`520d77629ea34548bac2999bd92683f3`.)

Nonfatal baseline warnings: CCA source remapping / missing javax.annotation.Nullable,
API Guardian annotation enums, existing public-target mixin, deprecated APIs/
Gradle features. Malformed persistence and closed-UI diagnostics in test output
are expected negative fixtures. `git diff --check` passed.

## Integration blocker / limits

This checkout still has the baseline HTTP `EmbeddingService` default. Restoring
local native inference belongs to the separate native-provider unit, whose
existing commit `bf1a671` was inspected but not merged without a supplied merge
parent. Consequently **this candidate alone does not yet provide a no-external-
server IntelliJ launch**. The separately retained native default, tokenizer/
normalization, runtime packaging and ownership/lifecycle changes must integrate
into dev. No native weights were fetched and no actual model inference or
Minecraft/IntelliJ gameplay/UI launch was performed here.

When merging with the native/lifecycle units (`bf1a671`, `3907414`, `94c87b5` and
subsequent fixes), retain their provider selection, preparation deadlines,
server identity ownership, native serialization and worker-drained teardown.
Retain this unit's generation-bound composition and client **observation-only**
startup. Helper readiness overlaps the lifecycle unit with the same independent-
subscriber behavior; do not restore shared readiness copies during resolution.

Backpressure is intentional and retryable. Eight concurrent compositions share
32 process-wide callback reservations with individual embedding requests and
other listeners. Arbitrary foreign callbacks that never return keep their
reservations; critical-thread callback consumers still need an explicit executor
regardless of future completion/attachment/waiter ordering. The UI gate always explicitly marshals regardless of
whether readiness was already complete. Composition snapshots/CPU work are
bounded by active operations/queue, not a whole-world completeness claim.
