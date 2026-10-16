# SOURCE rebuild handoff

Authority: `spirit-world-approved-design.md`; SOURCE owns landmark/activity except TraversalSteering. This replaces the cube-journal extraction controller. Source blocks, inventories and player statistics are untouched.

## Parent hooks / public APIs

Keep `LandmarkExtractionService.init()` + `SpiritActivityService.init()`. The extraction initializer delegates to idempotent `SourceLandmarks.init()`; registering SourceLandmarks directly is also supported, but do not initialize the retired journal controller. Keep the existing source-edit, activity, and player-action mixin registrations; no new mixin/config resource is introduced in this pass. Existing `topologyAdapter`, `prepareTopology`, `COMMITTED_TOPOLOGY`, `LandmarkProfiles.current()/wrap`, activity `effectiveEmbedding(server, metadata)` / `importance(server, metadata)` remain. Without an installed topology adapter the real activity merge/split/remove adapter is used.

```java
// io.github.mysticism.landmark.SourceLandmarks
public static void init();
public static CompletableFuture<Optional<LandmarkMetadata>> ensureSourceLocation(
    MinecraftServer server, String dimension, BlockPos position);
public static CompletableFuture<Optional<LandmarkMetadata>> refine(
    MinecraftServer server, String dimension, BlockPos position);
public static CompletableFuture<Region> region(
    MinecraftServer server, String dimension, Bounds sourceBounds, int maxCells);
public static CompletableFuture<Region> discoveryRegion(
    MinecraftServer server, String dimension, Bounds sourceBounds, int maxCells);
public static CompletableFuture<Optional<BlockPos>> safeAir(
    MinecraftServer server, String dimension, BlockPos position, int radius);
public static CompletableFuture<Boolean> transfer(
    MinecraftServer server, String receiverId, String donorId, Bounds cells);
public record Cell(BlockPoint position, BlockPalette.State material, String biome, boolean sky);
public record Region(String dimension, Bounds bounds, List<Cell> cells, boolean complete);
```

Call on server thread. Normal completions return on server thread; shutdown/unload cancel outstanding work. Caller cancellation cancels staged work. Missing/generated-absent cells are UNKNOWN, omitted from `Region.cells`, and set `complete=false`. `safeAir` radius 0..8 is conservative and explicit; NEVER silently substitute it for an invalid captured navigation destination. Preserve captured vectors/source coordinates: none of these APIs re-attunes or teleports a player.

`SourceSelector.begin(server, embedding, radius, frameGeneration, sourceRange)` returns an owner-thread resumable geometry request. `advance(metadataBudget, pageBudget, leafBudget, maxSamples)` bounds are 1..16 / 1..4 / 1..512 / 1..512. Batches contain source-cell bounds/material/occupancy, source ID/revision, effective compatible embedding and importance. `cancel()`, `complete()`, `generation()` are available. This is a **raw semantic admission/geometry stream**, not a second representative policy: consumers must apply the existing `RepresentativeSelector` to detached headers and recheck revision/frame before mesh publication. Radius admission uses effective activity embeddings, not base-only nearest-K. No complete-catalog hydration or 256-record cutoff.

`RepresentativeSelector` retains radius/fog gates, density clustering, weighted moments, semantic reconstruction versus projected geometric distortion, coverage seeding, quotas and hysteresis. Adds finite inverse-square priority and a mandatory deterministic nearest target inside 5% radius, overriding a stale cluster/pin lock. Returned representative importance remains bounded (priority is separate).

## Implemented production behavior

