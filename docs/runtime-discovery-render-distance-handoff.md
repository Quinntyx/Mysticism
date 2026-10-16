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

## Completion-path review repair (retained merge follow-up)

The review correctly identified that the earlier bounded reader ended in an
unbounded **server-thread** completion handler. A whole 32³ region still went
through registry/block-state resolution, shape extraction, octree compaction,
prism coalescing and sorting there; retention then rebuilt/sorted up to 65,536
nodes. Scheduler-only latency tests did not cover that workload.

Production `issueDiscovery` now only registers the real region future and keeps
its admission slot through mesh publication. `Context.advanceDiscovery` polls
those same per-window identities; completion never invokes mesh construction.
The shared per-server budget is at most 256 examined cells and a one-millisecond
materialization deadline per tick, with at most 64 cells per window turn. Air and
duplicate samples count too. The starting window rotates so one player's horizon
cannot consume every tick. Registry resolution and collision-shape extraction stay
on the owning server thread, under that incremental budget. This is cooperative:
a single modded shape callback cannot be preempted mid-call.

A dedicated single-thread daemon with a two-task queue performs detached
`farNodes` compaction/coalescing and `retention` merging/sorting. It receives only
completed materialized maps, immutable tile geometry and source-coordinate
metadata, never world/registry access or the live coverage scheduler. There are at
most two submissions per server tick and one pending result per window. Rejection
retains progress for a later tick; there is no synchronous fallback or waiting.
The two source-read admission slots now also bound completed-but-unmaterialized
regions. Retention decisions return as immutable nodes, tile references and
explicit evictions; only the owner applies coverage/retry changes. No 65,536-node
retention rebuild or sort runs in the completion/poll path.

Window teardown/expiry invalidates request identities and worker results before
cancellation; late work cannot restore expired data or replace a fresh same-tile
request. Replans invalidate detached retention snapshots, while retaining completed
tile compaction so registry work is not repeated. Unchanged stationary replans do
not continuously invalidate a delayed result. Materialization failures preserve
prior valid terrain and request real retry rather than granting false coverage.

`DiscoveryMeshCompletionSelfTest` drives the **actual private Context/Window
completion path**, not a second scheduler. It feeds all 32,768 observed source
cells and two existing 32,768-node tiles, then runs real detached compaction and
98,304 -> 65,536-node retention on separate worker threads. A bare-JVM adapter
substitutes only registry/shape resolution with immutable material geometry and
asserts its owner-thread affinity. The test checks per-window and shared eight-
player budgets, elapsed-time stopping, budgeted air scans, held/saturated worker
progress, no inline fallback/publication, material/extent preservation, real
coverage eviction/retry, partial observations, stale-replan caching, stationary
replans, materialization failure and actual window teardown with fresh same-tile
replacement. This does not measure Minecraft movement time or real registry cost.

Final validation: cached Gradle 8.13 / Java 21
`--offline --no-daemon --console=plain --rerun-tasks build` passed in 2m 5s;
all 14 actionable tasks executed. The runner discovered and passed all **14**
main-based runtime classes, including the new completion-path test (36,124 checks
on this run; count includes per-tick assertions). Packaging/metadata/descriptor/
runner tests and `verifyProductionJar` passed. The retained build handle is session
`$11`, original pane `%2904`, build pane `%2928`, run
`b3b53fce0916427780635157ae0de40b`, exit 0. Existing annotation/deprecation and
intentional invalid-persistence fixture warnings remain. `git diff --check` passed.
The earlier full build also passed, before the stationary-replan guard was added.
No software was installed, no external embedding endpoint was used, and no live
Minecraft/GPU/multiplayer or native-v2 model inference was verified.
No projection, ownership/growth or embedding policy is changed by this repair.
Near-field refinement, frame candidate selection, mesh/collision stitching and
transport remain their existing server-thread paths; this fix does not claim a
hard deadline for the whole terrain tick or prove real-world FPS. Source views
remain progressive and the extra materialization budget can delay full coverage.

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
