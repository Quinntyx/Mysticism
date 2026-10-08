# Terrain rebuild handoff — observer-local meshes

Authority: `docs/spirit-world-approved-design.md`, read completely. This supersedes the old frozen-frame/block-overlay prototype. One bounded implementation pass; no Gradle, new tests/dependencies, source-world writes, global saved projection, or safety floors. Parent owns integration and runtime smoke.

## Checkpoint and bounded independent-review follow-up

Base mesh checkpoint: `13ea4db22273304d9855df39292d612e47abd97d`. Independent review supplied by parent identified five TERRAIN findings (the SOURCE overlay-reconciliation finding belongs to the source/activity worker). The bounded follow-up implements: current—not captured—target geometry with versioned exact destination ownership proof; no discovery re-anchoring; live source capture precedence; conservative continuous swept geometry/body guard plus overlap depenetration; and reserved nearest-region admission with stale-region fading. `init()` now installs the REAL NAV LandingSafety and late-source anchor callback. No unconditional success guard, q/basis snap, alternate safe arrival or new tests. Compilation/runtime are still parent-owned and not performed here.

## Current-support walking acquisition API (NAV coordination)

Implemented the bounded flight-off path after exact ownership integration. NAV calls these TERRAIN-owned methods, not `setShallow` on the remembered entry window:

```java
public record WalkSupport(String landmarkId, String sourceDimension,
    Vec3d sourcePosition, Basis384f sourceBasis, Vec384f sourceCoordinate,
    Vec3d normal, long windowIdentity, long ownershipRevision) {}
public static Optional<WalkSupport> currentSupport(ServerPlayerEntity player);
public static boolean acquireCurrentSupport(ServerPlayerEntity player);
public static boolean acquireCurrentSupport(ServerPlayerEntity player, WalkSupport expected);
public static boolean ownershipPending(ServerPlayerEntity player);
public static void cancelCurrentSupport(ServerPlayerEntity player);
```

`sourcePosition` is the CURRENT actor feet inverse-mapped through the SAME visible affine cell; not an old target, safe-air replacement or block-center snap. `sourceBasis` is that producing region's captured source grid (defensive clone), and `sourceCoordinate` is the producing window's source-grid coordinate, separate from user attunement (NOT a q replacement). `windowIdentity` is stable for that actual source projection window; the expected-support overload rechecks it, the actual owner and exact ownership revision before mutation. `currentSupport` requires current exact AIR/SOLID ownership at actual floor and body voxels. NAV may continuously align its basis toward `sourceBasis` using `canAlign`; acquisition revalidates the current contact, matching source basis, projection/body clearance and whole-frame transition. It preserves q, body position and user captured attunement, and only succeeds after installing a geometry-compatible current source window. False means remain Deep. NAV already consumes this schema in the sibling worktree. It must retain Deep/flight while ownership, source samples, basis or geometry remain unconfirmed. Call `cancelCurrentSupport` on approach cancellation/expiry. One bounded 12³ material probe/player, actual-floor/body ownership from the producing window's 16³ exact mask, 20-tick retry backoff, current geometry-key recheck and a whole-frame SAT/body transition gate precede publication. Probe outer unknown cells are NOT converted to floors; every body/floor sample must exist and pass collision checks. Near source materials replace only the sampled patch; remaining producing-region surfaces and other active representatives are retained. No q, basis, physical position, velocity or captured-attunement setter is called by acquisition. Source coordinates retain fractional X/Y/Z.

## Public common API

Class: `io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService`

