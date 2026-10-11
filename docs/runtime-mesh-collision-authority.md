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
  carrier-block push-out.
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

## Regression discovery and verification

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

## Remaining runtime verification

No client/server launch, gameplay, shader/GPU or native model inference was
performed. Before integration sign-off, test source-world movement unchanged;
spirit movement/pose changes through deliberately inserted hidden carrier blocks;
rotated mesh edges and partial/extended shapes; server packet reconciliation;
fade-out/fade-in; and two observers with different projections. Pending-frame
behavior intentionally matches existing movement: no frame means no fabricated
carrier obstacles. This branch does not repair entry streaming or guarantee frame
readiness after `/tp`, and does not change embeddings or require an external server.
