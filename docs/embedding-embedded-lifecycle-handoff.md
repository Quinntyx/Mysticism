# Embedded provider lifecycle unit

Scope: `feat-runtime-embeddings-embedded-lifecycle`, based on
`bfbce90c4752e8f6ee821769eb9d51a43453a13b`. The retained session had only inspected
sources before provider timeouts; its branch and checkout were clean. No history
was replaced. Movement, terrain ownership/projection, rendering and vector/profile
semantics are unchanged.

## Actual behavior

- `Mysticism` passes the actual server identity to helper start/stop. Duplicate
  startup for that owner is idempotent. Replacement start detaches and cancels the
  previous session; a late stop from the old owner cannot stop the replacement.
- `EmbeddingHelper` constructs only a lazy `LifecycleEmbeddingProvider` under its
  short lifecycle monitor. The real provider factory, profile validation and
  readiness probe run on an inference worker, covered by the readiness deadline.
- A process-wide lease spans factory execution, all inference, **and completed
  provider close**. A new session waits interruptibly for the old one to drain;
  exiting a waiting session does not open a model or accumulate cleanup jobs.
  A constructor that swallows interruption cannot proceed to probing after exit.
- Calls to one provider/predictor are serialized using an interruptible lock.
  Factory implementations must unwind partially created native resources if
  construction throws; the wrapper can own only a successfully returned provider.
- `AsyncEmbeddingEngine.close()` immediately rejects readiness/work, clears cache
  and pending requests, interrupts workers, and returns without waiting for native
  work. Provider resources close once on the daemon cleanup thread **only after
  worker termination**. A timeout is not evidence that native work has returned.
  Readiness failures/timeouts also initiate this drain automatically.
- Late native results cannot repopulate a stopped engine. Readiness queried after
  successful startup followed by shutdown fails rather than reporting old success.
- Helper readiness listeners have independent engine delivery reservations.
  Foreign callbacks cannot hold a shared future chain or delay lifecycle status;
  status comes from the currently owned engine, not a late callback snapshot.
- Native close failure is logged with its cause and retains the process lease.
  Later startup fails its bounded deadline rather than opening another runtime
  over possibly live native resources. A permanently hung native call/close also
  leaves embeddings unavailable until that call returns or the JVM is restarted.

## Integration boundary / real remaining blocker

This baseline's `EmbeddingService` is still the existing Ollama HTTP implementation;
there is **no in-process model implementation, native runtime packaging or supplied
v2 model asset in this checkout**. This unit does not claim to remove that existing
external-server requirement. The in-process runtime unit must supply the real
provider factory at the helper's two `EmbeddingService::new` call sites, retaining
the lazy wrapper and its lease. Do not instantiate a model before passing it to
this wrapper, remove the drain barrier, or add v1/dummy/truncated fallback vectors.
`profile()` must remain metadata-only and match the approved current profile.

The lifecycle uses `EmbeddingProvider` and has no DJL/ONNX/model dependencies. The
current native **768-coordinate v2** profile is untouched. Tests' fake vectors are
explicit fixtures, never production inference. No live model, external service,
IntelliJ client/server launch, gameplay or native GPU cleanup was exercised here.
The current artifact verifier also excludes native model runtimes; real provider
integration will require its owner's matching packaging changes and verification.

## Verification

Canonical `./gradlew --no-daemon --console=plain build` passed with no task
exclusions or diagnostic classpath overrides. The existing runner discovered all
five top-level compiled suites, including the added `EmbeddedLifecycleTest`:

- `AsyncEmbeddingCallbackIsolationTest`: 29 checks
- `AsyncEmbeddingEngineSafetyTest`: 264 checks
- `EmbeddedLifecycleTest`: 186 checks
- `EmbeddingPersistenceTest`: 90 assertions
- `EmbeddingPipelineTest`: 136 assertions

The new suite exercises stop during a noninterruptible factory/probe/inference,
replacement without an intervening stop, late stop ownership, duplicate startup,
factory/profile failures, 30 repeated sessions, cancelled sessions behind blocked
native close, independent helper listeners, serialized predictor use, tail
coordinate 700 preservation, and fail-closed teardown. Existing assertions about
provider close now explicitly await asynchronous resource cleanup instead of
assuming that lifecycle exit blocks. Test discovery was checked against compiled
`build/classes/java/runtimeTest` and canonical runner output; no Gradle discovery
or packaging rules were weakened.

The compiled lifecycle suite also passed 20 consecutive fresh-JVM invocations
(186 checks each, fake providers only). Retained stress pane `%1893`, run
`9822be63da4648b1be1c03d23d45c90c`, exit 0; captured output contains 20 passing
suite markers.

`buildSelfTest` passed metadata/bytecode/packaging rejection and discovery/order/
assertion/failure fixtures. `verifyProductionJar` verified the remapped
`build/libs/mysticism-1.0-SNAPSHOT.jar`. Expected negative-fixture close failure
and corrupt-NBT traces appear in passing output. Earlier development runs caught
and corrected a test latch shadowing error; final passing runs use the corrected
fixture, not exclusions. Nonfatal baseline warnings remain (deprecated Gradle
features/APIs and API Guardian/mixin annotation warnings when client compilation
runs).

Retained canonical build evidence: original tmux session `$11`, origin `%1861`,
pane `%1870`, run `d0af486de7664bc49bf67b277795920a`, exit 0. Build/test output is
retained in its job pane; monitor/capture with the project's required tmux-jobs
helper and the pane/run pair rather than treating helper exit as build success.
