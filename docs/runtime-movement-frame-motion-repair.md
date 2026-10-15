# Runtime observer frame-motion repair

Scope: keep an individual player's projection continuous across observer-frame
revisions while moving. This is a repair on `feat-runtime-movement-frame-motion`,
not completion of every item in the eleven-problem runtime request.

## Callback ownership

`ShaderManager.updateSession` invokes `ClientSpiritCache.syncObserver` both at
end-tick (before the predictor) and at render start. Previously those refreshes
consumed authoritative revisions using the previous tick's movement. The
predictor then rolled history over the revision; its own mirror publication was
ignored as unchanged. Basis revisions snapped and movement-start revisions lost
translation compensation.

`syncObserver` now stages the observer snapshot through `refreshObserver` without
publishing or rolling render history. `ClientLatentPredictor` reads fresh
components, computes the current displacement with the existing player/world,
motion-epoch, deep-mode, semantic-ready and teleport guards, then publishes once
through `beginTick(movement)` before prediction. Position, basis and continuity
offset history remain coherent. No prior-movement fallback remains. Stationary
revisions still publish on ticks; unchanged mirrors retain predicted state.
Pending snapshots are discarded by session clear/disconnect.

The original traversal math and component authority remain intact. The repair
changes no source octree ownership, terrain collision/rendering, embedding
profile or per-player deep/shallow policy. Both prior frame-motion commits
`ce2916b` and `bb0b705` remain in ancestry.

## Validation

Commands ran visibly in tmux window `Mysticism:frame-motion-repair`, pane `%1216`,
using the existing Gradle 8.13 and Java 21, offline, without exclusions, new
services, model downloads or system installs:

```sh
GRADLE=/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
"$GRADLE" --offline --no-daemon --console=plain runtimeSelfTest
"$GRADLE" --offline --no-daemon --console=plain build
```

- Red control: new callback-order regression with refresh forwarding to the old
  immediate publication and tick rolling reproduced a **7.53888-block** sub-tick
  projection discontinuity. Log: `logs/frame-motion-repair-red.log`.
- Corrected canonical `build`: **PASS**, including all five discovered
  `runtimeSelfTest` classes, build/packaging fixtures, remapped artifact and
  `verifyProductionJar`. Log: `logs/frame-motion-repair-build.log`.
- `ObserverFrameContinuityTest`: **103 checks**, covering shader refresh → actual
  predictor frame-advance helper → render fractions 0/.25/.5/.75/1, basis and
  translation revisions, repeated render refreshes, unchanged-mirror next tick,
  movement start/stop in deep and shallow modes, transitions, pending-state clear,
  deliberate large snaps and prior continuity regressions.
- The other discovered tests passed: embedding callback isolation, engine safety,
  persistence/network and pipeline. Persistence tests intentionally log rejected
  malformed profiles/vectors; those are not test failures.

## Remaining risks

No live Minecraft client/GPU or IntelliJ launch was performed. Rendering snapshots
now publish at the next predictor tick (at most one tick's normal publication
latency); real multiplayer frame pacing and visual pivot quality still need a
playtest. Existing API Guardian/mixin compile warnings and Gradle deprecation
warnings remain. Passing this scoped CPU regression/build does not establish that
entry, walking, source generation, teleport collision, terrain meshes, fog,
diagnostics or in-process native embeddings satisfy the full runtime request.