- Source-position seeded, stable-ID exclusive 3D masks; chunks/8-block pages are IO/storage only, not feature identity. Pure ownership/flood/material/topology processing runs on a bounded worker.
- Initial connected cave air crosses chunk IO boundaries and stops at exact biome keys. Solid walls cannot connect independent air cavities. Sky escape becomes an open surface feature, never an enclosed cave. Unknown neighbor proofs persist as frontiers; discovery progresses from frontier positions, with bounded chunk-reload retries.
- Surface skin/open air and mountains use actual sampled blocks, sky transitions and elevation/relief. Surface ownership does not indiscriminately swallow underground caves. High-importance geometry refines to smaller octree cells; the nearby source neighborhood stays exact. Mutable palettes/revisions retain the source anchor.
- Generated unloaded chunks use vanilla asynchronous read-only `scanChunk` (including queued saves), bounded selective NBT collection and background palette/biome/heightmap decoding. No tickets, chunk generation, source writes, world/registry access on worker or model inference on tick.
- Actual Nomic biome/structure/palette descriptors are submitted asynchronously; weighted compatible item-index vectors are the fallback, never random/fake vectors. Existing profiles are preserved through LandmarkProfiles.
- Real adjacent unowned growth; exclusive contiguous source-mask transfer; converged physically adjoining merges through repository plans. Initial biome boundaries do not veto activity merges. Stronger activity can attempt a one-cell steal when a neighbor has not converged. Importance dampens changes and decays with a 35,040,000-tick half-life; chest contents join existing dwell/held/inventory/place/break/use/death/kill/spawn signals. Persistent influence state and merge lineage use existing stores.
- Disposable old source-generation/catalogue and derived influences are discarded together. Current native source saves retain records, aliases and revisions. Player stats/inventory/source chunks are never cleared.
- Lifecycle cancellation: player activity disconnect/respawn/dimension changes; source chunk unload/edit invalidates an active snapshot; dimension unload/server stop cancel reads, worker jobs, pending mutations and release activity pauses.

## Exact region ownership hook for TERRAIN (follow-up to terrain 87510e0)

```java
public static CompletableFuture<SourceOwnership.Region> SourceLandmarks.owners(
    MinecraftServer server, String dimension, Bounds sourceBounds, int maxCells);
// SourceOwnership.Region:
String dimension(); Bounds bounds(); long ownershipRevision();
Map<BlockPos,String> owners(); Optional<String> ownerAt(BlockPos position);
boolean isCurrent(MinecraftServer server);
```

Call on server thread after normal source init. Result is an immutable map of exact native source blocks to canonical source IDs. Both observed AIR and SOLID octree leaves count; UNKNOWN/unowned blocks have **no entry**. AABBs only select candidate/page IO, never assign ownership. Full requested volume must fit `maxCells <=32768`; no partial/truncated success. Conflict between different source masks fails rather than guessing. No source-world access, chunk loads, inference, or readiness dependency. Completed maps are detached immutable snapshots; `isCurrent(server)` checks same store instance and a runtime source-topology stamp. Creation, growth, transfer, merge, split, delete or reset invalidates old maps; activity-only CAS does not. This adds no saved schema/migration.

One ownership request advances/server tick alongside extraction: <=4 metadata records **or** <=1 geometry page with <=512 reconstructed leaves **or** <=256 octree node visits/128 staged leaves **or** <=256 block-map insertions. Queue <=8 including the active request; at most one retained page and 128 staged leaf ranges. Cancellation is polled on tick; dimension unload/server stop cancel queued/active reads and release the ownership cursor. Ownership proofs have one dedicated store cursor alongside (not instead of) the exclusive discovery cursor; see the ownership latency repair below. A concurrent source publication fails the request as stale; consumer re-requests. Cold NBT IO remains synchronous and page-bounded, not a hard wall-clock guarantee. Entire catalogue traversal is resumable; a large catalogue or queued ownership requests can still delay completion.

**Terrain consumption required (not edited in SOURCE worktree):** request this mask for the exact local/target sliding source bounds in parallel with `region(...)`, retaining/cancelling the future with the Window/session. Check `mask.isCurrent(server)` and matching dimension/bounds before publishing labels or granting support/exit. Label each unit source sample from `mask.ownerAt(pos)`, never from `Window.id`; absent means empty/unowned, not retained-owner permission. Compaction must not merge different owner IDs or owned with unowned voxels: split nonuniform ownership down to unit cells. `sourcePosition` must verify actual mapped foot/body sample ownership against the resolved retained ID; support must use the actual floor-contact cell's map owner, and exit must fail while ownership is absent/stale/outside the queried region. Replace the AABB-only authority check at SpiritTerrainService.sourcePosition (terrain 87510e0 lines 136–141). Do not retune/re-anchor from a changed owner. Current authoritative map is usable only within its requested bounds; refresh after walking outside or topology invalidation.

No tests/build run for this bounded hook, per parent instruction. Terrain wiring remains TERRAIN-owned; this SOURCE commit supplies the callable API, not a claim that terrain has already consumed it.

## Review correction: source/activity reconciliation