```java
public static void init();
public static boolean prepareEnter(ServerPlayerEntity player);
public static boolean restore(ServerPlayerEntity player);
public static void cancelEnter(ServerPlayerEntity player);
public static boolean exit(ServerPlayerEntity player);
public static Optional<Support> support(ServerPlayerEntity player);
public static Optional<SourcePosition> sourcePosition(ServerPlayerEntity player);
public static Optional<ProjectionFrame> projectionFrame(ServerPlayerEntity player);
public static Optional<Vec3d> project(ServerPlayerEntity player, Vec384f location);
public static LandmarkEmbedding coordinate(Vec384f location);
public static boolean clearRay(ServerPlayerEntity player, Vec3d from, Vec3d to);
public static void setShallow(ServerPlayerEntity player, boolean shallow);
public static void prefetchTarget(ServerPlayerEntity player, String dimension,
    String landmarkId, BlockPos position, Vec384f captured, Basis384f sourceBasis);
public static boolean landingReady(ServerPlayerEntity player, String dimension,
    String landmarkId, BlockPos position);
public static boolean tryLandTarget(ServerPlayerEntity player, String dimension,
    String landmarkId, BlockPos position);
// Fractional captured feet: real overloads, no flooring of the source pose.
public static void prefetchTarget(ServerPlayerEntity player, String dimension,
    String landmarkId, Vec3d position, Vec384f captured, Basis384f sourceBasis);
public static boolean landingReady(ServerPlayerEntity player, String dimension,
    String landmarkId, Vec3d position);
public static boolean tryLandTarget(ServerPlayerEntity player, String dimension,
    String landmarkId, Vec3d position);
public static Optional<TerrainMeshFrame> mesh(ServerPlayerEntity player);
public static void onFrame(BiConsumer<ServerPlayerEntity,TerrainMeshFrame> transport);
public static boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
public static void onSourceAnchor(SourceAnchorListener listener);
// interface SourceAnchorListener:
// void anchor(ServerPlayerEntity player, SourcePosition source, Basis384f capturedSourceGrid);
```

Nested public records: `Support(String landmarkId, Vec384f embedding, Vec3d center, Vec3d normal)` (defensive vector copies); `SourcePosition(String dimension, Vec3d position, String landmarkId)`.

**Projection consumers:** q and peer/item positions are non-unit LOCATIONS. Use `project(player, rawLocation)` or `projectionFrame(player).project(coordinate(rawLocation))`, NOT `LandmarkProfiles.wrap(rawLocation)`; the latter's UNIT profile is intentionally incompatible with the coordinate profile. Each frame snapshots CURRENT q/basis and that player's physical carrier origin. No server-global frame overload remains.

**Navigation:** prepare in source world before teleport; initial capture does not wait for extraction/embedding readiness. Call `setShallow` when mode changes. Shallow retains source-grid alignment; actual support reports the mesh owner. `sourcePosition` uses current exact mask ownership and fails for unknown samples, removed/different current owner, or a different grounded owner; transient unanchored native mappings can have a blank ID with no exit permission. Alias IDs are resolved through the current repository. Normal exit validates body clearance and materializes at CURRENT source coordinates, never an old entry/spawn substitute.

**Landing correction coordinated with NAV review:** NAV must continuously approach captured q and basis BEFORE `tryLandTarget`. `landingReady` requires exact source-owner confirmation plus a CURRENT geometry-version proof of every body/foot voxel (not header bounds), complete 12³ source read, unchanged current destination versus captured guidance, q error <=2e-6 semantic units, and summed basis squared error <1e-8. The proof reader stops once exact footprint ownership is known; it does not hydrate all remote pages just to land. Target geometry uses the current effective embedding and does not occupy/suppress a normal representative slot. Invalid/unconfirmed target geometry is not drawn as a fabricated floor. `tryLandTarget` rechecks current source body/floor when loaded (cached exact source samples otherwise), publishes current target geometry, requires actual target-owned mesh ground contact, and requires projected target/carrier difference <=.001 blocks. It DOES NOT set q/basis or reposition the carrier. Only then rebinds the exact captured source origin and publishes shallow. False means stay deep at the same position; no alternate landing. NAV owns smooth approach, automatic flight while anchoring is pending, and client prediction-baseline reset. Reconnect/tick prewarm uses `nav.targetPosition()` and `nav.targetBasis()`, not a floored or default replacement. BlockPos overloads delegate to legacy center-XZ/integer-Y feet. Terrain's installed LandingSafety adapter implements BOTH BlockPos and Vec3d readiness. NAV owns the effective-vector capture and exact-foot CCA persistence; actual captured attunement is never updated by terrain.

