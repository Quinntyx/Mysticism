# Observer-local collision authority

Scope: `feat-runtime-mesh-voxel-collision-removal`, for integration into **dev**.
This is the collision-path unit, not an implementation claim for the other runtime units.

## Active paths repaired

The existing movement/sneaking hooks already swept the observer's affine source
shapes. They were not sufficient: vanilla server packet validation subsequently
queried the carrier world's blocks, entities and border. Client prediction also
pushed the player out of hidden carrier blocks. Pose changes, generic clearance
and suffocation queried those same unrelated blocks.

- `SpiritMeshPacketCollisionMixin` keeps vanilla movement validation, but replaces
  its block-space clearance and new-endpoint-intersection tests with the same
  player's `MeshCollision` frame. An existing overlap is not a **new** intersection:
  moving out of it remains possible. Swept movement remains authoritative; speed,
  teleport and other packet checks are not disabled.
- `SpiritMeshCollisionMixin` replaces generic clearance and suppresses carrier-only
  suffocation and push-out. Mesh depenetration, not a nearby air block, separates
  player and surface.
- `SpiritMeshSneakMixin` uses affine body clearance for pose changes as well as the
  existing real-mesh ledge check.
- Client-only `SpiritCarrierPushMixin` suppresses the client's separate two-axis
  carrier-block push-out and vanilla voxel Auto-Jump. It clears queued Auto-Jumps
  before the movement tick consumes them, including a source-world jump queued
  just before seamless entry. Explicit jumping and affine mesh stepping remain
  intact; native/source-world Auto-Jump and the user's option are unchanged.
- All hooks are restricted to spirit **players**. Native/source worlds retain
  vanilla behavior; no source-world collision data extraction was removed.
- Fully zero-opacity cells no longer obstruct body clearance or geometry
  publication. Fade-in at identical coordinates is still validated. Nonzero
  opacity retains collisions. Broad phases use each actual local shape's transformed
  bounds, including shapes extending outside the unit model (e.g. fences).

No overlay stamping, voxel snapping, full-cell AABB collision, shared observer
frame, synthetic floor, or source-ownership approximation was introduced. The
source-coordinate octree, deep/shallow projection, source capture, navigation,
rendering and embedding implementations are unchanged. Historical block-prototype
classes with no production caller are not used as fallback paths.

## Original regression discovery and verification

The current runner in `scripts/selftest.gradle` discovers tests under
`src/runtimeTest/java` and the existing embedding-test directory. Historical
`src/test/java` prototype suites outside that directory are deliberately not
reactivated. The new suites are in the active source set:

- `MeshCollisionAuthorityTest`: 29 checks of real production SAT, swept motion,
  packet endpoint guards, opacity transitions, partial/extended shapes, distorted
  empty corners and separate observer projections.
- `SpiritMeshCollisionHookTest`: 11 packaged/required hooks and their mapped
  Minecraft 1.21.1 method targets; redirect invocation owners/descriptors checked
  directly in bytecode. This is **not** a live mixin transformation test.

Executed with the existing Gradle 8.13 / Java 21 installation:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build
```

Result: exit 0, `BUILD SUCCESSFUL`; all six discovered runtime suites passed:
new mesh authority and hook suites plus embedding callback isolation, engine
safety, persistence and pipeline. `buildSelfTest` passed its packaging,
descriptor and runner fixtures. `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar` (not the dev jar).

Retained first build: original tmux session `$11`, origin `%1905`, pane `%1914`,
run `ee7c81d3f4434ce98e28e6d06044276f`. Loom emitted nonfatal CCA source-remapping
warnings about missing `javax.annotation.Nullable`; client compilation emitted
missing API Guardian annotation enum warnings. Persistence regressions logged
expected malformed-data rejection stack traces while passing.

## Review follow-up: client voxel Auto-Jump

The review identified `ClientPlayerEntity.autoJump(FF)V` as another independent
carrier-block path. Mapped Minecraft bytecode confirms direct calls to
`World.getCollisions` and `BlockState.getCollisionShape`, and confirms that
`tickMovement` consumes `ticksToNextAutojump` into `input.jumping` before moving.
Suppressing only `pushOutOfBlocks` did not guard either path.

The repair cancels Auto-Jump at HEAD in spirit terrain and clears its countdown
at movement-tick HEAD without cancelling the tick. There is deliberately no
block-based fallback or fabricated mesh Auto-Jump heuristic. Players can jump
explicitly, and `MeshCollision.move` continues to perform mesh-based stepping.

Active regression coverage now includes:

- `SpiritCarrierAutoJumpTest`: 40 checks invoking the unchanged compiled
  production handlers with isolated player/world doubles: spirit cancellation,
  pending countdown cleanup, stationary ticks, native-world pass-through,
  re-entry on the same player object, and retained push-out behavior. The doubles
  are test-only; no Minecraft bootstrap or live mixin transformation is claimed.
- `SpiritMeshCollisionHookTest`: 13 required registered mapped targets, plus
  shadow-field mapping, HEAD placement, non-cancelling cleanup, direct vanilla
  voxel calls and queued-jump consumption. Its annotation decoder handles
  `@Inject`'s `At[]` as well as `@Redirect`'s single `At`.

Full forced verification command (existing Gradle 8.13 / Java 21, no exclusions):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build --rerun-tasks
```

Result: exit 0, `BUILD SUCCESSFUL`, 14 executed tasks. Discovery executed all
seven current runtime suites (29 geometry checks, 40 compiled-handler checks,
13 hook targets, and all four existing embedding suites). `buildSelfTest` and
`verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Nonfatal warnings included missing API Guardian annotations, the existing public
RenderLayer mixin target declaration, deprecations and expected malformed-data
rejection logs from embedding persistence tests.

Retained successful build: original session `$11`, resumed origin `%2285`, pane
`%2286`, run `5d09889f444642708517d69a2adc8aac`. The first forced build in that
pane (run `3d783cd47e044ea5809eff9a2b891c2a`) failed in the new annotation decoder
with `ClassCastException` on `@Inject.at`; the decoder was repaired and the whole
build rerun, not bypassed. The earlier origin `%2262` had disappeared when this
session resumed; it was not used to launch a fallback job.

## Remaining runtime verification

No client/server launch, gameplay, shader/GPU or native model inference was
performed. Before integration sign-off, test source-world movement unchanged;
spirit movement/pose changes through deliberately inserted hidden carrier blocks;
Auto-Jump enabled during entry/re-entry with pending source-world jumps, manual
jumping/mesh stepping, and native-world Auto-Jump after exit; rotated mesh edges and partial/extended shapes; server packet reconciliation;
fade-out/fade-in; and two observers with different projections. Pending-frame
behavior intentionally matches existing movement: no frame means no fabricated
carrier obstacles. This branch does not repair entry streaming or guarantee frame
readiness after `/tp`, and does not change embeddings or require an external server.