`SpiritActivityService.prepareSourceUpdate(server, before, after)` returns the existing `LandmarkExtractionService.TopologyPlan`. SourceLandmarks acquires the activity mutation pause before computing the importance-limited source update; prepares the overlay correction before `stagePut`; commits it immediately after the core source publication and before completing the request; cancels it on failure/unload/shutdown. It applies `effective + (newBase - oldBase)` and normalizes, preserving the prior activity residual, importance mass/timestamps, owners and immutable claims. No-change source vectors do not repeatedly erase personality history. Revision/overlay identity checks reject stale publication. This closes the reviewed bug where an existing persisted activity vector permanently masked source changes. No additional tests or Gradle were run for this correction.

## Bounds / honest remaining limits

One advancing source operation/server; separate read/requested/background lanes (64 pending reads/requested ops each), hint queue 128, frontier-retry queue 64, worker queue 16. A suspended resource owner is counted in its discovery lane's pending budget. Live reads <=512 cells and stop after ~1.5ms between cell samples; this is **not** a hard whole-tick deadline for metadata/NBT page IO/publication. Region volume <=32768 and <=16 chunk IO adapters; local overlap operation <=64 parents, <=512 hydrated pages/parent, expanded topology <=131072 cells. These are per-operation limits, not a global catalog cap. Oversized operations fail visibly through futures/status rather than fabricate coverage.

Live gameplay, new save/reload cycles, actual Nomic inference, multiplayer entry, projected meshes/collision and terrain coupling were **not exercised** in this worktree. Parent performs integrated validation. Source and semantic catalog paging are resumable linear traversal, not spatial/high-dimensional acceleration indexes; large catalogs may take many ticks and cold metadata page reads still happen on the owner thread. Descriptor inference does not exclusively occupy the pipeline: movement reads can interrupt waiting discovery (see the async-discovery scheduling correction below). Read backlog, worker saturation and pending generated-chunk IO can still delay read completion. Old immutable generated blob files are not garbage-collected. Raw SourceSelector intentionally does not choose representatives itself. Natural neighboring cave union currently requires semantic convergence too; temporarily separate provisional owners can remain. The worker's individual-feature expansion/page caps can stop growth of very large caves. Source edits revise occupancy/material but do not repartition one historical cave ID into new air-component IDs after a new wall splits its air. Safe-air support recognizes a conservative floor whitelist and may reject otherwise valid modded/slab floors. Stored coarse far material detail requires successful near refinement before full fidelity. Nearby chest sampling covers at most two loaded chests and 16 slots/chest per sample. A full-air observation with no real item fallback must await a working descriptor model.

## Async-discovery Ensure observation repair

The production `GeneratedSourceReader` constructor now captures the source world's
sea level on the owner thread alongside dimension/height metadata. `Ensure` consumes
that detached value, rather than dereferencing the null `world` left by constructor
delegation. No Overworld/default sea-level fallback is used; mountain classification
keeps the source dimension's actual elevation threshold. The observation clock still
uses the Overworld tick, and topology preparation remains on the bounded worker.

`SourceEnsureObservationTest` drives the real `Ensure.advance`/`observe` path through
NBT decoding, pending snapshot completion and actual asynchronous topology preparation.
Its detached IO/metadata fixture needs no game bootstrap, persistence or model service;
only the private session holder is allocated without constructors. It verifies yielding
while the worker is pending, the sampling budget, source sea level/dimension, and real
mountain ownership geometry (default/Overworld sea levels give a different result).

Cached Gradle 8.13 `--offline --no-daemon --console=plain build` passed in visible tmux:
build/packaging tests, all seven discovered runtime regression classes (14 Ensure
checks), common/client compilation, remapping and production-jar verification.
Contributing async-discovery commits remain ancestors. No gameplay/GPU/multiplayer or
actual native-model launch was exercised; cold metadata/page IO still has the bounded
but not hard-wall-clock tick limitations described above. This repair does not certify
all eleven runtime symptoms or change their independently owned subsystems.

## Async-discovery scheduling correction: movement reads during resource ownership

Discovery can retain the single store geometry cursor, staged mutation or activity
mutation pause across ticks. Those resources are not needed by `region(...)` movement
reads. `SourceSchedule` now reserves a suspended resource owner outside the ordinary
lanes: reads may interrupt it, but no other discovery (even a requested op ahead of a
background owner) may take its discovery turn. Five read operations grant the owner
one bounded advance; quota expiry no longer lets discovery run to completion before
yielding again. An already running read is not interrupted. Resource completion is
not gated on model readiness. Source edits/unload/shutdown also cancel the suspended
owner and release its cursor/mutation/pause through the existing operation lifecycle.

