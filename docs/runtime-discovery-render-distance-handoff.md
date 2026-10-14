# Retained runtime source-discovery repair

Scope: `feat-runtime-discovery-render-distance-discovery`, on top of retained
`bbd696d` / `4aa35f8` / `44b98cf`. This repair addresses source discovery/visible
coverage, retaining the earlier fixes for nearest-only frame starvation and
budget-evicted data still marked covered. The follow-up fixes sparse near-mask
replacement, generation-hint starvation and expiration of incomplete results.
It does not implement the ten independently assigned runtime repairs or change
embeddings.

## Behavior

- Discovery uses the smaller of the server view limit and this player's advertised
  client view distance. Source scheduling follows the player's shallow translation
  or deep affine inverse, still in independent per-player windows. The bounded
  maximum now covers the full vanilla 32-chunk slider (512 source blocks).
- The scheduler keeps the full tiled source box. Retention uses an expanded box,
  not a sphere that silently invalidated its own view-box corners. Reads retain
  their bounded issue/concurrency limits and use the real source-landmark lane.
- Observed equal opaque full-cube material layers batch into exact rectangular
  mesh unions. This is **mesh batching**, not a change to source-coordinate octree
  ownership/growth or persisted source data. Air, holes, partial shapes, differing
  owners, material properties, light and tint are not merged. Near sampled air and
  exact ownership refinement correctly split rectangular batches (including odd
  dimensions) instead of treating a slab as a cube. Near replacement uses an
  indexed mask of **actually sampled cells**, including air, rather than the
  bounding box of sparse patches: unknown gaps retain their far material,
  ownership provenance and mesh/collision geometry.
- The serialized source lane yields after a bounded discovery-read streak to
  both explicit ownership/landing requests and the separate chunk-load/frontier
  generation-hints queue. Those gated lanes alternate when both wait. Reads
  still bypass unavailable embedding/index readiness; hints are consumed and
  converted into real Ensure operations only when selected and ready. This
  bounds operation starts, not inference or publication completion time.
- Local frame admission preserves the old exact near allowance (1024 shallow /
  640 deep) plus the separate bounded 256-cell far lane. Projected spatial buckets
  round-robin far observations instead of admitting only the nearest 256 cells.
  Duplicates, degenerate geometry and exhausted palettes consume no admission.
- Source sizes and affine axis columns feed the same emitted mesh and collision
  frame. Rectangular keys include every extent; existing cubic keys remain stable.
  Server and client horizon culling now follow view distance instead of the old
  server 192 / client 64-block caps.
- Budget eviction removes scheduler coverage and queues real rediscovery with a
  stationary cooldown. Cumulative player movement (>16 source blocks), or a view
  change, promotes evicted tiles immediately. Expiration/cancellation remain
  bounded by the window lifecycle. Incomplete/empty results, failed requests and
  pending reads expire too, not only covered tiles. Expired pending futures are
  removed before cancellation, and completion removes a pending entry only when
  its future still matches; a stale callback cannot displace a fresh request for
  the same tile. Changes in retained data dirty compaction.

## Regressions and checks

`DiscoveryFrameSelfTest` exercises the production pure discovery, retention,
projection and spatial-admission helpers through actual `TerrainMeshFrame`s:

- Three observed ground layers through 10-chunk discovery -> retention -> shallow
  and deep frames, reaching all four 160-block distance edges. Actual SAT sweep
  checks establish collision at those distant visible surfaces.
- Dense unmergeable nearby ground cannot starve distant quadrants or observed
  cells at 32-chunk distance; ordering is independent of input iteration.
- Mesh batching preserves holes, material/owner boundaries and partial shapes;
  near replacement removes only sampled air, including inside odd-sized cubes.
  Deformed batch interiors participate in the real SAT collision path.
- Budget eviction followed by slow movement while still inside retention reissues
  the tile; freeing budget retains it; stationary cooldown avoids immediate
  thrashing. View-box corners stay covered and the effective player view is used.
