# Terrain rebuild handoff — observer-local meshes

Authority: `docs/spirit-world-approved-design.md`, read completely. This supersedes the old frozen-frame/block-overlay prototype. One bounded implementation pass; no Gradle, new tests/dependencies, source-world writes, global saved projection, or safety floors. Parent owns integration and runtime smoke.

## Checkpoint and bounded independent-review follow-up

Base mesh checkpoint: `13ea4db22273304d9855df39292d612e47abd97d`. Independent review supplied by parent identified five TERRAIN findings (the SOURCE overlay-reconciliation finding belongs to the source/activity worker). The bounded follow-up implements: current—not captured—target geometry with versioned exact destination ownership proof; no discovery re-anchoring; live source capture precedence; conservative continuous swept geometry/body guard plus overlap depenetration; and reserved nearest-region admission with stale-region fading. `init()` now installs the REAL NAV LandingSafety and late-source anchor callback. No unconditional success guard, q/basis snap, alternate safe arrival or new tests. Compilation/runtime are still parent-owned and not performed here.

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
public static boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
public static void onSourceAnchor(SourceAnchorListener listener);
// interface SourceAnchorListener:
// void anchor(ServerPlayerEntity player, SourcePosition source, Basis384f capturedSourceGrid);
```

Nested public records: `Support(String landmarkId, Vec384f embedding, Vec3d center, Vec3d normal)` (defensive vector copies); `SourcePosition(String dimension, Vec3d position, String landmarkId)`.

**Projection consumers:** q and peer/item positions are non-unit LOCATIONS. Use `project(player, rawLocation)` or `projectionFrame(player).project(coordinate(rawLocation))`, NOT `LandmarkProfiles.wrap(rawLocation)`; the latter's UNIT profile is intentionally incompatible with the coordinate profile. Each frame snapshots CURRENT q/basis and that player's physical carrier origin. No server-global frame overload remains.

**Navigation:** prepare in source world before teleport; initial capture does not wait for extraction/embedding readiness. Call `setShallow` when mode changes. Shallow retains source-grid alignment; actual support reports the mesh owner. `sourcePosition` fails for outside retained bounds, unknown foot samples, removed owner, or a different grounded owner. Alias IDs are resolved through the current repository. Normal exit validates body clearance and materializes at CURRENT source coordinates, never an old entry/spawn substitute.

**Landing correction coordinated with NAV review:** NAV must continuously approach captured q and basis BEFORE `tryLandTarget`. `landingReady` requires exact source-owner confirmation plus a CURRENT geometry-version proof of every body/foot voxel (not header bounds), complete 12³ source read, unchanged current destination versus captured guidance, q error <=2e-6 semantic units, and summed basis squared error <1e-8. The proof reader stops once exact footprint ownership is known; it does not hydrate all remote pages just to land. Target geometry uses the current effective embedding and does not occupy/suppress a normal representative slot. Invalid/unconfirmed target geometry is not drawn as a fabricated floor. `tryLandTarget` rechecks current source body/floor when loaded (cached exact source samples otherwise), publishes current target geometry, requires actual target-owned mesh ground contact, and requires projected target/carrier difference <=.001 blocks. It DOES NOT set q/basis or reposition the carrier. Only then rebinds the exact captured source origin and publishes shallow. False means stay deep at the same position; no alternate landing. NAV owns smooth approach, automatic flight while anchoring is pending, and client prediction-baseline reset. Reconnect prewarm uses `nav.targetBasis()`, not a default replacement basis.

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
* Single bounded repository geometry reader publishes bounded representative geometry; four pages/32 ticks/128 cells per window yield to other regions rather than rejecting the whole feature or retrying its first page indefinitely. This is representative geometry, NOT complete whole-feature hydration.
* Eight normal representative regions plus one RESERVED closest region are bounded; stale unselected regions fade even if projection is within 80, except actual support/body-adjacent surfaces. Capacity deferrals requeue instead of dropping the exact-near choice; closest geometry renders before background representatives. Shallow renders only its source-local window so unrelated semantically similar regions cannot crowd the initial scene. Deep projects local/target/representatives through CURRENT observer basis. Exact prewarmed targets get priority. Fading removal checks actual active cell distance and never removes near-player surfaces just because their semantic root is far away.
* Shared exact-overlap surface stitching eliminates duplicate coplanar same-shape cells, deterministic material dithering keeps collision identical, and affine model/shape deformation is shared by renderer and swept SAT physics. The conservative affine endpoint-AABB envelope rejects any ambiguous continuous sweep through the body before publishing BOTH render/collision frames; unsafe NAV basis proposals are refused before application. Existing penetrations receive bounded SAT separation corrections, not ignored contacts. A held frame cannot be used to approve landing, and unsuccessful shallow-frame publication rolls back the mapping. Broadphase eight-block buckets, bounded split sweep, sliding, stepping, jump and sneak behavior use actual meshes.
* Server/dimension/disconnect/respawn cleanup cancels owned async tasks and releases collision cache; reconnect rebuilds derived meshes from NAV's per-player source binding, never a frozen global save. Normal source blocks, inventory and stats are not changed. Old overlay/math helpers remain solely for existing historical tests; no active service calls them.

## Honest remaining limits / parent fix priorities

1. **No compile/live/GPU smoke executed here** per parent serialization request. Parent must compile against the merged source/NAV/render APIs and validate mixin injection, entry/exit/landing, flight collision, reconnect and Fast/Fancy/Fabulous.
2. **Ownership precision:** local source windows label samples with the retained owner, validate bounding bounds and actual mesh support, and confirm exact target ownership asynchronously. They do NOT yet carry an authoritative per-cell source ownership mask for every walking sample across curved/overlapping landmark masks. Parent/source followup must add a bounded exact owned-region mapping if strict cross-mask walking/exit authority is required. Do not equate an AABB test with full octree ownership.
3. **Landing depends on NAV's continuous q/basis alignment.** The strict gate intentionally refuses rather than snapping. NAV currently captures `header.baseEmbedding()` while terrain correctly places at `SpiritActivityService.effectiveEmbedding`; parent/NAV must capture the effective vector at capture time if visited-source targets should initially match, while keeping that snapshot frozen afterward. Our 2e-6-unit/1mm gate is stricter than NAV's 0.0005-unit acquisition band; controller convergence can stall rather than guarantee arrival. BlockPos targets also lose fractional slab/stair foot heights. No quiet safe substitute is provided. Test that NAV reaches these tolerances and resets prediction baseline; changed/invalid targets remain deep. Target source confirmation can be delayed by embedding/source queues. Unloaded target snapshot can become stale before source edits are republished.
4. **Source streaming is finite:** complete initial near view, but loaded sparse windows/page/cell/material caps can omit farther or extremely diverse/detailed geometry. Normal active representative windows do not yet perform automatic full source-revision refresh; local loaded refresh and target proof do, but activity-driven growth elsewhere can remain visually stale until reselection/reentry. Representative geometry currently samples the first bounded page window rather than a resumable spatially prioritized stream through every page. No global record cap/starvation, but not complete huge-cave coverage.
5. **Fog-hidden activation is incomplete:** removal is faded and preserves actual immediate support/body, but close-slot admission must retire stale projected-near regions and new representatives can appear/fade inside the opaque boundary in deep mode. Strong hidden-frontier activation needs a retained prepared-vs-active stage, not a false claim that all fading is fog-hidden.
6. **Smoothing follow-up implemented:** overlapping representative borders now sample a smooth bounded height-displacement field (three-meter border band, <=12cm at every affine corner). Source-local meshes and the near-five-meter patch remain undeformed, with smooth onset to eight meters. A determinant guard prevents inverted/collapsed cells. The SAME resulting cell columns go to model rendering, transport and SAT. Dithering is deterministic and restricted to equal unit collision cubes with opaque ordinary-model palettes; stairs/translucent/contextual shapes don't exchange skins. This is an inexpensive affine approximation, not a nonlinear SDF, watertight triangulation, or general cliff-filling operation. Visible holes/cliffs remain legitimate; there is no watertight-floor guarantee or safety floor.
7. **Model cases:** stairs/slabs/fences use real source blockstate shapes/models. Unloaded snapshots still approximate light/tint and unknown neighbor/entity context; they no longer overwrite accurate loaded captures. Dynamic block entities use a collision-shaped stone-model fallback; fluid surfaces and entity-context-specific shapes (e.g. powder snow equipment) are not fully modeled. Slipperiness/sound/block-contact effects still use vanilla carrier behavior except suppressed carrier contacts; collision itself uses mesh. GPU failure status is visible, but no software terrain renderer exists.
8. **Rebuffering/performance:** shallow transforms don't upload unchanged cells, but source refresh/deep basis evolution can rebuild bounded VBOs and broadphase frequently. Conservative swept-AABB holding can refuse safe tilted/rotating changes near the body; move clear rather than teleporting or letting a surface pass through. Exact ownership proof can be delayed/restarted by concurrent repository mutation. Limits are finite, not measured SMP/GPU performance. Up to 64 server sessions; excess refuses entry.

## Checks at checkpoint

Passed: `git diff --check` before both commits; bounded source-level corrective pass against the supplied independent six-finding review and NAV follow-up `603bf9b` APIs. Source finding 1 is explicitly outside terrain scope and still requires source/activity reconciliation; not assumed solved. Not run: Gradle/javac, existing offline mains, live Minecraft, model endpoint, GPU/SMP. No new test batteries/scripts or migration machinery. Source/config/initializer ownership remained with parent/other workers.
