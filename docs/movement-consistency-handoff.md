# Spirit movement consistency handoff

Piece: consistent input, acceleration, damping and diagonal movement in spirit modes,
without unstable speed corrections. Baseline: `bfbce90c4752e8f6ee821769eb9d51a43453a13b`.

## Problem addressed (part of runtime problem #1: movement jitter/rubber banding)

Spirit deep flight previously inherited vanilla 1.21.1 creative flight, which is directionally
inconsistent:

- Vertical key input accelerates at `3 * flySpeed` with `0.6` damping (PlayerEntity.travel flying
  branch overrides `velocity.y` with `prevY * 0.6`), while horizontal input accelerates at
  `flySpeed` (`2x` sprinting) with `0.91` airborne drag.
- The vertical input is added directly to velocity in `ClientPlayerEntity.tickMovement` and
  bypasses the `movementInputToVelocity` normalization that keeps horizontal diagonals at
  single-key speed, so mixed W+A+Space speeds differ from every other direction.
- Sprint toggles mid-flight double the horizontal acceleration instantly, so semantic travel
  (`TraversalSteering` integrates real per-tick deltas) sees direction-dependent rates.

## Implementation

- `src/main/java/io/github/mysticism/movement/SpiritMovement.java` — shared, deterministic model.
  Spirit deep flight (only `mysticism:spirit` world, only `abilities.flying`, only the
  movement-owning logical side, no vehicle/swimming) now uses: one acceleration rate
  `flySpeed * (sprinting ? 2 : 1)` for EVERY axis, the vanilla `movementInputToVelocity` rule
  (normalize past length 1) extended to the 3D input including the jump/sneak axis, identical
  `0.91` damping on every axis, and vanilla's tick structure preserved exactly: strip the vanilla
  pre-added vertical impulse (`direction * flySpeed * 3`), accelerate, `Entity.move` (so
  `MeshCollision`, mesh sneaking, collision zeroing and `onGround` behave exactly as in walking),
  then damp the POST-collision velocity. Velocity is never clamped, zeroed or corrected; the
  exponential model converges monotonically to one terminal speed (`a * 0.91 / 0.09`) in every
  direction, so prediction and semantic tracking never fight a correction. `onLanding()`,
  fall-flying flag 7 reset and `updateLimbs(false)` are replicated for vanilla parity.
- `src/main/java/io/github/mysticism/mixin/SpiritTravelMixin.java` — `@Mixin(PlayerEntity.class)`
  HEAD cancel of `travel` that delegates to `SpiritMovement.travel` and cancels only when the
  model handled the tick. Shallow walking, swimming, vehicles, other players on the server and all
  non-spirit worlds fall through to vanilla untouched.
- `src/main/java/io/github/mysticism/mixin/EntityFlagAccess.java` — `@Invoker("setFlag")` on
  `Entity` for the vanilla parity flag reset; registered in `mysticism.mixins.json`.
- `src/client/java/io/github/mysticism/client/MysticismClient.java` — installs the vertical input
  provider: the same `input.jumping`/`input.sneaking` key state vanilla read, under the same
  camera condition (`getCameraEntity() == player`), so the strip is exact. Default provider is 0
  (deterministic headless/dedicated behavior).
- `src/runtimeTest/java/io/github/mysticism/movement/SpiritMovementTest.java` — 1274 assertions:
  unit/bounded digital input (diagonal never faster than straight), vanilla yaw convention,
  direction-independent terminal speed (walk + sprint, pure/diagonal/vertical/3D-mixed directions),
  monotone convergence without overshoot or oscillation from above AND below the terminal speed,
  stable hover decay, axis-symmetric damping, continuous mode-transition momentum, exact vanilla
  vertical-impulse strip, uniform sprint scaling, travel-gate truth table, and mixin
  registration/config contract.

## Validation actually performed

- `./gradlew --no-daemon --console=plain build` green on baseline `bfbce90` and on this branch
  (includes `buildSelfTest`, `runtimeSelfTest` — now 5 suites — and `verifyProductionJar`).
- `runClient` booted on this worktree with the mixins registered (`required=true`: any bad target
  crashes boot); mod entrypoints, resource reload and spirit postprocess all initialized, no mixin
  errors in `run/logs/latest.log`. Game was then stopped.

## Not exercised / limits (honest)

- No in-game session was played: /spirit entry, actual flight feel, mesh collision interplay and
  multiplayer prediction were NOT exercised by hand. The model is deterministic and unit-tested;
  the integration points were verified by boot and by bytecode-level target checks
  (single `getFlySpeed()` call site in `ClientPlayerEntity`, `PlayerEntity.travel` override,
  vertical add at offset ~1071 before the `super.tickMovement()` travel call at ~1290).
- Vertical flight speed is now equal to horizontal (vanilla was ~2.2x slower per axis). This is
  the deliberate consistency change; landing convergence code reads position deltas, not
  velocities, and `TraversalSteering` ignores >4 blocks/tick (max here ~1.0 sprint, 0.5 walk).
- The brief overlap while a client is still flying but the server already flipped to shallow
  (one round trip) uses flight drag 0.91 instead of vanilla's grounded `slip*0.91`; harmless and
  client-authoritative.
- Known collision-domain jitter sources (client/server mesh frame divergence, `MeshCollision.move`
  depenetration, step interplay inside `Entity.move`) belong to the terrain/collision piece and
  were NOT changed here.
