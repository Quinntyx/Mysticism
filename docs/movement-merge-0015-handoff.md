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


## Retained review repair — initial verification blocked (resolved below)

This section concerns the repair AFTER retained merge `dbb1ff2`; the passing
canonical build above belongs to that older merge, not this candidate.

- Prediction context now registers before movement integration. Each component
  retains its latest received wire sequence/revision/authoritative pose even
  before the predictor knows the replacement entity. Context initialization uses
  that baseline, or a pre-integration baseline if no packet has arrived. First
  delayed periodic snapshots therefore use normal pending-movement reconciliation.
- The real shared deep integrator exposes a substep observer. Client history
  records its distance-bounded curved rotations, not only whole-tick endpoints.
  Server integration is unchanged; no global/shared basis or new steering control.
- A suppressed server overlap window explicitly carries `requiresReconciliation`.
  The server marks BOTH pose components and broadcasts immediately, not only on
  cadence. Session-local correction revisions travel with each pose payload,
  after the normal persistence serialization. A newer marked pose is authority
  even when it resembles movement lag. Position and basis revisions remain
  independent; old wire sequences cannot undo them. Clean windows retain their
  measured semantic movement. Disputed windows still exclude travel for collision
  safety, but no longer silently diverge or hold a resting prediction indefinitely.

`MovementReconciliationSelfTest` uses real steering/classification and actual
component packets. It covers pre-context component replacement, first-ever sync
arriving after prediction, +1/-0.7 deep reversals against unequal .07/.29/.5/.73/
.95/1-block server windows, oblique travel and captured-target easing, independent
suppressed-window convergence after delayed delivery, 300 resting ticks, resumed
movement and reordered pre-correction packets. No dummy inference is involved.

Before production edits, `runtimeSelfTest` discovered/executed the new class and
failed both original probes: pre-context position rollback and intermediate curved
basis rollback. Other suites reached before that failure passed (embedding
callback isolation 28, engine safety 184, persistence 90, pipeline 135, mesh
corrections 30 and classification 31). These are RED-run results, not candidate
verification. Original session `$11`, origin `%2400`, pane `%2409`, run
`07db8668d5884c119085d8b64fb17dbf`, exit 1 (39 seconds). That slot was subsequently
reused by an unsuccessful build start, so its original token is now stale; the
captured failure is recorded here rather than claiming it remains monitorable.

Candidate canonical command was attempted through the required tmux-jobs helper:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build --rerun-tasks
```

It could not start: `respawn pane failed: fork failed: No space left on device`.
The host reported 4,094/4,096 allocated PTYs; disk/inodes were available. The
failed start left a blank slot that tmux-jobs reports as running, but tmux reports
no process PID (`-1`): pane `%2440`, run `4e1e37af9b534ceba1d233bacb4a2ef3`,
window `@1498`, original session `$11`. A later start was refused as busy. No
command output or canonical exit code exists. No other pane was closed, no host
limits changed and no software installed. Re-run after resolving this host
resource issue; do not use the old passing build/artifact as this candidate's
verification. `git diff --check` passes; production compilation and green
regressions remain UNVERIFIED, including packaging/production-jar checks.

No gameplay, artificial network-latency test, live mixin execution, native model
inference or IntelliJ launch was performed. Native v2 embeddings/persistence,
source ownership/octrees, terrain/collision implementation and independent
per-player projection are untouched. History is still bounded to 256 endpoints;
with distance substeps this retains less physical travel than whole-tick history.
Off-trail corrections without explicit provenance still use the retained fallback
lag heuristic. Persistent server/client collision disagreement can still require
repeated authoritative correction; this repair makes each suppression explicit,
not proof that every terrain disagreement has been eliminated.

### Authorized restart: canonical verification passed

The authorized restart preserved repair commit
`41ede75f84ea72686e87960a06cfa86444ba125e` and its clean checkout. Host PTY use
had fallen to 146; the prior origin `%2400` no longer existed. The continuation
bound its original restart pane `%2547`, window `@1552`, in the same session
`$11`. No host resource or other agent's workspace was changed.

The canonical Gradle 8.13 `--offline --no-daemon --console=plain build
--rerun-tasks` command then PASSED, exit 0, 31 seconds, all 14 tasks executed.
Job used: pane `%2550`, window `@1555`, run
`f903da39178d4b3b9f70fa403a90099c`, origin `%2547`. It compiled production/client
and runtime tests, executed all **11 discovered** runtime main-based classes,
ran build/descriptor/packaging/runner self-tests and verified the remapped
production jar `build/libs/mysticism-1.0-SNAPSHOT.jar`.

Runtime results: new reconciliation **77** checks, timing 30, ordering 55,
hooks 17 (offline contracts), classification 31, mesh corrections 30, burst
advancement 15; embedding callback isolation 28, safety 184, persistence 90,
pipeline 135. The corrupt-data persistence tests deliberately emit warnings and
caught-error traces; the runner completed all suites successfully. Existing
missing-annotation, mixin-target and Gradle-deprecation warnings remain nonfatal.
`git diff --check` also passes.

The earlier PTY/canonical verification blocker is resolved. The original red
probe and interrupted-start history above remains an audit trail, not a current
candidate failure. Source/CI success still does not establish live Minecraft
behavior or real native model inference; the limits above remain in force.


### Pre-context ordering boundary regression

A final initialization-path check found that an older packet could still replace
the component's pose before predictor registration, despite the new cache retaining
the newer authority. `LatentPos` and `LatentBasis` now reject stale/duplicate
sequences at their component boundary, before reconciliation, including replacement
and peer packets. Newly received peer authority still applies normally. The cache
and live baseline therefore cannot disagree merely because packets reordered
before the local prediction context existed.

The added actual-packet regression failed before the guard with
`pre-context reordered position cannot replace the newest received baseline`.
Red `runtimeSelfTest`: exit 1, 11 seconds, pane `%2558`, window `@1555`, run
`09727d6b07c3415ca5408adae8f950f0`, origin `%2547`, session `$11`.

After the guard, the canonical full `build --rerun-tasks` PASSED again: exit 0,
33 seconds, all 14 tasks executed, all 11 runtime suites plus packaging and
production-jar verification. Reconciliation now passes **81** checks; other suite
counts are unchanged. Build pane `%2550`, run `92186f0f3a7f47138603488c4e3d19ab`
(historical handle; the same stable build pane is reused for final-HEAD verification).
The final delivery report carries the latest pane/run token. All substantive
retained commits/review feedback remain preserved; no review round was restarted
or child agent created during this continuation.
