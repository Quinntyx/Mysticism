# Retained runtime integration: merge-0028

Integrates retained parents `7c168c3b2e3b31632f829a3940c5baa918395e9a`
(mesh support evidence and visual depth) and
`e90eda87cec33bda32d7258fe651c403e19c4d5f`
(mesh surface quality and late walking recovery). The checkout began inside their
unfinished merge; no commits or user edits were discarded. Only the assigned
terrain evidence/depth/boundary/walking recovery scope is claimed, not all eleven
runtime problems. Neither dev nor main was modified.

## Integration decisions

- Keep actual affine contact witnesses, exact current ownership diagnostics and
  collision-primitive broadphases. Do not reintroduce endpoint unit-model bounds
  into transition checks: distant endpoints can sweep through the player and
  source shapes can extend outside a unit model.
- Keep measured uploaded float-vertex bounds and normalized inverse-transpose
  normals, rather than the old alpha-only vertex adapter.
- Feed the current camera-local fog medium from immutable per-model bounds
  measured during successful VBO uploads. Include extended/non-collidable baked
  models; empty models do not manufacture density. Reuploads invalidate the
  medium cache even without a new packet. Exact MAIN scene depth, not the coarse
  density hints, remains the fog occlusion endpoint.
- Retain crack-free affine leaf projection and deterministic whole-cell duplicate
  selection. Independent source projections are not joined with fabricated floors.
- Retain the per-player bounded issued walk intent, actual published current-source
  material proof classification, and late recovery from the current basis. Repeated
  requests do not reset budgets; cancellation/expiry releases retained state.
  Discovery completion alone is not ownership, proof publication or acquisition.

`UploadedTerrainEvidenceTest` combines the production bounds/medium cache with
actual extended affine support evidence and collision. It checks empty uploads,
reuploads without packets, hidden geometry, diagnostic producer/source witnesses
and agreement at the extended visible top. Parent suites retain their boundary,
normal, swept transition, proof publication and asynchronous timing regressions.

## Actual validation

Cached Gradle 8.13 / Java 21, no exclusions or diagnostic classpath:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --continue --rerun-tasks build
```

Final run: exit 0, `BUILD SUCCESSFUL in 26s`, all 14 actionable tasks executed.
Common/client/runtime compilation, `buildSelfTest`, `runtimeSelfTest`, `remapJar`,
`remapSourcesJar` and `verifyProductionJar` passed. Framework `test` is deliberately
skipped; runtime coverage ran through the discovered main-based runner:

| Runtime suite | Result |
| --- | --- |
| SpiritTerrainDepthContractTest | 25 static wiring checks |
| SpiritMediumFieldTest | 24601 checks |
| TerrainVertexConsumerTest | 13 checks |
| UploadedTerrainEvidenceTest | 14 combined geometry checks |
| MeshSupportEvidenceTest | 32 checks |
| MeshSurfaceQualityTest | 753 checks |
| WalkSourceReadinessTest | proof publication/readiness and late recovery passed |
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| WalkRequestRecoveryTest | 9 timing/recovery groups |

Packaging/runner discovery, ordering, assertions/failure propagation, metadata,
14 descriptor-only and 19 invalid-artifact rejection cases passed. Verified artifact:
`build/libs/mysticism-1.0-SNAPSHOT.jar`.

Retained original tmux session `$11`, origin `%2218`, build pane `%2230`, final
run `1a057bdb16e34ca9872142aa6f2b9a27` (exit 0). Prior integration build in the
same pane, token `f605ebfe3ba04364ae2632079e1ee1cf`, also passed before the
uploaded-bounds enhancement. `git diff --check` passed. Existing API Guardian
annotation/deprecated-API/Gradle warnings remain; malformed embedding fixtures
log expected negative-case exceptions.

## Limits and remaining verification

No Minecraft/IntelliJ launch, actual GPU upload/draw, framebuffer driver exercise,
Fast/Fancy/Fabulous/reload/resize smoke, multiplayer timing or performance test was
performed. The new geometry regression does not bootstrap a renderer/server.
Density is still a coarse conservative AABB hint; genuinely independent source
projections may have real gaps. Source budgets and the existing render horizon
are unchanged. Per-player walk recovery still requires live source publication,
current contact and all acquisition/transition guards, and needs slow-discovery
playtesting. Static/server diagnostic evidence is explicitly not a client draw
acknowledgement.

No model inference or embedding service was called. Embedding backend, native v2
profile/dimensions and persistence were not changed; there is no v1 migration,
truncation, dummy-vector substitute or new server prerequisite in this scope.
Restoring and actually verifying in-process inference remains a separately owned
runtime unit. No compile/test/packaging blocker remains in this candidate.
