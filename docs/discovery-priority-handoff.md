# Source discovery priority repair

Scope: retained discovery-priority branch, follow-up to `f39029ce01f62a7b1867d0d77a244b754b693f2e`.

## Behavior

The shared scheduler now allows at most one consecutive background activation
before dispatching an eligible waiting READ/PLAYER operation. The budget is
shared by explicit requests and discovery hints, and is charged in the actual
`SourceLandmarks.Session.tick` activation path, not during selection. An urgent
hint is selected by filtering the whole hint queue, so old aged backfills cannot
hide it. Eligible reads still bypass model-unready discovery.

Aging continues to give oldest retained background work a turn despite urgent
churn. With an older aged background backlog and continuous urgent arrivals,
background and urgent dispatches alternate. Without eligible urgent work,
background drains without idling. Clears reset dispatch history. Existing queue
capacity, eviction, locality distance ordering, coverage and rotating visible
sweep remain unchanged; no ownership/projection/mesh/vector-format changes.

This is a dispatch bound, not a wall-clock latency guarantee. Active extraction,
source IO, inference and publication remain non-preemptive; individual operations
can still delay near-terrain availability.

## Verification actually performed

- The full aged 128-hint backlog followed by urgent request regression fails
  against the retained scheduler and passes with this repair.
- `DiscoveryFairnessTest`: 2,330 checks; both urgent tiers, fresh/aged waiters,
  full hint admission, shared hint/request fairness under sustained churn,
  background-only draining, read-only selection, readiness and clear/reset.
- Canonical cached Gradle 8.13 / Java 21 command:
  `gradle --no-daemon --console=plain --rerun-tasks build`.
  **BUILD SUCCESSFUL**, exit 0, all 14 actionable tasks executed. Nine current
  runtime test mains discovered and passed, including the new fairness test;
  packaging/runner fixtures and remapped production jar verification passed.
- Build retained in tmux session `$11`, pane `%2368`, run
  `30ad074461cf460882d0e432e6694b3f` (origin `%2360`).
- `git diff --check` passed. Annotation/deprecation warnings and expected corrupt
  embedding persistence fixture logs remain, with no failed checks.

No Minecraft gameplay, GPU, live server tick, or real model inference was tested.
Other runtime/model restoration units are outside this repair's assigned scope.

## Chunk-load availability follow-up

Follow-up to `adcd833d7dc97be3331d57a14df3cf3f17eb04d6`:

- CHUNK_LOAD now invalidates completed background coverage and marks overlapping
  active observations for refresh, rather than exceptionally completing the
  caller's future. Loads coalesce until the owner-thread tick.
- A fresh Read/Ensure attempt retains the exact original future, source query,
  preferred owner, activity flag and priority tier. The one-shot owner-binding
  callback therefore receives fresh success instead of a terminal cancellation
  that unrelated background backfill cannot repair.
- Old observation/preparation/catalog state is discarded; existing reader/work,
  geometry/mutation/history cancellation and mutation-pause release are reused.
  A detached, non-cancellable worker cannot deliver through the old attempt.
- Retries reenter the shared scheduler, aging from requeue time so a repeatedly
  invalidated background operation cannot monopolize oldest-aged dispatches.
- Restart uses the vacated active slot, never ordinary overflow admission or
  caller eviction. With a full 64-request queue, the reserved active slot may
  temporarily be the 65th queued entry; the scheduler must activate a retained
  request before activating another hint. Repeated active-hint invalidations
  cannot grow the queue. Once reclaimed, hints again share normal scheduling.
- Caller cancellation, source edits/unloads, shutdown and non-observation
  topology invalidation remain terminal. Ownership, projection, rendering,
  collision and embedding formats/inference are unchanged.

Verification on this follow-up:

- New automatically discovered `SourceAvailabilityRestartTest`: **1,434 checks**.
  Covers retained binding callback, fresh state, detached worker completion,
  repeated/coalesced loads, Read.advance refreshed result, readiness, completed
  and nonoverlapping operations, full admission, budget bounds under sustained
  active-hint load churn, background fairness and terminal cancellation.
- Isolated negative mutation restoring terminal availability cancellation failed
  as expected: `neighbor load must not consume caller callback`. This uses a
  copied source/classpath under ignored `build/discovery-availability-regression`,
  not any reset or replacement of retained tracked files.
- Canonical cached Gradle 8.13 / Java 21 command
  `gradle --no-daemon --console=plain --rerun-tasks build` succeeded, exit 0:
  all **10 discovered runtime mains**, packaging/runner self-tests, and remapped
  production-jar verification passed; all **14 actionable tasks executed**.
  The existing fairness suite still passed 2,330 checks. `git diff --check` passed.
- Final build retained in session `$11`, pane `%2633`, run
  `82b2102c829d47a5b80fb65f979d7089`, originating pane `%2607`.
  Negative mutation retained in pane `%2655`, run
  `fade39490aa944efa056fc4af21f5728` (expected exit 1).

These are server-free lifecycle/scheduling tests with explicit metadata fixtures,
not a live Minecraft chunk-event, store transaction, gameplay or model-inference
verification. Continual overlapping availability changes can delay completion
until the source stabilizes; caller intent is retained, but no wall-clock latency
bound is claimed. Non-cancellable worker work can continue until it finishes.