The per-tick geometry/publication budgets remain one page and 512 reconstructed
leaves. No new concurrency or store schema, projection, terrain/collision, source
ownership or embedding policy is introduced. At that scheduling revision the
independent ownership-map request lane still shared the store cursor; the fix
removed discovery-stage blocking of movement `Read` operations, not every kind of
shared-cursor contention. The ownership latency repair below addresses that remainder.

`SourceDiscoveryReadLatencyTest` drives the actual Session scheduling step, real
Ensure operations, real 100-page LandmarkStore geometry/publication and actual
GeneratedSourceReader movement reads with detached generated NBT. During both
geometry reading and background publication, three queued walk/near-terrain/target
reads completed in nine simulated ticks instead of waiting for 100+ discovery ticks.
Under sustained reads, every read took at most four simulated ticks; all 100 geometry
pages / 102 publication advances completed, without admitting competing discovery or
exposing a partial catalog. Real session invalidation cancels suspended geometry and
publication and releases resources exactly once. These are scheduler tick counts,
not a guarantee on disk IO, workers or live-game movement latency.

Cached Gradle 8.13 `--no-daemon --console=plain build` passed, including all **eight**
discovered main-based runtime classes, packaging fixtures and production-jar
verification. The latency regression passed 12,762 checks, scheduler regression 179,
Ensure observation regression 14 and multi-chunk reader regression 1,036. Retained
build pane `%2146`, run `cff3248e153140298b2aca9481d424ee` (exit 0). The initial build
failed only on the new fixture's hard-coded legacy vector dimension; the fixture
now follows the current native vector size without altering production vectors.
No gameplay, GPU, multiplayer launch or real native-model inference was performed.

## Async-discovery ownership latency repair

The real ownership proof used by terrain support/walk acquisition no longer waits for
an entire discovery geometry read. `SourceOwnership.Request` uses one dedicated
package-private `LandmarkStore` cursor slot, independent of the exclusive public
geometry cursor. Both are server-thread confined over immutable page versions and
share the existing eight-page/65,536-leaf decoded LRU. No competing discovery or
second ownership decoder is admitted. Completion/cancellation releases only the
matching cursor instance, including repeated cancellation of a superseded cursor.
A suspended partial decoder is discarded if the other cursor completes and caches
that same immutable page, so it cannot be reused against the next page key.

The existing ownership queue, source revision checks and per-tick budgets are
unchanged: one request phase, at most four metadata records or one geometry page/
512 decoded leaves or 256 octree node visits/128 staged leaves or 256 known voxel
insertions. AIR and SOLID grant exact native source-coordinate ownership; UNKNOWN
and AABB-only overlap do not. Concurrent source publication still fails the proof
and requires a fresh request. No source-world generation, inference, save-schema
migration, projection or mesh/collision policy was changed.

`SourceDiscoveryReadLatencyTest` now drives a real ownership Request before the
actual Session scheduling step, matching tick ordering, during a real 100-page
Ensure read with sustained generated-terrain reads. Before this repair, the proof
was still pending at tick 200. With both requested and background discovery, it
finishes in 105 simulated ticks while discovery still holds its cursor (six pages
completed); sustained movement reads still take at most four ticks and competing
discovery never starts. The fixture also checks exact far-page ownership and the
unowned neighbor, rather than merely future completion or status messages.

`SourceOwnershipCursorTest` covers interleaved partial decoders in both directions,
cache adoption followed by a different cold page, AIR/SOLID/UNKNOWN and clipped
masks, bounded decode/expansion/cache work, source-revision invalidation,
request cancellation, independent cursor release, superseded-cursor cancellation
and off-thread rejection. These use real store NBT and real Request paths but do
not launch Minecraft, flush a new save/reload cycle, or run an embedding model.

Validation: cached Gradle 8.13 `--offline --no-daemon --console=plain --rerun-tasks
build` passed, all 14 tasks executed, with all **nine** discovered runtime classes,
build/packaging fixtures, common/client compilation, remapping and production-jar
verification. The discovery latency regression passed 13,390 checks and the new
ownership cursor regression 542; retained pane `%2579`, run
`1d205d8996fc49af807afb03ffed9633`, exit 0. Client compilation still emits existing
API Guardian/mixin warnings; malformed embedding persistence fixtures intentionally
log rejection errors. Neither is a failed task. `git diff --check` also passed.

