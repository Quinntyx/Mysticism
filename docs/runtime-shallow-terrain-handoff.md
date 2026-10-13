# Runtime shallow terrain distortion

Scope: the retained `feat-runtime-mesh-shallow-terrain-distortion` unit only, for
integration into `dev`. This does not claim to fix all eleven runtime reports.

## Behavior

- The supporting source/carrier binding stays source-grid aligned while shallow.
  Other actual octree owners in that source window supply their current semantic
  locations, projected through this observer's own q and basis. No shared anchors,
  normalized location vectors, or source ownership changes are introduced.
- Entry/acquisition starts source-identical. Once ownership anchoring is delivered,
  distortion ramps over 40 server ticks. Displacement is capped at four blocks.
- The whole model and its actual local collision shapes stay native within five
  blocks of the observer, including coarse nodes and overhanging fence shapes.
  Both whole-cell protection and the point displacement field fade smoothly toward
  24 blocks, avoiding a far-corner snap when a cell enters the walking patch.
- Distortion changes the authoritative cell origin and affine columns, not a shader
  or client-only offset. Existing frame publication, body-transition rejection,
  mesh deltas, client collision and rendering consume that same accepted geometry.
  Local slab/stair/fence shapes, source coordinates, keys and owner IDs are retained.
- The renderer uses a small CPU-testable affine transform helper; fallback geometry
  uses those same local collision primitives. SAT remains narrow-phase affine-shape
  collision, not the cells' broad-phase AABBs. Vanilla block outlines are suppressed
  only in the spirit dimension; source-world outlines are unaffected.

## Regression coverage

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/ShallowTerrainProjectionTest.java`
is discovered by the existing main-based `runtimeSelfTest` runner. It exercises
native schema-2/768-dimensional independent projection (including coordinate 767),
location immutability and v1 rejection, protected ground/coarse/overhanging cells,
walking-patch boundary continuity, entry ramp and full far displacement, actual
render/SAT affine agreement, slabs/stairs/fences and empty shapes, sloped normals,
sheared AABB voids, exact wire/delta geometry and client/server sweep agreement.
It uses deterministic math fixtures, not fabricated production embeddings.

Canonical verification uses the installed Gradle 8.13 and Java 21, with no extra
framework or software installation:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --rerun-tasks build
```

Verified: `build` passed with all 14 actionable tasks executed, all five discovered
runtime classes passing (257 checks in the new terrain suite), `buildSelfTest`
discovery/descriptor/metadata/packaging regressions passing, and
`verifyProductionJar` validating the remapped production jar. `test` is intentionally
skipped in this main-based test harness. Existing API/deprecation warnings remain.

## Limits and handoff

No Minecraft/GL launch, multiplayer gameplay, seam/visual inspection or model
inference was performed. A coarse affine cell approximates the radial displacement
field; adjacent faces may separate outside the walking patch rather than receiving
artificial collision bridges. Source streaming distance, flight/navigation state,
fog/Kuwahara and in-process model-runtime restoration are separately owned work;
this unit neither implements nor verifies those fixes. Fresh native v2 embedding
configuration is unchanged, with no migration, truncation or new server prerequisite.
