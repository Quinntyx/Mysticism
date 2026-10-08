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

One ownership request advances/server tick alongside extraction: <=4 metadata records **or** <=1 geometry page with <=512 reconstructed leaves **or** <=256 octree node visits/128 staged leaves **or** <=256 block-map insertions. Queue <=8 including the active request; at most one retained page and 128 staged leaf ranges. Cancellation is polled on tick; dimension unload/server stop cancel queued/active reads and release the shared repository geometry cursor. A concurrent source publication fails the request as stale; consumer re-requests. Cold NBT IO remains synchronous and page-bounded, not a hard wall-clock guarantee. Entire catalogue traversal is resumable; a large catalogue or shared reader contention can delay completion.

**Terrain consumption required (not edited in SOURCE worktree):** request this mask for the exact local/target sliding source bounds in parallel with `region(...)`, retaining/cancelling the future with the Window/session. Check `mask.isCurrent(server)` and matching dimension/bounds before publishing labels or granting support/exit. Label each unit source sample from `mask.ownerAt(pos)`, never from `Window.id`; absent means empty/unowned, not retained-owner permission. Compaction must not merge different owner IDs or owned with unowned voxels: split nonuniform ownership down to unit cells. `sourcePosition` must verify actual mapped foot/body sample ownership against the resolved retained ID; support must use the actual floor-contact cell's map owner, and exit must fail while ownership is absent/stale/outside the queried region. Replace the AABB-only authority check at SpiritTerrainService.sourcePosition (terrain 87510e0 lines 136–141). Do not retune/re-anchor from a changed owner. Current authoritative map is usable only within its requested bounds; refresh after walking outside or topology invalidation.

No tests/build run for this bounded hook, per parent instruction. Terrain wiring remains TERRAIN-owned; this SOURCE commit supplies the callable API, not a claim that terrain has already consumed it.

## Review correction: source/activity reconciliation

`SpiritActivityService.prepareSourceUpdate(server, before, after)` returns the existing `LandmarkExtractionService.TopologyPlan`. SourceLandmarks acquires the activity mutation pause before computing the importance-limited source update; prepares the overlay correction before `stagePut`; commits it immediately after the core source publication and before completing the request; cancels it on failure/unload/shutdown. It applies `effective + (newBase - oldBase)` and normalizes, preserving the prior activity residual, importance mass/timestamps, owners and immutable claims. No-change source vectors do not repeatedly erase personality history. Revision/overlay identity checks reject stale publication. This closes the reviewed bug where an existing persisted activity vector permanently masked source changes. No additional tests or Gradle were run for this correction.

## Bounds / honest remaining limits

One active source operation/server, request queue 64, recency hint queue 256, generation survey queue <=4 positions per still-loaded source chunk, frontier-retry queue 64, worker queue 4. Live reads <=512 cells and stop after ~1.5ms between cell samples; this is **not** a hard whole-tick deadline for metadata/NBT page IO/publication. Region volume <=32768 and <=16 chunk IO adapters; local overlap operation <=64 parents, <=512 hydrated pages/parent, expanded topology <=131072 cells. These are per-operation limits, not a global catalog cap. Oversized operations fail visibly through futures/status rather than fabricate coverage.

Live gameplay, new save/reload cycles, actual Nomic inference, multiplayer entry, projected meshes/collision and terrain coupling were **not exercised** in this worktree. Parent performs integrated validation. Source and semantic catalog paging are resumable linear traversal, not spatial/high-dimensional acceleration indexes; large catalogs may take many ticks and cold metadata page reads still happen on the owner thread. A single operation waiting for descriptor inference can also delay later queued source reads. Old immutable generated blob files are not garbage-collected. Raw SourceSelector intentionally does not choose representatives itself. Natural neighboring cave union currently requires semantic convergence too; temporarily separate provisional owners can remain. The worker's individual-feature expansion/page caps can stop growth of very large caves. Source edits revise occupancy/material but do not repartition one historical cave ID into new air-component IDs after a new wall splits its air. Safe-air support recognizes a conservative floor whitelist and may reject otherwise valid modded/slab floors. Stored coarse far material detail requires successful near refinement before full fidelity. Nearby chest sampling covers at most two loaded chests and 16 slots/chest per sample. A full-air observation with no real item fallback must await a working descriptor model.

## Validation actually run

No new tests added and no Gradle run during concurrent rebuild (parent instruction). Scoped Java21 `javac --release 21 -proc:none` compilation of all SOURCE-owned landmark/activity classes against cached mapped Fabric/Yarn 1.21.1 classpath plus the read-only NAV LatentAttunement source. Interactive tmux pane `%463`, script `/tmp/mysticism-source-rebuild-check.sh`; output displayed, not redirected. `git diff --check`. This is compile validation, **not** evidence that live runtime behaviors above have passed.

