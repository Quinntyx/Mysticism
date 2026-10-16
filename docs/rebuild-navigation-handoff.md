# Approved rebuild — navigation handoff

Authority: `docs/spirit-world-approved-design.md`, prior bounded navigation reviews, and parent’s current support-acquisition/effective-snapshot correction. Scope: navigation, its CCA component, flight packet adapter and existing predictor only. No initializer, terrain, networking payload, renderer, resource/build configuration, foundation or dependency changes. No agents spawned; coordinated directly with the existing terrain session.

## Retained walk-request acceptance repair

The retained `b1d3ee7` support-pivot re-anchor and adaptive `SupportAlignment` schedule remain intact.
Review found a second, production guard failure: a supported floor tilted only 5 degrees has a
world AABB extending about .0175 blocks into the strict body even though its affine collision is
clear. Every adaptive subdivision includes that old AABB, so the old `transitionClear` rejected
all progress until the 200-tick walk deadline.

`MeshCollision.transitionClear` now uses endpoint AABB union only as broad phase. For each
ambiguous moving collision box, a fixed separating plane must separate the strict body from
**both** endpoint affine solids. This certifies their convex hull, which contains every vertex
throughout linear affine interpolation. If no plane certifies the interval, it is subdivided;
clear sampled poses never grant acceptance. Work is bounded to 256 interval checks and depth 16
per collision shape; exhaustion fails closed. Endpoint penetration still refuses a transition.
The endpoint-only five-block locality shortcut was removed because distant endpoints can sweep
through the player. No floor/contact whitelist, body-tolerance increase, teleport or q snap was
introduced. Ownership, per-player projection, collision/render geometry and embeddings are unchanged.

Regression coverage in the canonical main-based runtime runner:
- `MeshTransitionClearanceTest`: positive/negative 5-degree floor tilts and slabs with real SAT
  ground contact; full endpoint convex hull overlap requiring subdivision; safe affine side-wall
  motion; intermediate crossing with clear endpoints and midpoint; shear, raising floors, added
  collision and shape changes; seeded accepted-sweep clearance oracle.
- `WalkRequestAcceptanceTest`: actual production placement/pivot/blend plus `transitionClear`
  throughout a support alignment at ordinary and near-world-border carrier coordinates. The
  alignment reaches source-grid/acquisition geometry before expiry while preserving ground/body
  clearance, rather than relying solely on the older simulated increment-limit guard.

Verification: canonical `./gradlew --no-daemon --console=plain build` discovers six runtime mains
(including all four existing embedding suites), runs packaging/runner fixtures and verifies the
remapped production jar. An isolated build-directory recompilation of the original `b1d3ee7`
guard confirmed that both new regressions fail against it (the walking alignment stalls for all
200 ticks); no tracked source was rolled back for that check. Live player/component state, async source ownership/proof publication,
Minecraft launch, render feel and real model inference remain **unverified**. Very tight or complex
clear sweeps may still fail closed at the interval-work limit; no live performance profiling was
performed. The historical continuation below records the earlier integration work, not these tests.

## Current continuation

### Actual-support flight-off

`SpiritFlightToggleMixin` now routes the actual Yarn ability packet through `SpiritNavigationService.onFlightToggle(player, packet.isFlying())`.

- True enters deep/freeflight as before. False while deep records a **current-support** request, not a remembered-entry or explicit-target landing. Flight remains enabled until the real terrain operation succeeds.
- One session-local request/player, 200-tick deadline; repeated packets cannot restart its budget or duplicate the request. A guarded source-grid alignment takes at most 40 accepted steps; already-aligned support can acquire immediately. Source/window/owner/grid change, retarget, flight-on, capture, logout, respawn, dimension exit/unload and shutdown cancel/clear the transient request. Saved/wire mode flags do not resume an old request after reload.
- **Per-player touch independence:** an incoming deep touch from another player no longer cancels the recipient's own pending walk request, landing approach or in-progress blend. `TouchReception` (pure policy, regression-tested without a booted game) rejects the touch while the recipient carries any of those own intents; the initiator's per-actor throttle still applies and the approved blend-over-one-second mechanic is unchanged for a free deep recipient. Movement, expiry and the recipient's own actions keep cancelling intents normally, so one player can never silently trap another in deep flight by repeatedly re-touching during its own mode transition.
- Every pending movement tick advances q only by **actual physical delta in the current basis**, with finite/teleport bounds. It does not converge to the support coordinate, replace q, change position or mutate captured attunement. Client prediction has a separate `supportApproach` path using the same ordinary translation, not captured-target homing.
- Navigation consumes the sibling terrain’s **real** `currentSupport` / `acquireCurrentSupport(player, expected)` APIs. It retains stable producing-window identity, exact owner, dimension and source grid. It checks each proposed basis step for discontinuity and calls the real `SpiritTerrainService.canAlign` before applying it. Final acquisition rechecks real contact, source ownership revision, source body/floor proof, projected 1 mm alignment and whole-frame collision/render continuity in terrain.
- Success rechecks terrain’s resulting source mapping, binds those **current exact feet**, clears flight and resets the motion baseline. Captured target/dimension/basis/vector remain unchanged. No safety floor, target teleport, entry rebinding or `setShallow(true)` shortcut. If terrain cannot validate the support, the actor stays deep and sees pending/expiry/cancellation status.

