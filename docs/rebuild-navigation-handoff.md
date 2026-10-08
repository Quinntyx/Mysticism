# Approved rebuild — navigation handoff

Authority: `docs/spirit-world-approved-design.md`, prior bounded navigation reviews, and parent’s current support-acquisition/effective-snapshot correction. Scope: navigation, its CCA component, flight packet adapter and existing predictor only. No initializer, terrain, networking payload, renderer, resource/build configuration, foundation or dependency changes. No agents spawned; coordinated directly with the existing terrain session.

## Current continuation

### Actual-support flight-off

`SpiritFlightToggleMixin` now routes the actual Yarn ability packet through `SpiritNavigationService.onFlightToggle(player, packet.isFlying())`.

- True enters deep/freeflight as before. False while deep records a **current-support** request, not a remembered-entry or explicit-target landing. Flight remains enabled until the real terrain operation succeeds.
- One session-local request/player, 200-tick deadline; repeated packets cannot restart its budget or duplicate the request. A guarded source-grid alignment takes at most 40 accepted steps; already-aligned support can acquire immediately. Source/window/owner/grid change, retarget, touch, flight-on, capture, logout, respawn, dimension exit/unload and shutdown cancel/clear the transient request. Saved/wire mode flags do not resume an old request after reload.
- Every pending movement tick advances q only by **actual physical delta in the current basis**, with finite/teleport bounds. It does not converge to the support coordinate, replace q, change position or mutate captured attunement. Client prediction has a separate `supportApproach` path using the same ordinary translation, not captured-target homing.
- Navigation consumes the sibling terrain’s **real** `currentSupport` / `acquireCurrentSupport(player, expected)` APIs. It retains stable producing-window identity, exact owner, dimension and source grid. It checks each proposed basis step for discontinuity and calls the real `SpiritTerrainService.canAlign` before applying it. Final acquisition rechecks real contact, source ownership revision, source body/floor proof, projected 1 mm alignment and whole-frame collision/render continuity in terrain.
- Success rechecks terrain’s resulting source mapping, binds those **current exact feet**, clears flight and resets the motion baseline. Captured target/dimension/basis/vector remain unchanged. No safety floor, target teleport, entry rebinding or `setShallow(true)` shortcut. If terrain cannot validate the support, the actor stays deep and sees pending/expiry/cancellation status.

This now has a real implementation for arbitrary representative support. It is **not runtime-confirmed**. A normal successful handoff still depends on the terrain-owned proof/transition becoming ready; nav does not override a refusal. An actor can keep moving during alignment; stationary-only or centimeter-position targeting is not imposed on this path.

### Effective vectors, frozen snapshots and precise source feet

Both initial source q and newly captured points now call `SpiritActivityService.effectiveEmbedding(server, metadata).vector()`, adding the source-grid offset from the header anchor at 96 blocks/semantic unit. Neither uses the raw header base in navigation anymore. A capture snapshots the effective vector when the async capture completes on the server thread, then clones/stores it once. It never continuously follows activity changes. A real captured ZERO coordinate is not overwritten by initial default-target initialization.

`captureHere` takes actual current `SpiritTerrainService.sourcePosition` while shallow, or exact player feet in the real source world. Only the ownership-discovery seed is floored; captured X/Y/Z remain doubles. Thus slab-standing Y is not changed to a blocked integer. CCA stores `tfx/tfy/tfz`, validated independently; legacy target tags migrate to the old centered-XZ/integer-Y convention. Physical source pose/mode/permissions still survive an incompatible model reset; incompatible generated target/vector/basis/binding data does not.

The real terrain now exposes matching `Vec3d` prefetch/readiness/acquisition overloads and restores/prewarms with `nav.targetPosition()`. Old `BlockPos` APIs remain for explicit integer debug targets. `LandingSafety` also has an exact-foot `Vec3d ready` overload: the default only delegates for an **exact** legacy centered-XZ/integer-Y point and otherwise fails closed; the actual terrain adapter overrides it. No fractional-foot truncation is hidden in an adapter.

### Strict captured landing gate

The earlier held-W bug was real: source-basis-only translation recreated a perpendicular residual and repeatedly cancelled alignment. Existing shared `approachStep` instead uses bounded movement-driven convergence of **all** residual components: <=movement/96 and <=25% of the residual per step. No stationary drift, q replacement, normalization or captured-target update. Captured-target deep translation also eases before the alignment band while retaining full movement-dependent basis pursuit.

`.0005` is now **alignment entry only**, not successful landing tolerance. Source-grid alignment proceeds over 40 accepted guarded steps and movement convergence continues even after step 40. Navigation’s final semantic gate is `2e-6`; actual terrain has the matching strict target-vector gate and <=1 mm physical source alignment.

