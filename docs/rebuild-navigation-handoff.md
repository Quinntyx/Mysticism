# Approved rebuild — navigation handoff

Authority: `docs/spirit-world-approved-design.md` and parent’s bounded follow-up review of initial commit `75d578d`. Scope remains navigation/components/evolver/predictor/debug controls. No initializer, terrain, networking, rendering, resource/build configuration, foundation, dependency or agent changes. Parent’s existing `SpiritInteractionGuard` remains unchanged.

## Follow-up fixes (this commit)

1. **Immediate freeflight is independent of embeddings.** `enterDeep` always activates deep mode, flight permissions and flying, even while source discovery is pending/failed. Only semantic integration, landing and touch require a real anchor. Unanchored client prediction is also disabled. Leaving a floor while walking immediately enters freeflight; an ascending vanilla jump retains up to 14 unsupported ticks of shallow grace.
2. **Actual floor identity matters.** Navigation compares `support.landmarkId()` with the shallow binding, not just the retained terrain window. Blank matches blank only. An unrelated floor immediately enters deep, without rebinding. Exit performs the same identity check when support exists. The established source mapping still bounds ordinary jump grace.
3. **Physical pose survives model reset.** Valid source dimension/XYZ, active/deep mode and original flight permissions load independently of the embedding stamp. Incompatible generated landmark bindings/target/basis metadata are discarded. A corrupt generated target does not erase valid physical coordinates. Existing vector/basis components discard incompatible generated values as before; no archives/reindexing or world/stat/inventory migration.
4. **Landing is a deep approach, not a snap.** Within `.035` semantic units (3.36 blocks), basis alignment proceeds over 40 accepted steps, subject to the terrain guard described below. Singular/antipodal interpolation jumps over `.1` axis-vector distance are refused visibly. Physical movement continues to advance q; q is never replaced with the captured target, and there is no final basis replacement. Acquisition requires <=`.0005` semantic residual (4.8 cm), matching actual target support, near-horizontal support normal, aligned basis and the terrain guard’s readiness approval. Without a registered guard, acquisition is disabled with visible status, not silently permitted.
5. **Prediction baseline:** synced/persisted `motionEpoch`, deep-mode comparison and semantic-readiness gating prevent sub-four-block mode/anchor corrections from being interpreted as movement. During approach, client prediction advances q without ordinary deep basis steering. Server acquisition also resets the evolver baseline.
6. **Native entity allowlist:** both `ServerWorld.spawnEntity` filtering and Fabric entity-load filtering allow `PlayerEntity` and `ItemEntity` only in `mysticism:spirit`. Native projectiles/vehicles/orbs/falling blocks and living mobs are rejected/discarded. Source worlds and client-only source ghosts are untouched. Vanilla item load/pickup/magnet paths are not intercepted; held-item interaction remains owned by parent’s existing guard.

## Actual APIs

`MysticismEntityComponents.SPIRIT_NAVIGATION` remains the registered `ALWAYS_COPY`, auto-synced player CCA component. Existing API:

```java
boolean active(); boolean deep();
String sourceDimension(); String landmarkId(); Vec3d sourcePosition();
boolean hasShallowTarget(); String targetDimension(); String targetLandmarkId();
BlockPos targetBlock(); Basis384f targetBasis(); // defensive snapshot
void enterDeep(); void shallow(String dimension, String id, Vec3d pos);
void setActive(boolean active);
void target(String dimension, String id, BlockPos pos);
void target(String dimension, String id, BlockPos pos, Basis384f sourceBasis);
void clearTarget();
```

Additional state: `boolean semanticReady()`, `boolean modelCompatible()`, `boolean landingApproach()`, `long motionEpoch()`; controlled readiness/approach setters. The cheap current model stamp applies to semantic state, not physical coordinates. Blank unowned shallow bindings are valid; shallow targets require a real ID and an orthonormal captured source basis.

`SpiritNavigationService` retains `init`, `enter`, `exit`, `enterDeep`, `deep`, `binding`, `update`, `captureHere`, `captureSource`, `cancelCapture`, both `captureTarget` overloads, `touch`, and `anchorFromConcept`. Terrain’s actual `prepareEnter`, `sourcePosition`, `support`, `setShallow`, `prefetchTarget`, `tryLandTarget`, `exit` and direct `LandmarkStore.metadata` are consumed; no duplicate initial source extraction or invented semantic fallback. Commands use ready index data / real async source discovery, never model joins on tick. Ordinary deep travel still uses original movement-dependent `BasisIntegrator384f` toward target minus q, at 96 blocks/semantic unit; q is a location, not a normalized text embedding.

### Parent/terrain coordination REQUIRED for landing and late discovery

New real common hook:

```java
public interface SpiritNavigationService.LandingSafety {
    boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
    boolean ready(ServerPlayerEntity player, String dimension, String landmarkId, BlockPos block);
}
public static void installLandingSafety(LandingSafety safety);
public static void anchorSource(ServerPlayerEntity player,
        SpiritTerrainService.SourcePosition source, Basis384f sourceBasis);
```

- Parent installs a terrain-owned `LandingSafety` adapter **only after terrain supplies real implementations**. `canAlign` must validate the proposed next frame against the current player body and the same render/collision geometry, preventing local clipping before navigation applies that basis. `ready` must require the exact captured source window, observed/current clearance and ownership, and a coherent already-visible collision/render transition. Do not return unconditional true or call a mutating landing method as a readiness query. False retains deep freeflight and physical movement.
- Terrain’s currently inspected `tryLandTarget` calls `setPosition(projected)` and publishes shallow immediately. **Parent/terrain must remove that correction before installing this hook.** Acquisition must preserve CURRENT physical carrier pose and bind its current pose to the validated source coordinates; preserve the visible/collision geometry rather than jumping to a root or inserting a safety floor. Navigation never changes q or sets the final basis to disguise that jump.
- `sourcePosition` is shallow-only. Terrain must call `anchorSource` after real async ownership discovery, including when the player already switched to unanchored deep flight, using the actual retained source pose and captured source-grid basis. Invoke in the same server-thread publication transaction as terrain’s semantic-frame update to avoid a frame/q mismatch. This method validates real metadata and source ownership, marks semantic readiness and preserves deep mode/flight; it does not launch another extraction request. Until wired, unanchored deep freeflight works but semantic travel remains unavailable. Parent/terrain must account for physical movement during pending discovery when choosing the corresponding source pose/frame; no fabricated embedding is permitted.
- Current sibling terrain has JOIN restoration from `nav.active/sourceDimension/sourcePosition` and reuses `nav.targetBasis` for target prewarm. Model reset now retains the physical inputs it requires. These remain terrain-owned paths, not independently runtime-verified here.

## Existing integration/config glue (unchanged)

1. Initialize source and terrain before `SpiritBasisEvolver.init()` (idempotently initializes navigation); retain `ClientLatentPredictor.init()` after client terrain setup. CCA registration already exists.
2. Network delegates `deep/binding/touch` through actual `SpiritProjectionService.Navigation`. Network authenticates mutual reach/alignment and both `SpiritTerrainService.clearRay` views before touch. Touch preserves q/attunement and interpolates recipient basis over 20 ticks; movement cancels it. Parent initializes its existing `SpiritInteractionGuard` once.
3. Common mixin resource/manifest registration is parent-owned, not edited here:

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

`SpiritFlightToggleMixin`: Yarn `ServerPlayNetworkHandler.onUpdatePlayerAbilities` TAIL, after vanilla thread guard. `SpiritNativeMobMixin`: `ServerWorld.spawnEntity` HEAD, now the explicit native allowlist despite its historical class name. Terrain owns collision/movement mixins.
4. Model/native-dimension/profile configuration remains parent-owned. All navigation storage/math use actual `EmbeddingSpace` constants; this worktree still has foundation `DIMENSIONS=256`, `NATIVE_DIMENSIONS=768` until parent’s configuration integration.

## Lifecycle/resource bounds

Disconnect/respawn/dimension exit/shutdown clear transient motion/touch/capture state; pending callbacks verify the live session/player/generation. Source unload cancels matching captures and moves affected shallow players into flight. Original ability permissions persist across relog and restore on exit/dimension change/normal respawn. One pending debug capture/player; fixed-size per-player state, 20-step touch / 40-step landing basis work, dimension-bounded vector operations. No catalog scans, geometry hydration, model IO, inference or joins in navigation’s movement tick. Terrain guard implementations must preserve that budget. Four-tick CCA reconciliation remains, not a measured packet/CPU guarantee.

## Exact validation and remaining limitations

Initial commit and this bounded pass: **no builds, Gradle, smoke tests, new test suite, existing test execution, live Fabric/GPU/server/model execution or agents**, as requested. Static inspection and `git diff --check` only; no claim of runtime success. Existing old orbit/archive tests were left unchanged and are not evidence for this design.

Follow-up closes unconditional flight refusal, unrelated-floor retention, physical-coordinate erasure and navigation’s q/basis landing snap. **Landing intentionally remains fail-closed until parent/terrain wires the real guard and no-position-correction acquisition.** Late deep source anchoring also requires the actual terrain ownership callback above. Singular landing alignments may fail visibly rather than force a discontinuity. Coherent render/collision streaming, reconnect model reset, jump/edge behavior, mixin boot, item pickup/magnets and multiplayer touch still need parent’s integrated runtime validation. Parent owns serial merged compile/basic smoke and follow-up merge.