This now has a real implementation for arbitrary representative support. It is **not runtime-confirmed**. A normal successful handoff still depends on the terrain-owned proof/transition becoming ready; nav does not override a refusal. An actor can keep moving during alignment; stationary-only or centimeter-position targeting is not imposed on this path.

### Deep walk locality while moving through the projection

A deep-flying player who requests a walk is still moving: descending, grazing slopes and crossing
several projected source localities before settling. `DeepWalkAttachment` (pure policy, no Minecraft
types) now governs which locality the pending request attaches to in `attemptSupport`:

- Attachment and final acquisition have **different** normal gates. The contact must be real
  projected ground (`normal.y > .3`) and its **source-space** normal must identify a floor
  (`sourceNormal.y >= .99`). `TerrainMeshFrame.Cell.sourceNormal` pulls the contact covector back
  through the same accepted affine cell that collision/rendering use: normalized dot products
  with `axisX/size.x`, `axisY/size.y`, `axisZ/size.z`. This works for sheared and compacted cells;
  inverse position math would be wrong for normals. A tilted horizontal source floor now starts
  alignment even when its projected `normal.y < .99`. A source wall/edge graze remains `WAIT`,
  including a wall distorted into an apparently horizontal projected surface.
- The original projected `.99` check remains in terrain's **final acquisition** transaction, along
  with the fully aligned source basis, identity/revision, body/floor proof and continuous scene
  checks. Starting alignment is not permission to land. The reviewed pre-alignment `.99` gate
  deadlocked source floors: pending requests suppress ordinary deep basis evolution, and WAIT
  returned before guarded source-grid alignment could improve the measured projected normal.
- While the player keeps physically moving (per-tick delta above the shared movement epsilon), a
  changed contacted locality is `FOLLOW`ed: the request re-attaches to the locality actually under
  the player (window identity, landmark, source dimension, grid basis) instead of cancelling, so a
  descent across representative windows no longer produces repeated "support window changed"
  failures. `FOLLOW` restarts only the bounded 40-step grid alignment from the current basis; the
  200-tick request budget, attunement gate, discontinuity guard, `canAlign` and the full
  `acquireCurrentSupport` revalidation are unchanged.
- Only a locality change under a settled player cancels the request (terrain genuinely changed
  underfoot; a fresh request must validate the new support), preserving the previous cancel
  message and semantics. Non-finite deltas count as settled.

Regression coverage: `DeepWalkAttachmentTest` checks separate attachment/acquisition boundaries,
all locality decisions, invalid normals and evolving projected normals. `DeepWalkContactPremiseTest`
drives the real basis blend, per-step discontinuity check, swept-AABB ground query and continuous
transition/body guards from a 35-degree tilted source floor to the source grid. It crosses to a
second locality before the normal is acquisition-compatible, follows it without WAIT/cancellation,
and completes 40 accepted steps on that locality. Other cases reject an upward-projected source
wall, exercise covector pullback with shear/nonuniform source size, and refuse an obstructed
transition. These are numerical/geometry regressions, not a live player/session acquisition test.

#### Retained-unit validation (source-floor gate repair)

Canonical command: the installed cached Gradle 8.13 binary with
`--no-daemon --console=plain build`, in the owned checkout via `tmux-jobs`, original pane `%2138`.
Retained build handle: session `$11`, window `@1381`, pane `%2156`, run
`553d6451a2d943f088755b6df9b9bb4f`. **Exit 0, BUILD SUCCESSFUL**.