## Canonical transport/cache shape

Common `TerrainMeshFrame`:

```java
record TerrainMeshFrame(long revision, boolean shallow, String sourceDimension,
    Vec3d sourceOrigin, Vec3d carrierOrigin, List<Material> materials, List<Cell> cells)
record Material(String blockId, Map<String,String> properties)
record Cell(long key, int material, String landmarkId, Vec3d sourceMin,
    Vec3d min, Vec3d size, Vec3d axisX, Vec3d axisY, Vec3d axisZ,
    int color, int light, float opacity, List<Box> collision)
```

`min` is absolute observer carrier-space position; render only subtracts camera. `sourceMin`/`size` are exact source coordinates/extent. Columns transform the UNIT baked model; collision boxes are LOCAL shape coordinates transformed by those SAME columns. `Cell.bounds()`/`bounds(Box)` provide affine world broadphase bounds; not substitutes for narrowphase. Stable keys hash full source owner plus exact source min/side (not `String.hashCode`). Empty landmarkId means not yet owned; never invent chunk identity.

Limits: 2048 cells, 256 materials, 8 shape boxes/cell; immutable validated frames. Transport receives on the server thread and only after entry, full initial frame then its own bounded deltas keyed by cells/materials. Unchanged frames are suppressed. NETWORK owns actual payloads, connection/entry/dimension epoch validation, retransmission and complete client reconstruction; no duplicate networking classes here. On exit/disconnect call client `clear`/send network lifecycle clear. Entry epochs must distinguish a fresh revision sequence from the previous session.

## Client API and integration hooks

`io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient`:

```java
public static void init();
public static void accept(TerrainMeshFrame frame); // client thread, session already checked
public static void clear();
public static Optional<TerrainMeshFrame> frame();
public static Optional<String> failure();
```

Actual baked block-model drawing uses up to 64 bounded VBO groups, MAIN-target `SpiritRenderLayers.texturedMain(BLOCK_ATLAS_TEXTURE)`, depth writes, fog-distance/frustum culling. BEFORE_ENTITIES draws and flushes its own VBOs; never calls suppressed `WorldRenderer.renderLayer`. Calls `ShaderManager.afterMeshDepth()` AFTER drawing. Occupancy medium publishes `ShaderManager.setMediumSamples(samples, exactRasterEyeCenter)` with the exact raster origin. Shader/render owner handles peers/drops/postprocessing. Render failure sends visible status; buffers/cache clean on disconnect, exit, resource invalidation and stop.

Exact init glue: parent calls `SpiritTerrainService.init()` after source/activity/NAV init, and `SpiritTerrainClient.init()` on client before networking accepts frames. Terrain init itself executes `SpiritNavigationService.installLandingSafety` with `canAlign -> SpiritTerrainService.canAlign` and `ready -> SpiritTerrainService.landingReady`, and `onSourceAnchor(SpiritNavigationService::anchorSource)`; do NOT install a stub/always-true replacement. Late NAV anchoring shifts the local coordinate gauge by the actual q delta so existing surfaces stay physically unchanged. NETWORK connects `onFrame` to transport and calls `SpiritTerrainClient.accept` on the client thread. Register these COMMON mixin FQCNs (parent controls JSON/package prefix):

* `io.github.mysticism.dimension.spiritworld.terrain.mixin.SpiritMeshCollisionMixin`
* `io.github.mysticism.dimension.spiritworld.terrain.mixin.SpiritMeshSneakMixin`

Movement collision is replaced only for players in `mysticism:spirit`, server AND predicted client, including flying. Carrier block-contact callbacks and carrier-Y void damage are disabled for these players; source dimensions/entities are untouched. Sneak ledge clipping uses mesh ground, not carrier AIR. Existing parent interaction/native-scene mixins remain required and are not duplicated.

## Implemented executable path

