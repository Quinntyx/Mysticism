# Approved rebuild — navigation handoff

Authority: `docs/spirit-world-approved-design.md`, read completely; reviewed current sibling `docs/rebuild-{source,terrain,network,render}-handoff.md` directly. This is the approved per-observer custom-mesh rebuild, NOT the previous traversal/activity handoff. Scope: components, navigation service/mixins, evolver, predictor, traversal math and debug commands. Source owner now owns general activity/landmarks. No initializers, networking, terrain/render, JSON/resources/build files, dependencies, agents or Gradle runs changed/used.

## Actual APIs

`MysticismEntityComponents.SPIRIT_NAVIGATION` is registered for players (`ALWAYS_COPY`), `SpiritNavigation` implements CCA auto-sync:

```java
boolean active(); boolean deep();
String sourceDimension(); String landmarkId(); Vec3d sourcePosition();
boolean hasShallowTarget();
String targetDimension(); String targetLandmarkId(); BlockPos targetBlock();
Basis384f targetBasis(); // defensive snapshot; persisted with captured source pose
void enterDeep(); void shallow(String dimension, String id, Vec3d pos);
void setActive(boolean active);
void target(String dimension, String id, BlockPos pos);
void target(String dimension, String id, BlockPos pos, Basis384f sourceBasis);
void clearTarget();
```

**UNOWNED initial shallow source is valid:** `landmarkId=""`, actual dimension/coordinates, no fabricated ID. A shallow TARGET requires a real ID and an orthonormal captured source basis. The target embedding is an independent snapshot in `LatentAttunement.target()`. Component persistence accepts only current cheap model/profile stamps; incompatible semantic vectors are discarded, not archived/migrated. Normal blocks/stats/inventory are untouched. Original flight/no-gravity permissions are saved across logout/restart and restored on exit/dimension change/normal respawn.

`io.github.mysticism.navigation.SpiritNavigationService`:

```java
public static void init();
public static boolean enter(ServerPlayerEntity player);
public static boolean exit(ServerPlayerEntity player);
public static void enterDeep(ServerPlayerEntity player);
public static boolean deep(ServerPlayerEntity player);
public static Optional<SpiritScenePayload.Binding> binding(ServerPlayerEntity player);
public static boolean update(ServerPlayerEntity player, Vec3d physicalDelta); // evolver only, once/tick
public static boolean captureHere(ServerPlayerEntity player);
public static boolean captureSource(ServerPlayerEntity player, String dimension, BlockPos block, String requiredId);
public static void cancelCapture(ServerPlayerEntity player);
public static void captureTarget(ServerPlayerEntity player, String dimension, String landmarkId, BlockPos block, Vec384f embedding);
public static void captureTarget(ServerPlayerEntity player, String dimension, String landmarkId, BlockPos block, Vec384f embedding, Basis384f sourceBasis);
public static void touch(ServerPlayerEntity actor, ServerPlayerEntity target);
public static void anchorFromConcept(ServerPlayerEntity player); // ready-item position command only
```

Parent installs network `Navigation` by delegating `deep/binding/touch` to these actual methods. **Network authenticates mutual reach/alignment and BOTH `SpiritTerrainService.clearRay` views before calling touch.** Touch additionally rejects shallow/different-server/different-world/self, interpolates recipient basis over 20 ticks, preserves q/attunement, and cancels interpolation when actual movement resumes. Stationary authoritative basis sync still reaches predictor/render cache.

## Implemented integration

- Entry calls terrain `prepareEnter`, preserves exact source XYZ and yaw/pitch and uses those same carrier coordinates. Calls `setShallow(true)` after teleport. No `(0,128,0)`, entry floor stamping, safe-air substitution or shared projection frame.
- Terrain already owns asynchronous initial `SourceLandmarks.ensureSourceLocation`; navigation consumes its real resolved `sourcePosition` ID and direct `LandmarkStore.metadata(id)` once to establish q. **No duplicate entry extraction service/request.** Before resolution, binding has blank ID and an explicitly unavailable ZERO embedding; this is NOT used to evolve deep position. Ordinary unowned source geometry remains shallow and walkable. Double-jump/off-support deep transition is refused until a real source semantic anchor exists, with visible message. Explicit ready-item position is a valid debug concept anchor, not synthetic fallback.
- Shallow preserves basis/source-grid alignment and actual support. Vanilla jump retains shallow for up to 14 unsupported ticks; flight toggle or loss/change of confirmed supporting region enters automatic deep flight. No sneak/drop special controls, no motion freeze and no orbit/target mutation under the floor.
- Deep uses original `BasisIntegrator384f` movement-dependent rotation toward **target minus q**, fraction 0.30/block; chosen physical delta advances q at **96 blocks/semantic unit**. Stationary and >4-block teleport displacement do not inject travel. Server and client use `TraversalSteering` math; q is a LOCATION and must NOT be passed through unit-only `LandmarkProfiles.wrap`.
- Target capture uses actual async `SourceLandmarks.ensureSourceLocation`, rejects a mismatched requested owner, snapshots source-grid basis and embedding plus exact source-block offset; no tick model calls/joins. Generation guards prevent a cancelled/already-queued capture callback from overwriting a newer explicit item/personal/source target. Calls actual `prefetchTarget(..., captured, sourceBasis)`. Captured source basis is persisted and re-used on reconnect, not silently replaced with global/default axes.
- At q distance <.035, invokes actual `tryLandTarget` at that exact captured block. Failure stays deep nearby, with message and retry hysteresis; no alternative landing. Success rebinds shallow and restores captured basis/q, resets motion baseline. `/spirit leave` delegates exact current shallow `exit`; unavailable/obstructed/deep exits fail visibly.
- Fabric ALLOW_DAMAGE rejects damage to a deep player AND damage initiated by a deep player, preserving normal-world behavior. No duplicate Fabric attack/use guard was added: parent owns `SpiritInteractionGuard.init()`; held-item use, native item pickup/magnets and authenticated custom touch are untouched by this scope. Native living non-player spirit entities are rejected on spawn and discarded on load; source ghosts are not native entities. Normal source entities/ItemEntities are not touched.
- Disconnect, respawn, dimension change and shutdown clear transient motion/touch/pending-capture state. Source dimension unload cancels matching requests. Saved compatible q/basis/target/source pose remain component data.
- Predictor always refreshes `ClientSpiritCache.updateNavigation/updateObserver`, including at rest; no competing scene packet cache/renderer. World/player/disconnect changes reset predictor baseline. Initially unowned source does not predict fabricated semantic travel.