- Widely separated sparse patches replace only sampled air/changed partial
  solids, preserving the floor and real SAT collision between patches. Dense,
  checkerboard and odd-sized masks across signed grid boundaries preserve every
  unsampled source cell exactly once and produce deterministic output.

`SourceLaneFairnessSelfTest` exercises sustained discovery plus simultaneous
explicit requests and generation hints, verifies bounded yields for both gated
lanes, lazy hint consumption, and readiness-independent reads.
`SourceDiscoverySelfTest` additionally covers 80 movements with incomplete empty
and partial results under zero-node retention, pending/failed expiry and fresh
requests when returning to expired source terrain.

The existing `SourceDiscoverySelfTest` still tests full schedules, region-volume
bounds, retries/cancellation, far-field conversion and near supersede. A bare JVM
cannot initialize the Minecraft block registry; hand-built **observed material
geometry** is used, and the registry limitation is printed, not hidden. No dummy
embedding vectors or external inference calls are part of these checks.

Canonical command (existing Gradle 8.13, Java 21; run via `tmux-jobs`):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain build
```

`build` runs discovered `runtimeSelfTest` suites, packaging/runner `buildSelfTest`,
common/client compilation, remapping and `verifyProductionJar`. It does not use
Gradle's disabled framework `Test` action as proof of tests.

At retained `c71d118`, the follow-up canonical build passed; a second build with `--rerun-tasks`
passed in 25 seconds (all 14 actionable tasks executed, no compilation or
packaging task reused). Both builds had all seven main-based runtime classes
discovered and run with assertions: `DiscoveryFrameSelfTest` (7,982 checks),
`SourceDiscoverySelfTest` (296,336), `SourceLaneFairnessSelfTest` (73), and the
four existing embedding callback/engine/persistence/pipeline suites. Packaging,
descriptor, metadata and runner regressions passed; `verifyProductionJar`
accepted `build/libs/mysticism-1.0-SNAPSHOT.jar`. Expected invalid-persistence
fixtures log rejection warnings/stack traces; they are not inference tests.

## Cancellation review follow-up

`cancelWindow` now snapshots discovery futures and clears `discoveryPending`
**before** cancelling the snapshot. Cancelling a `CompletableFuture` can invoke
its completion handler synchronously, and `MinecraftServer.execute` can execute
that handler inline on the server thread. The old mutable `values().forEach`
iteration could throw `ConcurrentModificationException` when handlers removed
pending entries, aborting the remaining window/session cleanup. Clearing first
also invalidates all completion identity guards before any cancellation callback.

The discovered `DiscoveryCancellationSelfTest` invokes the actual private window
teardown via reflection, without a Minecraft server or embedding engine. Its 21
checks cover both pending reads with synchronous handlers, complete cleanup of
retained nodes/near samples, deferred handlers racing a fresh same-tile request,
repeated teardown, empty maps and already-completed futures. It inspects callback
outcomes so swallowed callback assertion failures cannot be counted as success.

The canonical command with `--rerun-tasks build` passed in 27 seconds: all 14
actionable tasks executed, all **eight** runtime classes discovered and passed,
packaging/metadata/descriptor/runner fixtures passed, and the remapped production
jar verified. Retained build handle: origin `%2587`, pane `%2593`, run
`614017d4756b46c082f1bccb9ab6d48f`, exit 0. Existing API Guardian/mixin/deprecation
warnings remain nonfatal. This is bare-JVM teardown coverage, not a live
world-change/server-stop play test or native v2 inference verification.

## Limits / unverified

Discovery and mesh budgets are intentional: large views generate progressively,
not synchronously, and extremely varied terrain can exceed retention/admission
budgets; this is horizon representation, not a promise to mesh every source voxel
simultaneously. Retention still favors nearer tiles under node pressure, with
real retry/movement recovery for evictions. The source vertical radius remains
bounded at 64 blocks. Prism batching currently stretches the existing block
model texture, as cubic octree batching already did; no texture tiling redesign
is included. No live Minecraft play session, GPU rendering, chunk generation
latency, multi-player load or native v2 model inference was verified. Existing
embedding/runtime and independent projection contracts were left unchanged.