Static reasoning, not a test: `approachStep` stops only when residual <`1e-8`, well inside `2e-6`. With ordinary held-W movement >=.012 blocks at entry, its 25% cap applies; `.0005 * .75^20 < 2e-6`. Therefore it does not intentionally stop at the looser band. This is conditional on accepted terrain transitions/real movement and ignores float rounding; no numerical/runtime claim was measured. Singular source-basis transitions or changed current effective placement can legitimately remain deep rather than snap/follow the frozen snapshot.

## Actual APIs and sibling integration

Existing registered `MysticismEntityComponents.SPIRIT_NAVIGATION` remains `ALWAYS_COPY`, auto-synced. Additions:

```java
boolean supportApproach();
void setSupportApproach(boolean value);
Vec3d targetPosition();
void target(String dimension, String id, Vec3d exactFeet, Basis384f sourceBasis);
```

Existing `active/deep/semanticReady/modelCompatible/landingApproach/motionEpoch`, physical source getters, `targetBlock/targetBasis`, all `BlockPos` target setters and ability persistence remain. Target basis and terrain support vectors/bases are defensive snapshots.

```java
public static void SpiritNavigationService.onFlightToggle(ServerPlayerEntity player, boolean flying);
public static void SpiritNavigationService.captureTarget(ServerPlayerEntity player,
    String dimension, String id, Vec3d exactFeet, Vec384f embedding, Basis384f sourceBasis);

public interface SpiritNavigationService.LandingSafety {
    boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
    boolean ready(ServerPlayerEntity player, String dimension, String id, BlockPos point);
    // default exact-foot overload; terrain overrides it for fractional coordinates
    boolean ready(ServerPlayerEntity player, String dimension, String id, Vec3d exactFeet);
}
```

Consumed **existing-session terrain implementation**, inspected directly:

```java
SpiritTerrainService.currentSupport(player); // Optional<WalkSupport>
SpiritTerrainService.acquireCurrentSupport(player, expectedSupport); // boolean
SpiritTerrainService.cancelCurrentSupport(player); // clears bounded proof on cancellation/lifecycle
// WalkSupport: landmarkId, sourceDimension, sourcePosition, sourceBasis,
// sourceCoordinate, normal, windowIdentity, ownershipRevision
SpiritTerrainService.prefetchTarget(player, dimension, id, Vec3d feet, vector, sourceBasis);
SpiritTerrainService.landingReady(player, dimension, id, Vec3d feet);
SpiritTerrainService.tryLandTarget(player, dimension, id, Vec3d feet);
```

Parent must merge matching terrain commits `ae2da9c982e7a371a2fd9223fbd4cb9d05c1aa54` / `a59a24588b7231a65460d63bed535b33e2dc6c67` and this nav continuation together before compile. This older nav worktree intentionally does not contain the sibling terrain changes; no stub or out-of-scope transplant was added. Terrain owns `init()` installing both readiness overloads, source-region proof lifetime, cached/live AIR/SOLID/shape/foot checks and scene publication. Its current `tryLandTarget` preserves carrier position (no old `setPosition(projected)` correction). Source ownership publication calls `anchorSource(player, actualSourcePose, capturedSourceBasis)` in its real publication transaction, including for already-deep actors. Navigation keeps pending support prediction mode across that real late discovery.

## Existing integration/config glue

No new initializer or mixin/config entries in this continuation. Initialize actual source and terrain before `SpiritBasisEvolver.init()` / navigation; client predictor remains after client terrain. Parent owns all config/model/native-dimension values, manifests, resources and initializer glue. Existing common mixin package/resource:

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

Flight adapter stays Yarn `ServerPlayNetworkHandler.onUpdatePlayerAbilities` TAIL after the vanilla thread guard. Existing native spawn/load allowlist still permits `PlayerEntity` and `ItemEntity`, not native mobs/projectiles/etc. Parent’s held-item interaction guard, mutual-reach/alignment/clear-ray touch authentication and client-only source ghosts are unchanged.

## Bounds, exact validation and limitations

Navigation adds fixed-size state/player, one current-support/guard/acquisition query per pending tick, a 200-tick request lifetime and <=40 accepted dimension-bounded basis steps. No navigation catalog scans, model IO/inference/joins or model callbacks on movement tick. Terrain’s transitive bounded source proof/gathering and scene work remain terrain-owned and were not benchmarked here. Four-tick authoritative CCA reconciliation remains; no measured packet/CPU guarantee.

This continuation: **no tests, test additions, Gradle/build, smoke, live Fabric/GPU/server/model execution or spawned agents** as instructed. Exact validation: static production-source/API review plus `git diff --check`. Existing historical orbit/archive tests are unchanged and not evidence for this design.

Remaining limitations: runtime convergence/float tolerance, scene/body continuity while walking, async ownership readiness, slab acquisition, reconnect/model-reset reconstruction, mixin boot and server/client prediction need parent’s integrated validation. Impossible/changed/unready ownership or geometry remains deep, with no substitute teleport/floor. Singular alignments are refused. The 200-tick request can expire before slow terrain proof completes. A changed effective placement can invalidate an old frozen capture; nav will not quietly update it. Parent owns merged compile/client compile/basic smoke and final merge.