- Common and client compilation, `runtimeSelfTest` discovery of all **six** main-based suites:
  deep-walk geometry/alignment **293** checks, deep-walk attachment **47** checks;
  embedding callback isolation **28**, engine safety **184**, persistence **90**, pipeline **135**.
- `buildSelfTest` packaging/descriptor/runner checks and `verifyProductionJar` passed; artifact
  `build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` passed.
- Existing missing API Guardian annotation and mixin/deprecation warnings remain nonfatal.
  Embedding persistence's malformed-data warnings/stack traces are expected rejection fixtures.
- No live Fabric player tick, client/server launch, renderer/GPU or real model inference performed.
  Terrain's real acquisition transaction still requires source proof/ownership and safe geometry;
  these tests do not prove it completes on arbitrary gameplay terrain. Conservative source edge
  contacts can wait, and blocked/slow proof/alignment can still exhaust the unchanged 200-tick budget.
- `WalkSupport` adds server-local `sourceNormal`; wire/storage formats, prediction modes, independent
  per-player projection, source octree ownership, captured attunement and native v2 embeddings are
  unchanged. No software/models installed, no agents delegated, no dev/main/worktree mutations.

The remaining sections below retain the earlier navigation handoff's historical integration notes;
its older no-test/build limitations do not describe the retained-unit validation just listed.

### Effective vectors, frozen snapshots and precise source feet

Both initial source q and newly captured points now call `SpiritActivityService.effectiveEmbedding(server, metadata).vector()`, adding the source-grid offset from the header anchor at 96 blocks/semantic unit. Neither uses the raw header base in navigation anymore. A capture snapshots the effective vector when the async capture completes on the server thread, then clones/stores it once. It never continuously follows activity changes. A real captured ZERO coordinate is not overwritten by initial default-target initialization.

`captureHere` takes actual current `SpiritTerrainService.sourcePosition` while shallow, or exact player feet in the real source world. Only the ownership-discovery seed is floored; captured X/Y/Z remain doubles. Thus slab-standing Y is not changed to a blocked integer. CCA stores `tfx/tfy/tfz`, validated independently; legacy target tags migrate to the old centered-XZ/integer-Y convention. Physical source pose/mode/permissions still survive an incompatible model reset; incompatible generated target/vector/basis/binding data does not.

The real terrain now exposes matching `Vec3d` prefetch/readiness/acquisition overloads and restores/prewarms with `nav.targetPosition()`. Old `BlockPos` APIs remain for explicit integer debug targets. `LandingSafety` also has an exact-foot `Vec3d ready` overload: the default only delegates for an **exact** legacy centered-XZ/integer-Y point and otherwise fails closed; the actual terrain adapter overrides it. No fractional-foot truncation is hidden in an adapter.

### Natural resting landing

Deep flight no longer hovers forever above available owned terrain. While deep, navigation measures real mesh contact (`MeshCollision.ground`) each movement tick:

- Natural landing arms only after the player has actually been airborne during this deep stretch (`Session.airborne`), so a standing flight toggle is never immediately reversed; any ascending tick resets the rest window.
- After `NaturalLandingPolicy.REST_TICKS` (10) consecutive resting ticks — near-horizontal ground normal (`>= .99`, matching walk acquisition), no ascending motion — and only when `SpiritTerrainService.currentSupport` is really present, the shared validated support approach begins (`naturalSupport` flagged). It uses the identical `beginSupportApproach`/`attemptSupport`/`acquireCurrentSupport` path as an explicit walk request: no q/basis/position snapping, same cancellation and expiry contract.
- Unknown/unowned geometry below keeps free flight. A naturally triggered approach that expires reports its own expiry text and re-arms only after the player leaves the ground again (`endSupportApproach` clears the airborne arm), so failures cannot retry-loop while resting. Touch basis blending and a captured-target landing approach are never diverted.

Policy constants live in `NaturalLandingPolicy` (pure, regression-covered); the physical resting-contact premise is regression-covered against the real swept-AABB index, including faded (opacity 0) terrain and steep slopes refusing the `.99` gate.

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
// sourceCoordinate, projected normal, sourceNormal, windowIdentity, ownershipRevision
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

## Approach/arrival completion (walk-approach-completion continuation)

