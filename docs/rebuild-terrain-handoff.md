# Terrain rebuild handoff — observer-local meshes

Authority: `docs/spirit-world-approved-design.md`, read completely. This supersedes the old frozen-frame/block-overlay prototype. One bounded implementation pass; no Gradle, new tests/dependencies, source-world writes, global saved projection, or safety floors. Parent owns integration and runtime smoke.

## Checkpoint status (parent requested immediate commit)

Independent six-finding review corrective pass IN PROGRESS. At this checkpoint: target meshes use CURRENT owner embedding rather than the captured guidance, independent of normal region slots; landing exact-cell ownership proof streams current repository geometry; late ownership no longer replaces the local captured mapping; loaded tint/light/shapes override cold snapshots; swept overlap depenetration and a conservative local geometry-transition validator exist. Still finishing: install actual NAV LandingSafety/preblend and late-anchor callback, call transition validator from publication, and reserve/evict region admission. Landing remains fail-closed until the real guard is installed. No compile/test performed. Later sections describe the intended complete handoff; this checkpoint status overrides them where integration is pending.

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
public static Optional<TerrainMeshFrame> mesh(ServerPlayerEntity player);
public static void onFrame(BiConsumer<ServerPlayerEntity,TerrainMeshFrame> transport);
```

Nested public records: `Support(String landmarkId, Vec384f embedding, Vec3d center, Vec3d normal)` (defensive vector copies); `SourcePosition(String dimension, Vec3d position, String landmarkId)`.

**Projection consumers:** q and peer/item positions are non-unit LOCATIONS. Use `project(player, rawLocation)` or `projectionFrame(player).project(coordinate(rawLocation))`, NOT `LandmarkProfiles.wrap(rawLocation)`; the latter's UNIT profile is intentionally incompatible with the coordinate profile. Each frame snapshots CURRENT q/basis and that player's physical carrier origin. No server-global frame overload remains.

**Navigation:** prepare in source world before teleport; initial capture does not wait for extraction/embedding readiness. Call `setShallow` when mode changes. Shallow retains source-grid alignment; actual support reports the mesh owner. `sourcePosition` fails for outside retained bounds, unknown foot samples, removed owner, or a different grounded owner. Alias IDs are resolved through the current repository. Normal exit validates body clearance and materializes at CURRENT source coordinates, never an old entry/spawn substitute.

**Landing correction coordinated with NAV review:** NAV must continuously approach captured q and basis BEFORE `tryLandTarget`. `landingReady` requires the exact prewarmed target's source-owner confirmation, complete 12³ source read, q error <=2e-6 semantic units, and summed basis squared error <1e-8. `tryLandTarget` rechecks current source body/floor when loaded (cached exact source samples otherwise), publishes current target geometry, requires actual target-owned mesh ground contact, and requires projected target/carrier difference <=.001 blocks. It DOES NOT set q/basis or reposition the carrier. Only then rebinds the exact captured source origin and publishes shallow. False means stay deep at the same position; no alternate landing. NAV owns smooth approach, automatic flight while anchoring is pending, and client prediction-baseline reset. Reconnect prewarm uses `nav.targetBasis()`, not a default replacement basis.

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

Parent common init after SourceLandmarks/activity/navigation dependencies; client terrain init before networking accepts mesh frames. Register these COMMON mixin FQCNs (parent controls JSON/package prefix):

* `io.github.mysticism.dimension.spiritworld.terrain.mixin.SpiritMeshCollisionMixin`
* `io.github.mysticism.dimension.spiritworld.terrain.mixin.SpiritMeshSneakMixin`

Movement collision is replaced only for players in `mysticism:spirit`, server AND predicted client, including flying. Carrier block-contact callbacks and carrier-Y void damage are disabled for these players; source dimensions/entities are untouched. Sneak ledge clipping uses mesh ground, not carrier AIR. Existing parent interaction/native-scene mixins remain required and are not duplicated.

## Implemented executable path

* Immediate loaded source capture around ANY entry location, including actual vanilla collision boxes and source biome tint/light; async `SourceLandmarks.region` reads only generated source terrain for the sliding view. No `setBlockState`, chunk ticket stamping, ledger activation or `TerrainState` initialization.
* Source tiles are retained under a 32768-tile window, refreshed body/floor first plus bounded cyclic refresh. Uniform fully solid equal-material octree compaction only outside seven blocks; near ~five blocks remains unit source models/shapes. No artificial bridges/floors. Unknown terrain stays unknown.
* Radius catalog discovery uses the actual `LandmarkStore.sourceRangePage` cursor, eight records per advance across source dimensions, not the old global-256 scan/rejection. Cluster slots use distortion, source-independent density-neutral priority, hysteresis, bounded importance and inverse-square proximity (inner-five-percent exact-near target always admitted). Current q is rechecked before activation; removed/stale medoids don't remain locked forever.
* Single bounded repository geometry reader publishes bounded representative geometry; four pages/32 ticks/128 cells per window yield to other regions rather than rejecting the whole feature or retrying its first page indefinitely. This is representative geometry, NOT complete whole-feature hydration.
* Shallow renders only its source-local window so unrelated semantically similar regions cannot crowd the initial scene. Deep projects local/target/representatives through CURRENT observer basis. Exact prewarmed targets get priority. Fading removal checks actual active cell distance and never removes near-player surfaces just because their semantic root is far away.
* Shared exact-overlap surface stitching eliminates duplicate coplanar same-shape cells, deterministic material dithering keeps collision identical, and affine model/shape deformation is shared by renderer and swept SAT physics. Broadphase eight-block buckets, bounded split sweep, sliding, stepping, jump and sneak behavior use actual meshes.
* Server/dimension/disconnect/respawn cleanup cancels owned async tasks and releases collision cache; reconnect rebuilds derived meshes from NAV's per-player source binding, never a frozen global save. Normal source blocks, inventory and stats are not changed. Old overlay/math helpers remain solely for existing historical tests; no active service calls them.

## Honest remaining limits / parent fix priorities

1. **No compile/live/GPU smoke executed here** per parent serialization request. Parent must compile against the merged source/NAV/render APIs and validate mixin injection, entry/exit/landing, flight collision, reconnect and Fast/Fancy/Fabulous.
2. **Ownership precision:** local source windows label samples with the retained owner, validate bounding bounds and actual mesh support, and confirm exact target ownership asynchronously. They do NOT yet carry an authoritative per-cell source ownership mask for every walking sample across curved/overlapping landmark masks. Parent/source followup must add a bounded exact owned-region mapping if strict cross-mask walking/exit authority is required. Do not equate an AABB test with full octree ownership.
3. **Landing depends on NAV's continuous q/basis alignment.** The strict gate intentionally refuses rather than snapping. Test that NAV reaches these tolerances and resets prediction baseline; changed/invalid targets remain deep. Target source confirmation can be delayed by embedding/source queues. Unloaded target snapshot can become stale before source edits are republished.
4. **Source streaming is finite:** complete initial near view, but loaded sparse windows/page/cell/material caps can omit farther or extremely diverse/detailed geometry. Representative geometry currently samples the first bounded page window rather than a resumable spatially prioritized stream through every page. No global record cap/starvation, but not complete huge-cave coverage.
5. **Fog-hidden activation is incomplete:** far removal is distance-guarded and faded; newly selected representatives can still appear/fade inside the opaque boundary in deep mode. Strong hidden-frontier activation needs a retained prepared-vs-active stage, not a false claim that all fading is fog-hidden.
6. **Smoothing is bounded/exact overlap only:** uniform octree seams match; exact coincident surfaces dither without changing collision. No general nonlinear seam/SDF blend across differently deformed intersecting regions. Visible holes/cliffs remain legitimate; there is no watertight-floor guarantee or safety floor.
7. **Model cases:** stairs/slabs/fences use real source blockstate shapes/models. Dynamic block entities use a collision-shaped stone-model fallback; fluid surfaces and entity-context-specific shapes (e.g. powder snow equipment) are not fully modeled. Slipperiness/sound/block-contact effects still use vanilla carrier behavior except suppressed carrier contacts; collision itself uses mesh. GPU failure status is visible, but no software terrain renderer exists.
8. **Rebuffering/performance:** shallow transforms don't upload unchanged cells, but source refresh/deep basis evolution can rebuild bounded VBOs and broadphase frequently. Limits are finite, not measured SMP/GPU performance. Up to 64 server sessions; excess refuses entry.

## Checks at checkpoint

Passed: `git diff --check` (run before commit); manual scoped review of current mesh transform/shape/target lifecycle and coordination with the provided independent NAV review. Not run: Gradle/javac, existing offline mains, live Minecraft, model endpoint, GPU/SMP. No new test batteries/scripts or migration machinery. Source/config/initializer ownership remained with parent/other workers.
