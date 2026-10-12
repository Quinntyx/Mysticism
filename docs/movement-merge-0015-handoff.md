# Retained movement integration + ordering merge

Scope: delayed/reordered spirit pose updates and movement-window timing only. Target
`feat-runtime-merge-0015`, for integration toward dev; no dev/main mutation.

## Retained state and resolution

The checkout arrived at timebase parent `16c00a29f6d048956fe09c00b5c1f7d90b212c75`
with a pending merge of ordering parent
`cf8dc494be72a010c057183b2d4af9790e710fb5`. The sole conflicted file was
`ClientLatentPredictor.java`. Resolution preserves both parents' behavior:

- Drain reposition/depenetration provenance every spirit tick. Skip correction
  windows, subtract the actually applied client depenetration from chosen travel,
  and retain the shared 8-block admitted-window bound.
- Consume pose-teleport invalidation even during prediction baseline breaks.
- Install the wire-ordering filters before sync, record position/basis endpoints
  **after** integration, clear history on disconnect/world/mode/epoch breaks, and
  keep periodic observer refresh even at rest.
- Preserve the server correction baseline hook, nav-before-correction sync ordering,
  downstream burst admission and actual mesh step-up correction accounting.

The requested previous-session JSONL was absent at its supplied path. A search of
that session directory found no alternate copy. Review used retained source,
index/merge stages, parent commits and the ordering handoff, not an invented
previous-session result.

## Additional real repairs

`ClientPoseSync` now resets each wire frontier on replacement of its component
instance. Otherwise respawn/replacement could restart sequences at 1 while the
old entity's sequence remained high, suppressing fresh authority indefinitely.
Replacing position does not reset the independent basis stream, or vice versa.

`BasisIntegrator384f` uses `1 - (1 - eta)^distance` remaining-angle decay. It
retains the one-block rate (and immediate eta=1 behavior), while composing
movement over different packet/tick windows instead of saturating a burst's turn.

`TraversalSteering.deepStep` integrates the moving target direction and translation
in distance-bounded midpoint substeps (<=1/8 block, <=64 per admitted window).
Captured-target easing uses each substep's current residual. No global/shared
projection frame or stationary target drift is introduced. Terminal approach
replaces the 25%-per-call clamp with a continuous speed-limited residual law:
far travel is <=movement/96; near travel decays the residual by distance with
75% reduction per block. Both client and server use these same functions.

No embedding inference/backend, native dimension/profile, persistence migration,
source octree ownership/growth, terrain mesh or render changes were made here.
This leaf does not claim to repair the other runtime units.

## Regression evidence

`MovementTimingSelfTest` exercises the real movement classifier, steering and
component sync/filter with:

- Six blocks accumulated versus 120 small movement windows and irregular windows,
  including captured-target easing and the last native coordinate.
- Angle-decay composition; deep path disagreement <0.025 projected blocks on the
  tested straight trajectories, basis agreement and 300 stationary ticks.
- Far, threshold-crossing and terminal approach movement grouped differently.
- A corrected accumulated client window, reversed travel, delayed outbound pose,
  catch-up and reordered duplicate packets.

`PoseOrderingTest` adds independent component-replacement/restarted-sequence
checks. `SpiritMovementHookContractSelfTest` checks registration/loading of both
parents' correction and baseline/history hooks, in addition to mapped descriptors.
These are offline contracts, not live mixin execution.

Original production classes were separately compiled into ignored
`build/movement-red` outputs, never restored over the working sources. Both red
probes failed as expected: original timebase math failed distance-composable angle
steering; original ordering guard rejected the replacement component's sequence 1.
The red probe Gradle run exited 1 with exactly those two assertion failures.
Retained pane `%2058`, run `237a500d80474097a88f2c1bc8475f43`.

Canonical verification uses the installed Gradle 8.13 distribution:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build --rerun-tasks
```

Final canonical build passed, exit 0 (23 seconds, all 14 tasks executed), in
original tmux session `$11` / origin `%2030`; retained pane `%2053`, run
`ec7e0639c34941d6b6b8557278d86a69`. It executed all 10 discovered main-based
runtime classes (4 embedding and 6 movement), build/descriptor/packaging/runner
self-tests and `verifyProductionJar`. Movement results: timing 30 checks,
ordering 55, hooks 17, classification 31, mesh corrections 30, burst advancement
15. The distributable remains `build/libs/mysticism-1.0-SNAPSHOT.jar`, not the
dev jar. `git diff --check` also passed.

## Limits and follow-up

- No Minecraft gameplay, artificial network latency, live mixin execution, native
  model inference or IntelliJ launch verification was performed.
- Subdivision bounds error for a straight measured window; it cannot reconstruct
  turns hidden between sampled positions. Net-displacement sampling and the
  retained 8-block bound remain; extreme bursts beyond that bound are rejected.
- Distance substeps add bounded native-vector allocations/rotation work. No live
  many-player allocation/performance profiling was performed.
- Ordering history remains bounded to 256 distinct endpoints. Extreme unacknowledged
  movement may evict history; corrections that overlap that trajectory remain
  indistinguishable from delayed movement without explicit protocol provenance.
- This changes steering/terminal-approach feel deliberately to remove window-size
  dependence; interactive trajectory/landing smoke tests remain necessary.
- Loom can emit existing CCA source-remapping and missing annotation warnings;
  production compilation and artifact verification determine build success.