## Generation survey overflow correction (P2)

Chunk-load surveys now retain every admitted actual surface-component/peak position in
`GenerationSurveyQueue`, independently of the 256-entry player/edit/frontier recency
set. Delayed embedding/index readiness and later chunks cannot evict the earliest
non-center terrain component. Retention is bounded by residency: at most four immutable
positions plus a chunk-coordinate key per still-loaded source chunk; no world, chunk,
or snapshot references. Chunk/dimension unload and server stop release this debt;
chunk reload surveys actual terrain again. This does not add tickets or force generation.

Idle source operations alternate generation probes with recency hints. The existing
asynchronous Ensure pipeline observes actual blocks, prepares native source octree
ownership, embeds descriptors (or uses the real compatible item index), and publishes
through the paged store. Only a successfully completed mutation containing the exact
known AIR/SOLID probe retires that generation position; null preparation, inference
failure, cancellation, and missing ownership rotate it for retry. Rotation covers both
chunks and sibling probes within a chunk. Stale completions from before unload/reload
cannot acknowledge replacement work. Explicit request priority remains unchanged.

`GenerationSurveyOverflowTest` delays readiness for 145 source chunks with two actual
surface-biome components each (290 distinct generation probes, beyond the old budget).
It reproduces eviction from the old recency set, drains the production survey queue
through actual `prepare` and paged `LandmarkStore.stagePut`, saves and cold-reloads,
and checks persisted exact ownership at every retained probe, including the earliest
non-center forest floor and air cell. Unknown cells stay unowned. Additional checks
cover failed-probe fairness, duplicate/stale completion, chunk/dimension unload,
replacement surveys, immutable admission, per-chunk budget, and shutdown cleanup.

Validation: `./gradlew --no-daemon --console=plain build` in visible tmux pane `%1291`
runs all eight discovered runtime test mains (overflow test: 2723 checks), packaging
self-tests, production-jar verification, and the complete build. This is deterministic
queue/prepare/persistence validation, **not** a live Minecraft render-distance/model
inference smoke test. Prior broad handoff limits still apply: one operation/model wait
or continuous explicit request pressure can delay background surveys, and generation
surveys intentionally select bounded major components rather than every source cell.
The other runtime repair areas remain integration-owned; this correction changes no
projection, collision, render, or embedding profile/migration contract.

## Spawn-region startup ordering correction (P2)

Fabric spawn-region `CHUNK_LOAD` events occur before `SERVER_STARTED` constructs the
source session. The callback now surveys the supplied actual source chunk **before**
looking up that session. `GenerationSurveyLifecycle` owns the same residency-bounded
queue before and after startup; `SERVER_STARTED` hands it to the session rather than
allocating a replacement. Only immutable surface/peak positions are retained, not live
column views, world/chunk references, snapshots, stores, or early model initialization.
Existing paged Ensure ownership/publication, native v2 profiles, request priority,
independent projections, and custom collision/render contracts are unchanged.

Chunk and dimension unload remove generation debt even before any session exists.
Both `SERVER_STOPPING` and `SERVER_STOPPED` perform idempotent cleanup, also covering
startup that fails without reaching `SERVER_STARTED`. Server identities are isolated.
The startup path adds no chunk tickets, forced generation, or loaded-world rescan.

`GenerationSurveyStartupTest` exercises early two-biome source-column load events,
startup handoff, readiness gating, detached capture, post-start admission, pre-start
chunk/dimension unload, separate server identities, and failed-startup cleanup. It also
checks the **compiled Fabric event registrations and callback order**, so an early
null-session guard in `CHUNK_LOAD` cannot silently bypass the tested lifecycle helper.
`GenerationSurveyOverflowTest` now submits all 145 chunks / 290 component probes through
that actual survey/lifecycle path **before startup**, rather than directly admitting
probe lists to a queue. After handoff it prepares real terrain masks, publishes through
the paged store, saves/cold-reloads, and verifies exact persisted ownership of the
earliest non-center surface biome's floor and air cell without any unload/reload or
player hints. Its check count changed because readiness is asserted at startup handoff
instead of once for each pre-start load; no ownership checks were removed.

Validation: `./gradlew --no-daemon --console=plain build` with the installed Java 21 in
visible tmux pane `%1333` passed all **nine** discovered runtime test mains (startup:
29 checks; overflow/persistence: 2579 checks), packaging self-tests and production-jar
verification. `git diff --check` passed. These are deterministic lifecycle, compiled
adapter-wiring and terrain persistence regressions, **not** a live Minecraft startup
or actual descriptor-model inference smoke test. Background surveys still wait behind
active source operations/model inference and explicit requests; bounded surveys select
major surface components/peaks, not every source cell. The broad eleven-problem runtime
integration still requires live Minecraft validation in the integration worktree.
