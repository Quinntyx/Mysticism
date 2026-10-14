# Retained runtime merge: shallow horizon and elapsed walk expiry

Scope: `feat-runtime-merge-0016`, for integration into **dev**, not main. The
existing `3b22084` merge and both movement/entry-recovery parents remain intact.
This addresses scoped review P1/P2; it is not a claim to have playtested all
eleven issues or restored/verified a native embedding model runtime.

## Real acquisition, not just publication

Normal shallow entry previously had only its live near patch and initial source
query. Other selected regions could not supply the source-identical shallow
horizon. `ShallowSourceDiscovery` now acquires independently of semantic
selection: bounded source-coordinate catalog batches, bounded repository page
reads/octree traversal, and outward native generated-only region reads. The
production terrain tick advances one observer round-robin per tick. Repository
cursors yield at each page boundary; contention defers work rather than holding
up near/ownership reads indefinitely.

Acquisition and mesh publication now use the observer's client view-distance
setting (vanilla's 2–32 chunks), not a fixed 128-block visibility clamp. Generated
queries use a constant-memory 3D outward cursor with at most 128 skipped bounds
per turn and one 16-cube (<=4096 cells) request in flight per observer. Supported
entry starts in the floor's vertical patch. Ordinary recentering preserves
outward progress; a teleport or distance change restarts near-first. There is no
radius-cubed query queue. Native observations are read without forced generation;
unknown source space stays unknown.

The 4096-node discovery reservoir uses spatial coverage admission, rather than a
nearest-only prefix. The existing live near samples/negative coverage override
this reservoir before publication, preserving actual body detail. Both generated
AIR and SOLID observations mask older persisted leaves; periodic catalog reads
cannot resurrect an observed AIR block. Masks retain only still-in-range source
buckets on recenter. Fully observed 16-cubes now use one bit each in bounded
256-cube groups (at most 216 groups / 108 KiB of bits for the maximum 512-block
horizon). Fragmented/UNKNOWN observations retain the separate 8192-bucket bound.
An unrepresentable fragmented patch is deferred atomically; it does not stop the
outward cursor, later complete observations, or periodic sweeps. Promotion to a
complete observation reclaims its fragmented-mask slot. No UNKNOWN cell is masked
to make room. Persisted subtraction queries these exact masks without copying the
whole horizon's bucket map on each read. Mode changes, acquisitions and session cleanup
cancel pending work. IO retry keeps the previously acquired coherent reservoir.

Geometry uses the same source-identical carrier mapping, frame publication,
collision SAT and source-ownership validation as before. Observed unknown-owner
geometry grants no new landing/exit/ownership permission; no landmark or source
block is written by this discovery path. Deep selection, independent player
projection and native v2 embedding persistence are unchanged.

## Walk intent expiry

A request records its server start tick once. `attemptSupport` checks 1200 elapsed
ticks **before** support lookup and readiness/retry early returns. Intermittent
contact cannot extend the lifetime, and repeated packets do not renew it. Expiry
uses the retained stable-hover/owner-velocity delivery and approach cleanup path,
leaving a usable deep pose with a fresh request possible. The contact-only counter
was removed; the separate missing-support stall budget remains.

## Immediate post-acquisition jumping

Both successful acquisition paths (`attemptSupport` and targeted `attemptLanding`)
now seed `supportY` from the validated current carrier feet, and reset the
unsupported counter before handing off to walking. The previous floor's landing
envelope can no longer turn a jump packet arriving before the next grounded
navigation tick straight back into flight. Pose, velocity, projection and source
ownership validation are unchanged.

## Original retained checks

Using installed Gradle 8.13 / Java 21, offline, with no exclusions or diagnostic
classpath, canonical command:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build
```

Final result: **BUILD SUCCESSFUL**, exit 0. The retained tmux-jobs handle is
session `$11`, window `@1383`, pane `%2167`, run
`7c5e32387b774281a3a03ee4c2ea04fd`, original pane `%2118`.

- All **20** discovered current runtime main classes passed. New
  `ShallowSourceDiscoveryTest`: **1649** checks, exercising the production
  acquisition state machine, real octrees, compaction/publication and collision
  SAT with fixture repository/world IO. Covers different source landmarks at
  +/-120 blocks, vertical coverage, a 240-block horizon at 16-chunk view distance,
  generated-only horizon input, bounded reads/retention, cancellation, moving
  acquisition progress, retry retention and AIR masking. Source guards also
  check the production service's acquisition/publication wiring.
- `WalkRequestExpiryHoverTest`: **2429** checks, including intermittent contact
  every 199 ticks, skipped tick expiry, fresh request budget and service guard
  ordering. The movement, jump/landing, walk/flight, frame continuity, reconnect
  and entry-failure regressions remain discovered and passing.
- `buildSelfTest` packaging/descriptor/decoded-metadata/runner fixtures and
  `verifyProductionJar` passed; common/client compilation and remapping passed.
- `git diff --check` passed. Earlier iterations exposed an insufficient test
  acquisition window and two empty-reservoir expectations that incorrectly
  rejected newly acquired, in-range floor samples; corrected final checks test
  the actual out-of-range exclusion instead.

## Limits / remaining validation

This is progressive bounded coverage, not unlimited complete resident geometry.
The existing wire budget distributes real visible/collidable cells across the
footprint; it does not synthesize solid terrain between them. A large cold
catalog, shared-reader contention, source IO, high render distance or many players
can delay acquisition. Fragmented-mask exhaustion can defer individual incomplete
patches, but does not pause later complete generated acquisition or periodic
sweeps. There is no guarantee of sampling an entire large source volume
immediately. Multiplayer pacing and horizon quality need a live playtest at
realistic view distances.

No Minecraft client/server, GPU, IntelliJ launch, or model inference was run.
Fixtures do not prove live Fabric callback/mixin behavior or real source IO
latency. Expected embedding corruption fixtures log warnings; existing
annotation/mixin/Gradle deprecation warnings remain. No embedding provider,
profile, migration, dimensionality or dependency was changed in this repair.

## Scoped review repair verification (2026-10-14)

The same canonical offline Gradle `build` above passed with exit 0, without test
exclusions, classpath overrides, dependency installation or model downloads.
Retained tmux-jobs handle: original `%2573`, session `$11`, window `@1582`, pane
`%2600`, run `30601063928441edb6698b16ed8d0188`.

- All **21** discovered runtime main classes passed (17 runtime sources plus four
  embedding suites; the standard framework `test` action is intentionally skipped).
- `ShallowSourceDiscoveryTest`: **15031** checks. The new stationary 32-chunk
  regression consumes full generated 4096-cell regions, with no persisted
  geometry, crosses the old 8192-bucket exhaustion boundary, and still acquires
  the final vertical layer's positive/negative horizon. The bounded reservoir
  then supplies real mesh publication and matching SAT collision there. It does
  not substitute a sparse one-cell-per-request fixture for full observations.
- `ShallowObservationCoverageTest`: **36877** checks. Fragmented exhaustion,
  later full admission, fragmented-slot reclamation, clipped-patch union,
  negative coordinates, recenter/teleport pruning, exact AIR/SOLID subtraction
  and preservation of UNKNOWN persisted cells all pass.
- `ShallowJumpLandingGraceTest`: **58** checks. Invokes the actual production
  acquisition-session mutation with stale higher-floor state, then simulates an
  immediate vanilla jump/landing at three different acquired heights. Source
  guards pin that mutation before the walk handoff in both success paths.
- Existing entry recovery, walking correction, jump/landing, handoff, shallow
  locality, frame continuity, reconnect and expiry regressions remain discovered
  and passing. `buildSelfTest` and `verifyProductionJar` passed, as did common /
  client compilation and remapping. `git diff --check` passed.

An initial build failed because the new horizon assertion demanded one exact
sample at the edge of a spatial admission bin. The regression now checks the
outer retention/publication bands appropriate to the existing bounded spatial
admission, plus actual collision, rather than an arbitrary individual sample.
The final canonical build above passed. Expected embedding-corruption fixture
warnings and existing Gradle deprecations remain. No live Minecraft, GPU,
IntelliJ launch or real model inference was performed; embedding providers,
native-v2 dimensionality, persistence and dependencies were not changed.
