# Walk/flight accepted-movement repair

Scope: `feat-runtime-movement-walk-flight-handoff`, for integration into `dev`.
This extends, rather than replaces, the existing bounded handoff and owner-velocity
packet delivery commits. Projection, source ownership, mesh acquisition, and
embedding contracts are unchanged.

## Runtime path

Ordinary `onPlayerMove` packets update the server's accepted player position, not
its horizontal entity velocity. Using only `ServerPlayerEntity.getVelocity()` at
walk-to-flight entry could therefore send zero to a moving controlling client.

The existing registered `SpiritFlightToggleMixin` now snapshots the carrier pose
**after** `NetworkThreadUtils.forceMainThread`, then records displacement only at
vanilla's successful final return. Invalid movement, pending teleports, vehicle
movement, and collision rejections return earlier and cannot contribute a raw
requested destination or temporary collision pose. The mapped handler bytecode
is checked by an offline regression to protect these injection assumptions.

Each navigation session accumulates accepted displacement within one server tick.
A handoff uses a sample from the current or immediately previous tick (abilities
packets can precede the next move packet), falling back to server velocity when
there is no recent accepted movement. Multiple same-tick packets aggregate;
rotation-only packets do not erase that tick's movement. A subsequent accepted
stationary tick does override old server momentum. The existing magnitude bound,
grounded walk stop, self-velocity delivery, and navigation-mode handoff gate are
retained. Handoffs never move the carrier or replace its semantic position/basis.

Teleports/corrections, completed handoffs, and expired walk-intent stops invalidate
old samples. Dimension changes, respawns, disconnects, and server shutdown discard
them with the existing session lifecycle. No new external dependency or service.

## Regressions and validation

`AcceptedPlayerMovementTest` exercises the actual movement accumulator and handoff
policy together with vanilla's owner-velocity packet, including different server
and controlling-client velocities, walking/sprinting/jump/edge-fall momentum,
packet ordering/aggregation, stops, stale samples, teleports, non-finite movement,
bounds, and per-player independence. `WalkFlightHandoffTest` also checks the real
service/mixin wiring; its existing policy and delivery checks remain enabled.

Canonical suite: `./gradlew --offline --no-daemon --console=plain build` (includes
all discovered `runtimeSelfTest` classes, `buildSelfTest`, common/client compilation,
remapping, and `verifyProductionJar`). Run visibly in the `walk-flight-repair`
window of the `Mysticism` tmux session; log: `/tmp/myst-walk-flight-repair-build.log`.
Result: **BUILD SUCCESSFUL**, exit 0; all six discovered runtime test classes
passed, including `AcceptedPlayerMovementTest` (47 checks) and the retained
`WalkFlightHandoffTest` (764 checks). Packaging/runner fixtures and production-jar
verification passed. Embedding persistence warnings are expected invalid-input
fixtures; ordinary Java/Gradle deprecation warnings remain.

Limit: accepted displacement is a recent authoritative movement estimate, not a
new client velocity protocol. Under network batching/latency it may differ from
the client's newest post-input/friction velocity, and all owner-velocity packets
have vanilla's 1/8000 wire quantization. A live client/server double-jump, sprint,
edge-fall, grounded acquisition, and expiry smoke test is still needed; offline
regressions do not prove mixin application or in-game visual smoothness. This
scoped repair does not claim live validation of all eleven broader runtime issues.
