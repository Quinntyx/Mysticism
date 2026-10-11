# Runtime mesh/support evidence

Scope: `feat-runtime-mesh-mesh-support-diagnostics`, targeting development integration.
This repairs the terrain/support evidence unit, not all eleven runtime workstreams.
The retained baseline was clean at `bfbce90c4752e8f6ee821769eb9d51a43453a13b`.
The preceding session ended after investigation, without implementation edits.

## Behavior repaired

* Ground support now has a contact witness computed from the intersection of the
  swept player body and the actual affine collision primitive. Both polyhedra's
  edges are clipped against the other volume. The witness is not the foot center
  clamped into a voxel or a conservative world AABB. Ownership sampling moves a
  tiny fraction towards that primitive's center, keeping the witness inside even
  when distortion makes the shape thinner than a fixed world-space inset.
* A simultaneous non-supporting wall hit no longer masks a supporting floor.
  The support query considers upward SAT hits before choosing its earliest hit.
* `support` and `currentSupport` inverse-map that same witness for exact current
  source-octree ownership. Actor feet remain independently inverse-mapped, without
  clamping, for walking acquisition. Existing ownership revisions, accepted-frame
  producer identity, body ownership, source material proof, basis alignment and
  atomic publication guards are retained.
* Zero-opacity cells are absent from support, body clearance and transition
  collision, just as they are absent from the renderer. A hidden-to-visible cell
  must pass body clearance even when its affine pose has not changed.
* Clearance uses actual collision-primitive bounds, including overhangs beyond
  unit model coordinates. The transition guard no longer drops a moving surface
  merely because both endpoint model bounds are distant from the actor; its swept
  volume still must not intersect the body.

No player q, basis, position, flight state, attunement, source ownership topology,
embedding profile or embedding backend is replaced by this unit. No proxy block
placement, synthetic floors, source generation, migration or dependencies added.

## Diagnostics

`/spirit terrain` reports **published server geometry**, not a client draw or
landing acknowledgement:

* Accepted revision, positive-opacity cell count and affine collision shape count.
* Actual contact cell key, mesh label, fractional inverse-mapped source contact,
  source dimension and affine surface normal when support exists.
* Exact ownership as a separate observation: pending/stale snapshot, outside
  coverage, unowned in the current snapshot, or the proven current owner.

It reads the accepted frame and its retained producer map. It does not load blocks,
queue discovery, mutate navigation or equate `Window.ready` with support. A missing
contact is explicitly **not** a source-discovery verdict. A mesh label is not an
ownership proof. `/spirit` also distinguishes semantic anchoring and pending walk
approach from deep/shallow navigation mode.

## Regression discovery and build

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/MeshSupportEvidenceTest.java`
is in the existing `runtimeTest` source set and discovered by the main-based runner.
It exercises production SAT/contact/evidence and clearance methods: cube/slab/stair,
wall-first simultaneous contact, edge-only footprint overlap, skew and mirrored
transforms, tilted normals, inverse mapping, overhangs, AABB false positives,
render-only cells, fades, hidden-to-visible publication, thin distorted ownership
witnesses and swept transitions.

Canonical command (installed Gradle 8.13, JDK 21, no exclusions/overrides):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --continue --rerun-tasks build
```

Run through the required tmux-jobs helper, original pane `%1794`; the final retained
pane/run handle is recorded in the completion report. Successful verification
includes runtime discovery (this terrain suite plus four existing embedding
suites), packaging/runner fixtures, common/client compilation, remapping and
`verifyProductionJar`. Intermediate checks caught and repaired a test declaration
compile error and an incorrectly positioned skew-contact fixture.

No Minecraft gameplay, OpenGL draw, multiplayer session, actual model inference,
or IntelliJ launch was performed. Publication/opacity evidence cannot prove a
client received/uploaded/drew the frame; diagnostics deliberately say so. Ground
contact computation adds bounded per-query geometric work; in-game performance
and remaining transition holds need gameplay validation. Existing API Guardian,
client mixin-target and deprecated-API warnings are not runtime verification.