Debug controls: `/spirit [enter|leave|deep|capture]`; `/latent target here`; `/latent target at <dimension> <landmarkId> <x> <y> <z>`; `/latent target item <item>`; `/latent target personal`; `/latent show`; legacy `/latent set {basis,pos,attune} item <item>`. Item commands consume only ready current-model index values, never join/embed. Basis/position re-key commands reject shallow mode. Personal target is explicitly snapshotted, not live-followed.

## Parent-owned glue

1. Initialize source and terrain services before `SpiritBasisEvolver.init()` (which idempotently calls navigation init); retain `ClientLatentPredictor.init()` after client terrain initialization. Existing CCA entrypoint is sufficient.
2. Network currently already calls nav `touch` in its default adapter. To use the nav binding's explicit unavailable vector until real ownership is ready, use actual `SpiritProjectionService.install(new SpiritProjectionService.Navigation() { ... }, SpiritTerrainService::clearRay)`, delegating `deep/binding/touch` to the three methods above. Retain actual per-player terrain transport and both touch participants' `clearRay`. Binding/Ghost must permit blank unowned ID (network code now visibly does for Binding). Initialize parent-owned `SpiritInteractionGuard`; do not add a second guard here.
3. Add common mixin resource, for example `mysticism.navigation.mixins.json`, and reference it from Fabric manifest (NOT edited here):

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.mysticism.navigation.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["SpiritFlightToggleMixin", "SpiritNativeMobMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

Flight hook targets Yarn `ServerPlayNetworkHandler.onUpdatePlayerAbilities` TAIL, after vanilla server-thread guard. Native mob hook targets `ServerWorld.spawnEntity` HEAD. Terrain owns movement/collision mixin; no nav collision adapter or block overlay exists.
4. Parent owns native dimension/profile bump: current branch still has foundation `DIMENSIONS=256`, `NATIVE_DIMENSIONS=768`; network has requested native Nomic v2 document profile. Vector code is outside this scope; all nav storage/math use actual `EmbeddingSpace` constants.

## Concrete remaining integration/runtime limits

- Independent contract review: nav component and binding explicitly support initial UNOWNED blank IDs, and deep transition now waits for an actual source semantic anchor. Affine/local-box transport and matching 64-property materials are terrain/network-owned; real ghost pose/equipment/variant/player-profile transport and create-failure fallback are network/render-owned. Their latest sibling code visibly includes those appearance hooks; no out-of-scope edits or independent runtime-success claim here.
- **Terrain owner coordination:** current source lacks JOIN/session reconstruction for already-spirit saved players. Navigation persists source pose/basis and can resume movement, but cannot manufacture terrain sessions. Terrain must reconstruct its actual window/collision asynchronously from CCA on JOIN. Without that, shallow relog becomes deep/unavailable and meshes cannot reappear. No fake resume adapter was invented here.
- **Terrain owner coordination:** its fallback prewarm in tick currently passes `new Basis384f()`; use actual `nav.targetBasis()` now published. Navigation explicitly prewarms captured basis immediately at entry/capture and after session recreation, but parent must not rely on default-basis fallback winning event order. Terrain must also preserve requested captured basis when an existing same-ID/point prewarm is refreshed.
- Exact landing/source clearance, observed/generated-only cells, ownership validation, deformation and visible floor continuity are terrain-owned real hooks, not claimed independently verified. Native mob/deep damage/flight Mixin boot, simultaneous touch, CCA reconnect and live collision/jump behavior have NOT been exercised.
- Pending source discovery can fail/unavailable: no semantic fallback/deep travel is invented; player can remain on available shallow geometry and attempt exact exit. Recent pending capture is transient and may be lost on logout/shutdown. Callback publication verifies same live player/session.
- Vector work is bounded by native dimension and connected players; one pending debug capture/player, fixed 20-tick blend, no catalog enumeration/geometry hydration/model calls on movement tick. CCA q/basis and shallow pose reconcile every four ticks; these are not a measured wall-clock/packet-size guarantee. Terrain/network own their population/mesh/packet caps.
- Existing old prototype tests were left untouched; old orbit/archive assertions are not validation of this approved rebuild. No new test battery/migration machinery was added.

## Actual validation

`git diff --check` passed. Reviewed real sibling source/API and Yarn signatures with mapped `javap`. **No Gradle, compile, smoke, new tests, live Fabric/client/server/GPU or integrated model execution run** during concurrent implementation, per parent instruction. Parent owns serial merged compile/basic smoke; this document does not claim those passed.