* Immediate loaded source capture around ANY entry location, including actual vanilla collision boxes and source biome tint/light; async `SourceLandmarks.region` reads only generated source terrain for the sliding view. No `setBlockState`, chunk ticket stamping, ledger activation or `TerrainState` initialization.
* Source tiles are retained under a 32768-tile window, refreshed body/floor first plus bounded cyclic refresh. Background ingestion prefers real loaded collision/light/tint and preserves previously live captures; cold shape resolution uses retained source neighbor states rather than an always-empty view. Uniform fully solid equal-material octree compaction only outside seven blocks; near ~five blocks remains unit source models/shapes. No artificial bridges/floors. Unknown terrain stays unknown.
* Radius catalog discovery uses the actual `LandmarkStore.sourceRangePage` cursor, eight records per advance across source dimensions, not the old global-256 scan/rejection. Cluster slots use distortion, source-independent density-neutral priority, hysteresis, bounded importance and inverse-square proximity (inner-five-percent exact-near target always admitted). Current q is rechecked before activation; removed/stale medoids don't remain locked forever.
* Resumable per-region source-octree streaming now has a spatial 16³ near pass followed by every referenced page of the coarse far pass. A saved page index resumes after reader release; a partially decoded page finishes rather than replaying its prefix. Shared budgets: one page/256 decoder leaves per tick, then 128 octree node visits/32 emitted leaves and <=32 cold material resolutions; 128 near snapshot cells/tick are separately shared across sessions. A deterministic 768-node projected-distance reservoir and bounded material cache retain relevant geometry, not a first-page cutoff or whole-feature hydration. SOURCE's bounded octree Cursor is consumed directly.
* Up to nine PREPARED geometry windows are separate from eight normal ACTIVE representative regions plus one RESERVED closest region. Current radius is rechecked before admission. Already prepared surfaces outside the actual opaque 64-block horizon activate hidden; close geometry waits for near source samples then fades in over 20 ticks. Exact prewarmed targets also wait for the fade to finish before landing, without q/basis/body snapping or radius bypass. Near-five-block existing surfaces stay visible; every replacement still passes the common render/collision transition guard. Active revisions compare immutable geometry keys, so source/activity ownership growth restreams geometry without recreating stable anchors or captured targets; activity-only metadata changes don't repeatedly restart unchanged pages. Far visible revision replacements fade out before swapping; near protected patches update through the body guard. Eight normal representative regions plus one RESERVED closest region are bounded; stale unselected regions fade even if projection is within 80, except actual support/body-adjacent surfaces. Capacity deferrals requeue instead of dropping the exact-near choice; closest geometry renders before background representatives. Shallow renders only its source-local window so unrelated semantically similar regions cannot crowd the initial scene. Deep projects local/target/representatives through CURRENT observer basis. Exact prewarmed targets get priority. Fading removal checks actual active cell distance and never removes near-player surfaces just because their semantic root is far away.
* Shared exact-overlap surface stitching eliminates duplicate coplanar same-shape cells, deterministic material dithering keeps collision identical, and affine model/shape deformation is shared by renderer and swept SAT physics. The conservative affine endpoint-AABB envelope rejects any ambiguous continuous sweep through the body before publishing BOTH render/collision frames; unsafe NAV basis proposals are refused before application. Existing penetrations receive bounded SAT separation corrections, not ignored contacts. A held frame cannot be used to approve landing, and unsuccessful shallow-frame publication rolls back the mapping. Broadphase eight-block buckets, bounded split sweep, sliding, stepping, jump and sneak behavior use actual meshes.
* Server/dimension/disconnect/respawn cleanup cancels owned async tasks and releases collision cache; reconnect rebuilds derived meshes from NAV's per-player source binding, never a frozen global save. Normal source blocks, inventory and stats are not changed. Old overlay/math helpers remain solely for existing historical tests; no active service calls them.

## Honest remaining limits / parent fix priorities

