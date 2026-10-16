# Runtime mesh revision consistency

Scope: `feat-runtime-mesh-mesh-revision-consistency`, retaining the existing edits from session
`01a11c81-1b96-703b-92d3-8e0d45617866` on base
`bfbce90c4752e8f6ee821769eb9d51a43453a13b`. This is only the retained mesh-revision unit,
not a claim that all eleven runtime reports have been repaired.

## Behavior

- Transport reassembly and GPU-ready display are distinct revision stages. Complete received
  frames can advance the delta base while display upload is pending; they cannot prematurely
  change predicted collision, terrain ray occlusion, or the published fog occupancy medium.
- `TerrainRevisionGate` exposes only the last successfully uploaded immutable frame. Successful
  uploads commit that exact candidate, not a newer unbuilt pending frame. Resource reload can
  rebuild the same committed frame; a fresh session clears both stages and collision indexes.
- New GPU batches are built off to the side. `TerrainBatchBuild` owns every staged batch until
  transfer, so a later model/upload failure closes earlier staged buffers. Old displayed buffers
  remain intact, and the render path still draws them on the frame where an upload fails.
  New network frames coalesce without bypassing the 500ms failure retry backoff.
- Terrain fragments explicitly carry their delta base revision. Reconstruction requires the
  exact base, consistent headers/base across all parts, unique changed keys, and the whole-frame
  budget. Missing parts, wrong bases, or regressing revisions preserve the prior complete frame
  and request full recovery. Incomplete idle streams expire after one second. Recovery requests
  retry at most twice per second until a complete full frame arrives.
- Both connection/world/player and active scene-session guards precede client terrain mutation.
  The assembler also refuses cross-session composition. Server recovery verifies the current
  actor/handler, acknowledged session nonce and generation, and a ten-tick per-connection limit.
  Clearing the sent delta base forces a full resend even when terrain geometry is otherwise idle.
- The changed terrain wire format uses `mysticism:spirit/terrain_v2`; old clients cannot silently
  decode the new layout. Client and server must use matching mod builds. The small recovery
  request is `mysticism:spirit/terrain_sync_v1` (its own independent protocol version).

No changes to per-player q/basis, deep/shallow navigation policy, source-coordinate octree
ownership/growth, server transition/provenance guards, embedding model/profile/dimensions,
or inference providers. No new dependencies, generated vectors, migrations, truncation, or
source-world block placement.

## Verification actually performed

Installed Java 21 / Gradle 8.13, from this checkout:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --continue build
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --rerun-tasks build
```

Both exited zero. The second ran all 14 actionable tasks (not cached) and discovered six
main-based runtime classes, including both new suites in `src/runtimeTest/java`:

- `TerrainRevisionCoherenceTest`: 23 checks. Uses the production gate/resource owner and
  actual affine SAT ray queries; exercises pending/committed movement, failure cleanup,
  stale-build rejection, successful ownership transfer, resource rebuild, and session reset.
- `TerrainFrameAssemblerTest`: 140 checks. Actual payload codec roundtrips and assembly;
  source/projection updates, removals/empty frames, wrong bases, fragment gaps/expiry,
  duplicate keys, same-revision full recovery, entry isolation, and 2048-cell/eight-part limits.
- Existing `AsyncEmbeddingCallbackIsolationTest`, `AsyncEmbeddingEngineSafetyTest`,
  `EmbeddingPersistenceTest`, and `EmbeddingPipelineTest` all passed. These do not prove
  real model inference; persistence corruption fixtures intentionally log warnings/errors.

`buildSelfTest` passed packaging/metadata/descriptor and test-runner discovery/failure fixtures.
`compileJava`, `compileClientJava`, `remapJar`, and `verifyProductionJar` passed. The verified
artifact is `build/libs/mysticism-1.0-SNAPSHOT.jar`. The canonical runner deliberately excludes
historical prototype suites; the disabled framework `test` task is not evidence of gameplay tests.
`git diff --check` passed. Existing client annotation/mixin warnings and Gradle deprecations remain.

Retained tmux handle: original session `$11`, origin `%1997`, build window `@1288`, pane `%2001`.
Final rerun token: `43379388a5d143eab0977fb30a2f3d50`; exit 0, no signal.

## Unverified behavior / limits

No Minecraft launch, OpenGL upload/draw, resource reload, live packet recovery, movement/teleport
playtest, or real model inference was performed. CPU failure fixtures are not GPU verification.
The server remains authoritative on its accepted frame; this is not a simultaneous distributed
server/client commit protocol. Network/render delay, and especially persistent GPU failure,
can still leave the server ahead of the coherent client display/prediction revision. No claim
of eliminating all rubber banding is made. Validate gameplay and GPU behavior on matching builds
before declaring the aggregate runtime repair complete.
