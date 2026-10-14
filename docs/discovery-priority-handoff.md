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
