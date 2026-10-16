# Runtime grounded movement / crouch

Scope: `feat-runtime-movement-grounded-crouch`, based on retained `bfbce90`.
The previous session timed out during investigation; the retained checkout was clean.
This unit changes movement/support, not the other runtime units or embedding backends.

## Behavior

- `MeshCollision.Index` exposes the production movement and sneak kernels for deterministic runtime tests. Server and client wrappers still use their own observer's immutable mesh frame.
- Grounding and step eligibility now use actual upward affine mesh contact instead of the previous tick's vanilla `onGround`. Support searches each visible collision shape, so an unrelated wall overlap cannot mask a floor. Empty frames, hidden cells, ceilings, full-cell AABB corners and collisionless models do not provide support.
- Crouching keeps bounded axis/diagonal backoff but tests collision-resolved footprints. This allows genuine uphill mesh walking and reachable steps instead of treating rising faces as penetration/ledges. Drop reach respects step height; gravity cannot extend it. Upward jumps and unsupported airborne movement are not clipped.
- A grounded, nonascending upright walker resolves floor contact vertically, preserving requested X/Z. Gravity no longer projects sideways down a slope or undoes a crouch edge limit. Walls, airborne movement, upward jumps and free flight retain ordinary tangential sliding.
- Ordinary and stepped paths share the depenetrated start body, and both retain its correction.
- Existing `.025` support lift, `.125` ground reach and `.3` walkable-normal threshold are preserved. `ground()` hit times remain compatible with the existing `.15` source-contact sweep. No source ownership, projection, rendering geometry, cache profile or embedding changes.

## Verification

Canonical command (cached Gradle 8.13 / Java 21):

```sh
./gradlew --no-daemon --console=plain build
```

Passed with the existing main-based runner discovering **five** runtime classes:

- `MeshGroundedMovementTest`: **1282 checks**, including 400 ticks of stationary slope contact, 100 ticks of flat ledge pressure, uphill crouch traversal and 80 ticks of sloped ledge pressure; flat/affine/slab contact, independent frames, missing/hidden surfaces, negative/diagonal edges, jump/airborne/flight behavior, true steps/walls and depenetration preservation.
- `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
- `AsyncEmbeddingEngineSafetyTest`: 184 checks.
- `EmbeddingPersistenceTest`: 90 assertions.
- `EmbeddingPipelineTest`: 135 assertions.

`buildSelfTest` passed metadata, 14 descriptor rejection cases, 19 packaging rejection cases and runner discovery/order/assertion/failure checks. `verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` passed. Framework `test` is intentionally disabled; runtime discovery is through `runtimeSelfTest`, with this suite in `src/runtimeTest/java` (not the historical prototype source set).

## Limits / follow-up

No Minecraft client/server gameplay launch, multiplayer packet/frame-delay test, model inference, or performance profile was performed. Crouch footprint probes now run bounded collision resolution in addition to support queries; profile with dense meshes in-game. Cross-frame motion and client/server prediction reconciliation remain separate runtime units. Existing API Guardian/deprecation warnings and expected negative-fixture persistence warning stack traces do not fail the build.
