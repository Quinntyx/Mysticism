# Retained runtime unit: source-space mesh ownership

Scope: `feat-runtime-mesh-source-octree-ownership`, based on
`bfbce90c4752e8f6ee821769eb9d51a43453a13b`. This is only the mesh membership/ownership
unit, not a claim to fix all eleven runtime reports. No embedding implementation,
projection basis, movement policy, source persistence format, or other branch was
changed.

## Behavioral repairs

- Persisted geometry is replaced only at actually sampled absolute source cells.
  An AIR sample removes its cell; missing/UNKNOWN samples do not erase the rest of
  a sliding or disconnected patch's bounding envelope. Empty live patches retain
  persisted geometry. The envelope is only a broad-phase rejection; partial leaves
  recursively partition their exact sampled positions without expanding the whole
  persisted volume.
- One composition path reapplies a current exact source ownership mask to both live
  and persisted nodes on ownership completion, live recompaction, and geometry-stream
  publication. Geometry-only windows also refresh on ownership revision changes.
  Missing ownership inside a completed mask remains unowned; far nodes retain their
  producing owner. Source AIR ownership is independent of AIR having no visible mesh.
- Mesh keys avalanche each source-coordinate component before folding the next.
  Whole-double FNV with only a final avalanche had deterministic collisions on small
  signed integer lattices. `append` silently skipped these distinct cells, losing
  their visible mesh and collision together. This was reproduced by the new real
  `TerrainMeshFrame` regression before the key fix. Keys remain independent of
  player/carrier coordinates, projection basis, and octree root bounds. These are
  transient frame keys, not saved IDs; no migration is involved.
- Compaction priority uses actual positive source-cube bounds, not a unit block
  symmetrically expanded in all directions. This prevents coarse leaves claiming
  artificial proximity ahead of genuinely nearer source cells under mesh budgets.

The existing shared affine cells still drive rendering and SAT collision. Independent
per-player shallow/deep projection and source-coordinate octree growth are preserved.

## Regression discovery and verification

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/SourceMeshOwnershipTest.java`
is discovered by the existing `runtimeSelfTest` main-based runner, with assertions
explicitly enabled. It exercises real compaction, exact replacement/refinement,
`SparseOctree` growth, `TerrainMeshFrame` validation and `MeshCollision.Index` sweeps:

- Signed-coordinate, owner and side key lattice: 103,173 distinct keys.
- Sparse AIR/SOLID replacement, empty and complete-AIR patches, no overlaps.
- Mixed ownership, unowned cells, geometry-only and repeated live rebuilding,
  near/far refinement and newly discovered source cells.
- Positive and negative octree root growth, immutable parent, UNKNOWN envelope,
  and stable source identity.
- Sheared/scaled and translated frames whose SAT contact resolves to the same
  producing source cell; unchanged keys under identity versus distorted projection.
- Forty deterministic randomized sparse patches compared to exact per-block membership.

Canonical installed Gradle 8.13 command, in this checkout:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --rerun-tasks build
```

Result: **exit 0**, `BUILD SUCCESSFUL in 15s`, all 14 actionable tasks executed.
Original tmux connection `/tmp/tmux-1000/default,30794,11`, origin `%1699`;
retained job pane `%1744`, run `4cd9ea800c514b9ca6dca6f2c31c59c3`.

Runner discovered and passed exactly five suites:

- `SourceMeshOwnershipTest`: 249,600 checks.
- `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
- `AsyncEmbeddingEngineSafetyTest`: 184 checks.
- `EmbeddingPersistenceTest`: 90 assertions.
- `EmbeddingPipelineTest`: 135 assertions.

`buildSelfTest` passed packaging/metadata/descriptor and runner discovery/failure
fixtures. `verifyProductionJar` passed on `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Expected corrupt-embedding fixtures log warnings/stack traces; their tests passed.
Existing client API Guardian/Mixin annotation warnings and Gradle deprecation warnings
remain. Historical prototype tests excluded by `scripts/selftest.gradle` were not
claimed as discovered or run. `git diff --check` passed.

## Limits

No game/client/server launch, render capture, real model inference, or in-game
multiplayer/teleport/growth stress test was performed. The affine tests exercise
production SAT mathematics, not a rendered frame. Existing mesh budgets, streaming
latency, admission/fade/transition policies remain. The broader movement, entry,
shader and native-v2 in-process embedding work is outside this retained unit.
Native v2 embeddings were not changed, truncated, migrated, or replaced.