1. **No compile/live/GPU smoke executed here** per parent serialization request. Parent must compile against the merged source/NAV/render APIs and validate mixin injection, entry/exit/landing, flight collision, reconnect and Fast/Fancy/Fabulous.
2. **Exact ownership integrated:** consumes SOURCE commit `a231c552639b9199474700e0efa9f98829e26dfb` (`SourceLandmarks.owners`, `SourceOwnership.Region.ownerAt/isCurrent`). Current floor/body mappings use known AIR/SOLID mask cells, not retained labels or AABBs. Local compaction never merges differing or unknown owners; queried coarse leaves split at actual ownership boundaries. Lookup windows are bounded 16³/4096 cells, one live future per source window with 20-tick retry backoff; support/exit defer on stale/unknown masks. Initial unanchored native view may report a blank provisional binding, which explicitly grants NO support/exit permission. NAV should use `ownershipPending` to defer transient invalidation rather than interpreting it as a confirmed region departure. Global ownership churn and SOURCE's eight-request budget can delay confirmation; far cached representative geometry is still a finite visual sample, not continuously complete ownership refresh.
3. **Landing depends on NAV's continuous q/basis alignment.** The strict gate intentionally refuses rather than snapping. NAV currently captures `header.baseEmbedding()` while terrain correctly places at `SpiritActivityService.effectiveEmbedding`; parent/NAV must capture the effective vector at capture time if visited-source targets should initially match, while keeping that snapshot frozen afterward. NAV reports the final acquisition gate now matches terrain's 2e-6-unit/1mm requirement (the larger band only starts approach). Controller convergence can still stall rather than guarantee arrival. Exact Vec3d target overloads preserve slab/stair foot heights; legacy BlockPos callers deliberately retain integer-Y feet. No quiet safe substitute is provided. Test that NAV reaches these tolerances and resets prediction baseline; changed/invalid targets remain deep. Target source confirmation can be delayed by embedding/source queues. Unloaded target snapshot can become stale before source edits are republished.
4. **Streaming implementation, bounded rather than complete hydration:** traversal resumes across every page and spatially prioritizes near source geometry. Retained representative meshes remain finite (768 nodes/region plus unit near samples); very diverse geometry can be omitted from the reservoir/frame material limits. Uniform source octree cells provide coarse far geometry; nonuniform detail is not fabricated into solid safety floors. SOURCE pages remain immutable. Revision polling rotates over windows and sessions, so busy SMP/large cold pages can delay refresh; source bodies/ownership still fail closed. Near/source unknown cells remain unknown. Completed streams restart for substantial source-focus movement, not every camera frame; arbitrary basis-only changes may temporarily retain an older far reservoir until another spatial/revision pass.
5. **Fog admission is implemented with the approved close fallback:** PREPARED/ACTIVE stages use actual projected bounds and 64-block opacity, then 20-tick fades for necessary close admission. No absolute hidden-only claim: close current-radius representatives can appear inside fog with fading, and protected five-block source changes can update directly through the shared body guard. Exact target fade must complete before landing. Dense protected near regions can defer another candidate rather than remove a floor under the body. Five-block collision/visible agreement takes precedence over a blanket fog gate.
6. **Smoothing follow-up implemented:** overlapping representative borders now sample a smooth bounded height-displacement field (three-meter border band, <=12cm at every affine corner). Source-local meshes and the near-five-meter patch remain undeformed, with smooth onset to eight meters. A determinant guard prevents inverted/collapsed cells. The SAME resulting cell columns go to model rendering, transport and SAT. Dithering is deterministic and restricted to equal unit collision cubes with opaque ordinary-model palettes; stairs/translucent/contextual shapes don't exchange skins. This is an inexpensive affine approximation, not a nonlinear SDF, watertight triangulation, or general cliff-filling operation. Visible holes/cliffs remain legitimate; there is no watertight-floor guarantee or safety floor.
7. **Model cases:** stairs/slabs/fences use real source blockstate shapes/models. Unloaded snapshots still approximate light/tint and unknown neighbor/entity context; they no longer overwrite accurate loaded captures. Dynamic block entities use a collision-shaped stone-model fallback; fluid surfaces and entity-context-specific shapes (e.g. powder snow equipment) are not fully modeled. Slipperiness/sound/block-contact effects still use vanilla carrier behavior except suppressed carrier contacts; collision itself uses mesh. GPU failure status is visible, but no software terrain renderer exists.
8. **Rebuffering/performance:** shallow transforms don't upload unchanged cells, but source refresh/deep basis evolution can rebuild bounded VBOs and broadphase frequently. Conservative swept-AABB holding can refuse safe tilted/rotating changes near the body; move clear rather than teleporting or letting a surface pass through. Exact ownership proof can be delayed/restarted by concurrent repository mutation. Limits are finite, not measured SMP/GPU performance. Up to 64 server sessions; excess refuses entry.

