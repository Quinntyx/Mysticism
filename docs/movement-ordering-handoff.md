# Movement-ordering repair (P1)

Scope: prevent delayed/reordered authoritative spirit poses from undoing local prediction.
Target: `feat-runtime-movement-movement-ordering`, integrating toward dev, not main.
This is not a claim that the other ten runtime problem areas are repaired by this leaf.

## Behavior

`ClientPoseSync` retains independent bounded position/basis prediction trails at the
existing post-integration predictor tick boundary. A fresh wire-sequence snapshot
on the pending trail is movement lag even after backtracking; direct distance to
the last received pose alone cannot classify it as a correction. Intermediate
segment snapshots acknowledge only their reached prefix. Earliest matching
segments preserve pending movement across loops and repeated poses. Stationary
ticks do not consume history capacity, and stored native v2 coordinates are copies.

The existing wire-sequence rejection and direct-distance fallback remain intact.
Off-path corrections outside that fallback still apply and reset their component's
trail. Teleports, epoch changes, identity changes, prediction breaks and disconnects
invalidate relevant history; peer components are not filtered as local prediction.
Position acknowledgment/correction does not discard pending basis movement.
No projection, octree, mesh, embedding contract, or wire-format changes.

## Validation

The reported `0 -> +4 -> +1` fresh delayed snapshot regression failed against the
previous production guard, then passed with this repair. Tests use real component
sync packets and the installed client guard, not a replacement reconciliation stub.
Additional coverage includes later return snapshots, reordered duplicates, basis
reversals, loops, intermediate snapshots, in-place vector mutation, 300 stationary
ticks, component independence, off-path corrections in the last native coordinate,
correction-history retirement, prediction breaks, teleports and epoch changes.

Canonical suite/build (existing Gradle 8.13 distribution, no installs/services):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build
```

Passed: five discovered runtime main classes (including 50 pose-ordering checks),
build-system/runner/packaging self-tests, and production jar verification.
Final build log: `/tmp/movement-order-final.log`; red reproduction:
`/tmp/movement-order-red.log`. Commands ran in the visible tmux pane `%1311`.

## Remaining risks

No interactive Minecraft/network-latency smoke test was performed. History is
bounded to 256 distinct endpoints per component (about 3 MiB total at native v2
size); extreme unacknowledged movement can evict old poses, leaving only the direct
fallback. Without movement acknowledgments/correction provenance in the protocol,
an unmarked authoritative correction exactly overlapping the pending trail is
indistinguishable from delayed movement. Position/basis trajectory matching still
uses the existing tolerances; substantial client/server integration divergence
outside the trail and fallback remains an authoritative correction.
