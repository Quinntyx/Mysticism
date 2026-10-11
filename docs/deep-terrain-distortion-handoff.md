# Retained deep-terrain distortion repair

Scope: `feat-runtime-mesh-deep-terrain-distortion`, based on `bfbce90`. Repairs and
completes the retained uncommitted `DeepDistortion`/service/runtime-test work from
session `01a11c81-1c20-7189-bf7b-661bace2a808`; no prior commits or unrelated edits
were discarded. Other requested runtime units, embeddings, navigation, shaders,
source ownership and build/source-set configuration are untouched.

## Real production change

`SpiritTerrainService.buildMesh` now deforms the **final stitched cells**, before
constructing the shared immutable frame used by publication, `canAlign`, server
SAT, terrain transport, client rendering/prediction and source-support inversion.
The tick phase is session-local. UUID seed mixing uses both halves; the full-width
seed and tick are reduced before floating-point conversion, so UUID-sized numbers
cannot swallow a two-tick animation increment.

The old independent four-corner nonlinear fits did not preserve the other four
corners/shared faces. They also did not bound total corner motion, and independently
clamped or rejected adjacent cells. The repair uses one evolving affine translation
and shear per actual producing source window (not ownership label). A common affine
map preserves previously shared faces, including coarse/fine octree boundaries.
It does not manufacture bridges or smooth genuine holes/cliffs.

* Shallow bypasses distortion exactly. Deep windows with any model/collision
  extent within eight blocks stay entirely unchanged. Empty space between distant
  cells is not treated as nearby supporting terrain.
* The radial strength reaches full at 40 blocks, inside the existing 64-block
  render/fog horizon. Far window centers move 1.375 blocks at full strength and
  axes actually shear; this is not a nominal config or material-only change.
* All model and local-collision corners move at most 2.5 blocks. The rank-one shear
  has determinant ratio in `[.82, 1.18]`, preserving even reflected/skewed source
  projections. No per-cell fallback tears a connected patch.
* Keys, producing-window map, source coordinates/extent, palette/material, opacity,
  tint/light and local collision shapes remain unchanged. Building previews does
  not mutate accepted geometry or provenance. Existing transition/body guards
  still decide whether a shared render/collision replacement can be committed.

## Validation actually performed

Canonical command, using the existing Gradle 8.13 distribution and Java 21:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

`build` runs common/client compilation, the discovered `runtimeSelfTest`,
`buildSelfTest`, remapping and `verifyProductionJar`. Runtime discovery includes:

* `DeepDistortionTest` (production build hook, shallow/near protection, independent
  producer identities, shared faces/coarse-fine joins, all eight model/shape corners,
  full-width seed animation, reflected/skewed large nodes, baked-model affine
  matrix contract, fractional source inversion, actual `MeshCollision.Index` SAT
  contact time/normal and transition rejection, full/delta terrain wire roundtrips).
* `AsyncEmbeddingCallbackIsolationTest`, `AsyncEmbeddingEngineSafetyTest`,
  `EmbeddingPersistenceTest`, `EmbeddingPipelineTest` (existing enabled suites).

The packaging suite checks decoded Java metadata, descriptors, positive/negative
artifact fixtures and test-runner discovery/order/assertion/failure fixtures. The
production verifier checks `build/libs/mysticism-1.0-SNAPSHOT.jar`. Historical
prototype tests remain excluded by the existing source-set configuration; it was
not changed to hide failures.

Build output includes pre-existing API-status/mixin/deprecation warnings and
expected negative embedding-persistence fixture logs. These are not gameplay or
model-inference validation. The original-session tmux job handles, exact final
check counts, candidate commit and push outcome are reported with the handoff.

## Honest limits

This is an affine **window** distortion, not nonlinear vertex/tetrahedral/SDF
meshing. To avoid cracks in the existing affine model/SAT contract, the distant
part of a window with a near surface remains protected too. Different producing
windows may move independently, as their semantic projections already do; this
does not claim a watertight union of unrelated landmarks. Streaming changes to a
window's retained bounds may change its deformation; existing publication guards
still protect the body, but live visual/performance behavior is unprofiled.

No Minecraft client/server launch, entry/walk/flight playtest, GPU capture,
multiplayer latency test, or real native-v2 model inference was performed. The
extra animated far geometry can cause VBO/broadphase rebuilds at the existing
publication cadence; no performance measurement is claimed. All other reported
runtime problems remain the responsibility of their assigned units/integration.
No embedding implementation, dimensionality, vectors, server prerequisite or
migration behavior was changed by this scope.