## Required narrow SOURCE/core cursor glue for this pass

Parent must expose the already requested source page cursor methods (no SOURCE files edited by terrain):

```java
LandmarkStore.GeometryRead beginGeometryRead(String id, Bounds range, int nextPageIndex);
int LandmarkStore.GeometryRead.nextPageIndex();
```

The overload initializes the existing private page cursor at `0..geometryKeys.size()` after metadata lookup/validation; the getter returns only completed/skipped-page progress, never a partially decoded leaf offset. Terrain cancels/yields only at that boundary and resumes against the same immutable geometry-key list. If geometry keys change, it starts a new stream. `SparseOctree.cursor(range)` / Cursor.advance(nodeBudget, cellBudget) / complete() and both LandmarkStore cursor methods are now integrated from SOURCE/core `9aa6842`. Parent reports the integrated pre-correction compile/client compile/production jar passed. Terrain has not rerun those checks. No reflection/stub/duplicate store is used.

## Corrective round 1: producer protection, replacement coverage, confirmed fade

* Accepted frames retain a bounded server-local `cell.key -> producing Window` map, filtered after stitching. Build/canAlign previews do not mutate that map; held/rejected frames keep original provenance. Ownership relabeling no longer changes near-five-block protection or source-support window resolution. No mesh record/payload field or NETWORK change is required.
* Protected streams retain their prior geometry until a COMPLETE 16³ material patch covers the source bounds of every accepted producer cell within five projected blocks. Source materials resolve in a separate staging window under the existing 128-cell/tick budget, then atomically commit only on complete/current-geometry-key coverage. A single emitted stream node or incomplete ingestion cannot erase an unvisited old floor. Known AIR in a confirmed patch may legitimately reveal holes. Render and collision still share the accepted frame/body guard. Conservative whole-cell coverage can delay a strongly compressed/coarse near patch until coverage grows or the player moves clear; this intentionally retains support rather than guessing a floor.
* Target opacity is proposed from the last successfully published target opacity, not accumulated on ticks while the transition guard rejects publication. Confirmation requires target-produced cells surviving stitching, caps opacity by the requested fade step, and resets when geometry proof restarts or target geometry is absent. Landing checks confirmed opacity, not requested opacity. Transport exceptions roll back the accepted frame/provenance/revision and do not confirm fade. This is server publication confirmation, not a new client/GPU acknowledgement protocol.
* Scope: only `SpiritTerrainService.java` and this handoff. No tests, new dependencies, SOURCE/NAV/NETWORK/config/initializer edits. Parent owns the next compile/light smoke.

## Corrective round 2 (final): acquisition publication and pre-compaction coverage

