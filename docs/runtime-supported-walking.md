# Retained supported-entry / walk-intent repair

Scope: `feat-runtime-merge-0005`, integrating retained supported-entry and
walk-intent-lifetime work. This is not completion of the other nine runtime units.

## Behavior

- Entry keeps the real source-identical carrier feet and begins shallow with flight
  off. While source discovery or ownership publishes, grace requires contact with
  the actual visible collision mesh, never zero motion or a stale vanilla `onGround`
  flag. Grace is
  bounded to 100 supported waiting ticks and cannot be regained after a confirmed
  support is lost. A current empty ownership snapshot does not end that hold while
  `ensureSourceLocation` remains in flight; it is not a final source-discovery result.
  Completed-empty/failed/cancelled discovery with settled ownership does end the
  hold, and a never-completing discovery still cannot exceed the entry budget.
  Unsupported/off-edge entry still permits ordinary free flight.
- Entry waiting and the ordinary 14-tick jump grace have separate counters. A jump
  after waiting on ownership gets its normal budget; landing on the pending real
  floor does not replenish the entry lifetime.
- A flight-off request while deep remains flying until guarded acquisition really
  succeeds. Late semantic anchoring gets 1200 ticks; absent support gets 200
  consecutive stall ticks; real in-flight ownership/proof work for the current
  mesh contact does not spend that stall budget. Every request has a 2400-tick
  absolute bound. Bounds are simulation ticks, not wall-clock promises.
- Expiry reports the actual missing/unresolved phase, clears server and synced
  prediction flags, and preserves prepared terrain proof for another request.
  Duplicate pending packets do not reset budgets. Explicit cancellation, cleanup,
  and source-world unload release retained proof even after an intent has expired.
  `/spirit deep` explicitly cancels the approach/proof; another flight-off request
  can start fresh afterward. Failure is not permission to invent a landing.
- Retry still rechecks current contact, producer/window identity, authoritative
  ownership, source body/floor clearance, source-grid alignment, and frame
  continuity. No target attraction, q/basis/body snap, fabricated floor, global
  observer frame, or embedding/profile/migration change was added.

## Regression discovery and limitations

The existing `runtimeSelfTest` runner discovers the three navigation mains under
`src/runtimeTest/java/io/github/mysticism/navigation`, the terrain discovery-order
regression, and all four configured embedding suites. No framework dependencies
or suite-selection overrides were added.

`SpiritEntrySupportedWalkingTest` exercises the actual pure stance decision and
checks its production wiring. `WalkIntentLifetimeTest` exercises the production
lifetime state machine, including slow completion, terminal expiry and a fresh
retry. `SupportedWalkingIntegrationTest` runs the actual `MeshCollision.Index`
sweep with integer floors, fractional slab heights and negative coordinates,
plus airborne/off-edge/hidden-geometry cases. `EntrySupportDiscoveryTest` completes
the real resumable ownership request against an empty attached store, then drives
that current-empty snapshot and an unfinished discovery future through the
production pending-state combiner and actual mesh contact into the stance decision.
It covers terminal failure/cancellation, ownership refresh, and the entry grace bound.
The player-adapter and navigation/terrain lifecycle wiring checks inspect production
source; they do not execute Fabric callbacks or a live source-discovery operation.
Ignoring the discovery future in the production combiner was temporarily tested:
the discovered suite failed on the completed-empty-ownership ordering assertion;
the fix was restored before canonical verification.

Canonical verification command (installed Gradle 8.13, Java 21):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

`build` runs every discovered runtime main, `buildSelfTest` packaging/discovery
fixtures, compilation/resource processing, `remapJar`, and `verifyProductionJar`.
The intentionally disabled framework `test` task is not a passing test suite.

No Minecraft/client/server gameplay, source-future load timing under a live server,
GPU/network behavior, or real model inference was verified by these regressions.
The existing embedding backend is outside this unit's ownership; these checks do
not prove the separate in-process-model requirement or make it a new external
server prerequisite. Native v2 dimensions and fresh-profile semantics are unchanged.
