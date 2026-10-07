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

## Bounds / honest remaining limits

One active source operation/server, request queue 64, hint queue 128, frontier-retry queue 64, worker queue 4. Live reads <=512 cells and stop after ~1.5ms between cell samples; this is **not** a hard whole-tick deadline for metadata/NBT page IO/publication. Region volume <=32768 and <=16 chunk IO adapters; local overlap operation <=64 parents, <=512 hydrated pages/parent, expanded topology <=131072 cells. These are per-operation limits, not a global catalog cap. Oversized operations fail visibly through futures/status rather than fabricate coverage.

Live gameplay, new save/reload cycles, actual Nomic inference, multiplayer entry, projected meshes/collision and terrain coupling were **not exercised** in this worktree. Parent performs integrated validation. Source and semantic catalog paging are resumable linear traversal, not spatial/high-dimensional acceleration indexes; large catalogs may take many ticks and cold metadata page reads still happen on the owner thread. A single operation waiting for descriptor inference can also delay later queued source reads. Old immutable generated blob files are not garbage-collected. Raw SourceSelector intentionally does not choose representatives itself. Natural neighboring cave union currently requires semantic convergence too; temporarily separate provisional owners can remain. The worker's individual-feature expansion/page caps can stop growth of very large caves. Source edits revise occupancy/material but do not repartition one historical cave ID into new air-component IDs after a new wall splits its air. Safe-air support recognizes a conservative floor whitelist and may reject otherwise valid modded/slab floors. Stored coarse far material detail requires successful near refinement before full fidelity. Nearby chest sampling covers at most two loaded chests and 16 slots/chest per sample. A full-air observation with no real item fallback must await a working descriptor model.

## Validation actually run

No new tests added and no Gradle run during concurrent rebuild (parent instruction). Scoped Java21 `javac --release 21 -proc:none` compilation of all SOURCE-owned landmark/activity classes against cached mapped Fabric/Yarn 1.21.1 classpath plus the read-only NAV LatentAttunement source. Interactive tmux pane `%463`, script `/tmp/mysticism-source-rebuild-check.sh`; output displayed, not redirected. `git diff --check`. This is compile validation, **not** evidence that live runtime behaviors above have passed.