* Walking acquisition now builds `BuiltMesh`, validates before any accepted-state mutation, and uses the SAME `acceptFrame` helper as ordinary publication. Frame, producer map and revision commit/roll back together. Acquisition also restores source-local window/carrier/mode/region bindings on transport failure; future cancellation and probe cleanup happen only after success. Held/body-rejected previews leave the old frame/provenance intact.
* Narrow direct-assignment audit: initial pre-entry capture installs both frame and map; runtime replacements are centralized in `acceptFrame`; unchanged successful publication only swaps matching provenance for identical accepted geometry. No other standalone `s.frame = build(...)` path remains.
* `advanceNear` checks accepted producer coverage BEFORE tile pruning, tile replacement or node compaction. Complete 16³ samples that don't cover a compressed five-block projected patch leave all previous tiles/nodes untouched and retry via existing scheduling. Confirmed complete/current coverage (including known AIR) can commit legitimate holes. This conservative whole-patch gate may defer covered subarea edits while an outside portion remains unconfirmed; it does not silently delete the outside floor or create an invisible safety floor.
* Canonical approved spec read: `dev/docs/spirit-world-rebuild-spec.md`. Parent reports pre-round-2 compile/package, floor/slope/ray numerical smoke and GLSL syntax smoke passed. Those are parent results, not tests run here. No tests added; only this handoff and terrain service changed. Parent owns compilation/light smoke and final narrow recheck.

## Checks at checkpoint

Passed: `git diff --check` at committed checkpoints, the owner/acquisition/fractional-foot pass, the resumable-stream/revision/frontier pass, the three-blocker corrective round 1, and the final two-path corrective round 2; bounded source-level corrective pass against the supplied independent six-finding review and NAV follow-up `603bf9b` APIs. Source finding 1 is explicitly outside terrain scope and still requires source/activity reconciliation; not assumed solved. Not run: Gradle/javac, existing offline mains, live Minecraft, model endpoint, GPU/SMP. No new test batteries/scripts or migration machinery. Source/config/initializer ownership remained with parent/other workers.

## Corrective round 3: bounded discovery retention with render-distance coverage

* New `DiscoveryBudget` (terrain package) is the single explicit retention/coverage contract: `RENDER_DISTANCE=128` (shared by the mesh append cull and the geometry-stream far cull), `EXACT_RETENTION_RADIUS=24`, `MIN_RETENTION_RADIUS=8`, `MAX_TILES=32768`, `MAX_EVICTED_PER_PASS=512`.
* The former silent cuts are gone. The runtime tile prune (`>32768 tiles -> remove everything beyond 32 blocks in one removeIf`) and the per-near-patch `>24 blocks of focus` wipe both replaced by `enforceRetention`: farthest-first eviction, redundant far AIR first (air neither renders nor supports, so evicting it cannot change a single rendered or supported node), exact support radius protected while any farther sample can absorb pressure, bounded to 512 removals per pass, deterministic distance-then-position ordering. Discovered geometry therefore keeps spanning the discovery footprint out to the render distance under real memory pressure instead of collapsing to a tiny locality; cap pressure only trims the farthest solids after all redundant far AIR is exhausted, and never reaches inside the minimum support floor.
* Near-patch commits no longer discard everything beyond 24 blocks of each new focus; staged live-read tracking is merged into the committed window (`liveTiles.addAll`), and `ingest` records live reads in `liveTiles` so later stored snapshots can never overwrite loaded collision/light/tint captures (the guard the stored-fallback branch always intended).
* No range/work budget was widened to compensate: discovery request sizes, per-tick ingestion (256 cells), near snapshot streaming (128 cells/tick shared) and the 768-node stream reservoirs are unchanged. Only the retention policy changed, so visibility is no longer bounded by retention below what discovery actually delivered.
* Regressions: `src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/DiscoveryBudgetTest.java` (main-based runner): within-cap no-eviction, render-distance coverage preservation (far-air-first, farthest-only solid trimming, coverage far beyond the legacy 32-block cut), exact 24-block support protection, last-resort minimum-floor protection, air-first/tie-break determinism, and compacted-node equality under air-only eviction.
* Scope: `DiscoveryBudget.java` (new), `SpiritTerrainService.java`, `TerrainGeometryStream.java` (cull constant only), this handoff, and the new runtime test. Ran `./gradlew --no-daemon --console=plain build` (compile, client compile, remapJar, verifyProductionJar, buildSelfTest, runtimeSelfTest: 5 test classes green). No live server/client launch, GPU or embedding service was exercised; game/model assets were not available in this environment.