Runtime defect: the guarded source-grid alignment only STARTED once `currentSupport` existed — i.e.
once the body was already within the 0.15-block contact band. That is exactly the state where the
terrain transition guard must refuse any rotation whose swept render motion reaches the body, and
the old fixed-anchor/(acceptedStep+1)/40 schedule FROZE on the first refused step (a refused step
re-proposed an identical basis forever). Every walk request taken with a genuinely rotated deep
basis therefore expired without arriving, and retries failed the same way. Fix, preserving the
approved contracts (q advances only by real movement; no snapping; acquisition strictly through the
unchanged contact query plus `acquireCurrentSupport`; singular alignments refused):

- `SpiritTerrainService.approachSupport(player, depth)`: the SAME owned/producing-window/contact
  rules as acquisition, run with a deeper downward reach (nav uses 24) and without the current-body
  ownership check (the body is not there yet). It grants alignment/prewarm authority ONLY — nothing
  is acquired from it, and the real arrival still commits exclusively at validated contact.
- `MeshCollision.ground(body, index, depth)`: the resting-contact query parameterized by reach; the
  existing `.15` behavior is byte-identical through the same helper.
- `attemptSupport` now aligns against the support discovered BELOW a descending actor, so the
  guarded rotation and the walk-proof prewarm happen while the near-surface cells are still clear of
  the body; when real contact appears, an already-aligned approach acquires immediately.
- Refused guarded steps RE-PLAN (`SupportApproachAlignment`): re-anchor at the live basis and halve
  the pace (bounded at 1/5120) so the retry's swept motion fits under the guard. Every
  guard-validated, continuity-checked ACCEPTED step recovers the pace toward full (capped at the
  approved 40-accepted-step schedule), so transient refusals never permanently cripple the remaining
  request budget — three refusals strand the pace at 1/320 only if nothing is ever accepted again.
  Each epoch still converges to the EXACT destination basis the strict acquisition gate requires.
  The old schedule's deadlock and the permanent-ratchet expiry are both encoded as regression
  contrasts (`SupportApproachAlignmentTest`), plus sweep regressions for pre-contact discovery
  (`ApproachSupportSweepTest`).
- `ensureWalkProof` carries completed source proof across re-centered probes when landmark and
  geometry revision match (tiles are absolute-positioned; acquisition still revalidates every
  body/floor sample), so descent movement no longer starves readiness until the actor stands still.

## Retained integration: mesh movement and latest-destination arrival

The `feat-runtime-merge-0017` continuation integrates the retained movement parent
`702a9a22f2a3a44e72aa402d28e260aa911388d1` and approach/retargeting parent
`460255a3c7fdc121cc8cb47d8e4e41b83e55672a`, preserving their existing changes.
Additional integration repairs:

- The original swept collision hook was not sufficient: vanilla `onPlayerMove`
  overwrites the resolved pose with the requested coordinates after `Entity.move`,
  accepting small horizontal residuals and discarding ordinary vertical residuals.
  `SpiritMeshMovementAcceptanceMixin` intercepts only that final pose write in the
  spirit world and retains the actual swept result. Meaningful prediction mismatch
  corrects to the accepted surface rather than the previous tick's position. The
  packet correction threshold does **not** authorize even microscopic penetration.
  Vanilla invalid-packet, speed, thread and teleport guards remain in place;
  vehicle/no-clip/sleeping cases retain vanilla handling. Command teleports are
  not intercepted. The mapped Minecraft bytecode and mixin registration/ordinal
  are tested, but Fabric injection at launch has not been exercised.
- `MeshCollision.ground(body, index, depth)` now uses the same widened swept-query
  helper as normal contact (including the SAT tolerance). Contact still uses .15;
  deeper discovery only plans alignment and cannot itself authorize walking.
- Local pose reconciliation observes mode/epoch changes on packet arrival and
  prediction ticks, resetting each channel exactly once. A transition whose
  authoritative channels arrive between ticks no longer causes another full
  adoption at the following four-tick sync. Retired/local-outside-spirit component
  instances lose their prediction hooks; remote/server sync remains untouched.
- `explicitDestinationChange` itself invalidates pending capture completion before
  advancing the destination epoch and clears both landing/support approaches.
  Callers cannot accidentally leave an old callback alive to overwrite a newer
  request. Existing bounded `WalkIntentTracker`, source proof/ownership validation,
  refusal replanning and real contact-only acquisition remain authoritative.