Cold metadata/compressed-NBT IO and worker latency remain non-wall-clock-bounded.
Large catalogues, many-page intersecting features and queued ownership requests
can still exceed a walk deadline; this removes discovery cursor contention, not
all possible latency. Live gameplay, GPU rendering, multiplayer and actual native
v2 model inference have not been verified by this scoped repair.

## Retained render-distance / async-discovery integration

The retained merge keeps the render-distance parent's per-player source window/tile
planner and the async parent's detached generated-NBT reader, worker backpressure,
resource-owner scheduling and independent ownership-proof cursor. `region(...)`
remains movement-critical. `discoveryRegion(...)` is the same real generated-only
observation API in a separately bounded background lane; terrain's render-distance
tile path now uses it. A pending 32³ horizon read yields between <=512-cell advances
rather than running ahead of an entire walk/support request. Both paths retain the
same source coordinates, UNKNOWN semantics and cancellation lifecycle.

Hints are admitted before lane selection, not only after the pipeline goes idle.
At most one hint Ensure is materialized at once (remaining hints stay cheap bounded
seeds); this prevents delayed IO/model work from filling memory with snapshots.
After five movement read selections, admissible requested/background lanes alternate
discovery turns. Parkable background work round-robins, so a delayed tile/descriptor
cannot hide the rest of the horizon. Resource owners still reserve discovery turns
exclusively and release their own cursor/mutation/activity pause; movement reads and
the independently budgeted exact ownership proof keep progressing. View-distance
reads do not require embeddings; landmark construction still requires real compatible
descriptors or the existing compatible item-index fallback.

`SourceLaneFairnessSelfTest` now tests the production `SourceSchedule`, not an unused
lookalike picker. It covers continuous reads plus requested/background traffic,
readiness, parkable IO rotation and exclusive resource ownership. New real Session
regressions in `SourceDiscoveryReadLatencyTest` drive a pending detached tile decode
alongside movement sampling, bounded hint materialization/admission during continuous
reads, background queue backpressure, source-edit cancellation and late completion.
The render-distance coverage/frame/cancellation and generated-NBT/ownership suites
remain discovered by the canonical build. No projection, terrain owner/support,
custom collision or embedding-profile policy was changed by this merge resolution.

Final validation: cached Gradle 8.13 on Java 21 ran
`--offline --no-daemon --console=plain --rerun-tasks build` successfully (14 tasks
executed). `runtimeSelfTest` discovered and ran all **13** main-based classes;
`buildSelfTest` and `verifyProductionJar` passed. New/retained discovery coverage:
296336 planner checks, 7982 frame checks, 21 cancellation checks, 1036 generated-reader
checks, 14558 real Session latency/lifecycle checks, 81 production-lane fairness checks,
179 scheduler checks, 542 ownership checks and 14 detached observation-metadata checks.
The latency fixture measured nine ticks for its initial three-read batch and four
ticks maximum under sustained sampling; these are scheduler advances, not wall-clock
Minecraft latency. The final run corrected a test-fixture expectation: source edits
also cancel an active overlapping movement sample, then a fresh read succeeds.

Retained tmux build handle: session `$11`, original pane `%2667`, build pane `%2702`,
run `2b2080c87aed4921bdc5828ebeabf554` (exit 0). The verified artifact is
`build/libs/mysticism-1.0-SNAPSHOT.jar`. Existing annotation/deprecation warnings and
intentional invalid-profile fixture warnings remain. No dependencies were installed.
`git diff --check` and staged diff checks passed; the merge conflict is resolved.

Progressive coverage is not an instantaneous whole-view snapshot. The far-node/mesh
budgets and planner's +/-64-block vertical band remain; tall views are not full-height
world discovery. Metadata/compressed page IO, registry mesh conversion and packet/frame
work still have bounded counts rather than hard wall-clock deadlines; no live FPS or
movement-stall measurements have been taken. Minecraft/GPU/multiplayer launch and
actual native-v2 model inference have not been verified by this scope.

## Validation actually run (historical concurrent rebuild)

No new tests added and no Gradle run during concurrent rebuild (parent instruction). Scoped Java21 `javac --release 21 -proc:none` compilation of all SOURCE-owned landmark/activity classes against cached mapped Fabric/Yarn 1.21.1 classpath plus the read-only NAV LatentAttunement source. Interactive tmux pane `%463`, script `/tmp/mysticism-source-rebuild-check.sh`; output displayed, not redirected. `git diff --check`. This is compile validation, **not** evidence that live runtime behaviors above have passed.