Actual validation: cached Gradle 8.13, Java 21,
`--no-daemon --console=plain build --rerun-tasks`, exit 0. All 13 runtime suites
were discovered and executed, including `MeshCollisionTunnelingTest`,
`MeshMovementArrivalTest` (737 checks), `SpiritMeshMovementWiringTest`,
`SpiritPoseReconciliationTest`, `ApproachSupportSweepTest`,
`SupportApproachAlignmentTest`, `WalkApproachRetargetingTest`,
`WalkRetargetingTest`, `FlightCorrectionPayloadTest` and four retained embedding
suites. Build fixtures validated runner discovery/failure propagation, descriptors,
metadata and invalid archives; the remapped production jar passed verification.
Retained build handle: original session `$11` / origin `%2039`, job pane `%2071`,
run `9cfe9147dc7a46d197d7ab3c82fd5429`. Nonfatal API Guardian annotation/deprecation
warnings remain. No game/client/server launch, visual inspection, real asynchronous
world-generation timing, model inference or gameplay measurement was performed.
Embedding persistence/pipeline tests use fixtures, not model execution; this scope
changes neither the backend nor native-v2 profile rules. Slow source readiness can
still exhaust a bounded request and legitimately retain deep flight. Singular,
unsafe, stale or unowned support remains refused instead of granting walking.


## Contact-alignment review repair

The retained pace recovery did not resolve a safely touching tilted floor whose
world AABB already overlapped the player's body. Every proposed step still
contained that old AABB, so halving the pace could consume all 200 ticks without
changing the basis. The new `MeshTransitionAlignmentTest` reproduced exactly
that expiry against `MeshCollision.transitionClear` before the repair.

`MeshCollision.transitionClear` now keeps the endpoint swept AABB as broadphase
and refines ambiguous, corresponding affine collision shapes using continuous
separating-axis certificates. A direction must separate the body from **both**
endpoint boxes on the same side: it then separates every intermediate vertex
under linear affine interpolation, not just sampled poses. Ambiguous intervals
are recursively bisected (depth <=16, <=128 interval checks per ambiguous shape).
Every half-interval needs a certificate; an exhausted budget still refuses the
transition. Local collision topology changes retain the conservative hold. The
existing 10-micrometer strict-body tolerance is unchanged. This is not a bypass,
forced basis snap, player relocation, or early grant of walking.

The five-block endpoint-nearness shortcut was also removed: two distant clear
endpoints can still sweep a surface through the player. Existing contact/source
ownership/proof gates, source-coordinate projection, per-player frames, movement
acceptance/prediction, and destination epochs are unchanged.

The regression uses actual dot-product-projected floor frames, the production
blend/pacing policy and transition guard, and real mesh ground/clearance/movement
queries. Positive/negative initial tilts start with affine-safe ground contact
despite AABB overlap, reach the exact source grid within 41 ticks, and retain
upright support for acquisition; it also covers the two-tick publication cadence.
Fractional slab contact, thin moving-wall crossings (including off-midpoint and
distant-endpoint crossings), unsafe final/new geometry, and deterministic sheared
transition spot checks guard against weakening collision safety. Synthetic pace
unit tests remain explicitly separate from these real-geometry regressions.

No game launch, live ownership readiness, Fabric injection, GPU behavior,
multiplayer measurement or model inference is established by these JVM checks.
Embeddings/backend/native-v2 profile behavior is not modified in this scoped repair.

Actual review-repair validation: cached Gradle 8.13 / Java 21,
`--no-daemon --console=plain build --rerun-tasks`, exit 0 (14 tasks executed).
All **14** current runtime suites were discovered and passed;
`MeshTransitionAlignmentTest` passed **5,437** checks. Build self-tests passed
runner discovery/order/assertion/failure fixtures, metadata, descriptor and
packaging checks. `verifyProductionJar` accepted
`build/libs/mysticism-1.0-SNAPSHOT.jar`. Retained handle: original session `$11`,
origin `%2362`, job pane `%3066`, run `996f807538b1427aaef2b2ada4d27104`.
Earlier PTY exhaustion cleared before this run; the earlier inaccessible job
is not counted as verification. Nonfatal API Guardian/Mixin/deprecation warnings
and expected corrupt-embedding fixture logs remain.
