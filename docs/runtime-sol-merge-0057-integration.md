# Retained mesh, navigation, movement and discovery integration

Target branch: `feat-runtime-sol-merge-0057`, toward **dev**, never main.

## Retained state and conflict resolution

The checkout arrived with HEAD `38b83d6798714173930bc652941efadae4a76de8`
and a pending merge of `ccae8b425b64afc16d8b6e15e2c081ecc8e5442b`.
Existing commits and staged/uncommitted work were retained. Previous-session
history was supplied as `{}`; inspection used the actual index/merge parents,
source and retained handoffs rather than inventing a previous test result.

Resolved both conflicting files:

- `MeshCollision`: one production resolution path now carries the actual applied
  depenetration, chosen swept motion and real up-facing landing evidence. The
  entity adapter and entity-free tests use that same path. Mesh support is tested
  **after** depenetration; step-up starts from that body and retains its correction
  in the selected displacement. Vanilla ground flags and upward wall slides do
  not authorize stepping or sneak ledge clipping. Correction drains remain
  independent per player and client/server side.
- `SourceLandmarks`: pending work includes requests, prioritized hints, bounded
  refresh retries and the active operation. Ensure publication still immediately
  notifies terrain/navigation consumers after a successful commit. The incoming
  refresh/restart/fair-scheduling implementation is retained, not replaced with
  the earlier stale snapshot lifecycle.

The automatic navigation merge was reviewed: correction/transition navigation
state sync precedes the corresponding pose sync; physical state movement and
semantically integrable movement remain separate. Real support acquisition,
source footprint/neighboring collision producer proofs, walking-state commit,
teleport coverage/landing gates, current-frame selection and publication offers
are retained from the mesh/navigation parent. Ordering/reconciliation, distance-
composable steering, source observation invalidation/restart and fair visible-
locality discovery are retained from the movement/discovery parent.

Independent per-player deep/shallow projection, source-coordinate octree
ownership/growth and full native v2 vector/profile handling are unchanged.

## Added cross-parent regression

`MeshGroundSupportTest` now drives the actual combined resolution with a body
0.1 into a mesh floor, a 0.4-high ledge and a false vanilla ground flag. It checks
separate correction reporting, full horizontal step travel, exact physical ledge
support/body clearance, correction-free client travel, explicit server
reconciliation, flag independence and zero repeated correction at rest. The
leaning-wall discriminator additionally checks that an upward slide reports
neither a landing nor a depenetration. The suite passes **99 checks**.

## Canonical verification

Completed-source command (no task exclusions or diagnostic dependencies):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build --rerun-tasks
```

Passed, exit 0, 1m 11s, all 14 actionable tasks executed. Original tmux session
`$11`, origin `%2955`; retained build pane `%2963`, window `@1790`, run
`55088765e8eb4dc9a9404ca8071e9cc7`. The run executed **25 discovered runtime
main classes**, not zero tests behind the disabled framework-based Test action:

| Suite | Checks |
| --- | ---: |
| MeshGroundSupportTest | 99 |
| MeshTransitionClearanceTest | 18882 |
| SelectionAvailabilityTest | 31 |
| TeleportMeshCoverageTest | 69 |
| WalkAcquisitionProofTest | 2245 |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| DiscoveryInvalidationTest | 132929 |
| LandmarkPublicationAvailabilityTest | 9 |
| SourceAvailabilityRestartTest | 2013 |
| DiscoveryCoverageTest | 74 |
| DiscoveryDispatchTest | 26 |
| DiscoveryFairnessTest | 2330 |
| DiscoveryPriorityTest | 215 |
| VisibleDiscoverySweepTest | 16935 |
| MeshCollisionCorrectionSelfTest | 30 |
| MovementIntegrationSelfTest | 42 |
| MovementReconciliationSelfTest | 171 |
| MovementTimingSelfTest | 30 |
| PoseOrderingTest | 55 |
| SpiritMovementHookContractSelfTest | 17 |
| SteeringBurstAdvancementSelfTest | 15 |
| WalkRequestAcceptanceTest | 2174 |

Build/decoded-metadata/descriptor/packaging/runner discovery and failure fixtures
also passed, as did remapping and `verifyProductionJar` for
`build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` passed. Existing
annotation/mixin/deprecation warnings are nonfatal; malformed-vector persistence
fixtures deliberately log caught errors.

## Limits and outstanding full-request work

This is deterministic entity-free/offline validation, not live Minecraft
movement, real delayed-network gameplay, GPU rendering, asynchronous session
integration, performance profiling, native model inference or an IntelliJ launch.
The retained movement history is bounded and cannot reconstruct turns hidden
inside sampled net movement windows. Discovery priority/fairness is tested but
actual source/inference throughput still bounds visible-locality availability.

This unit does not claim all eleven original problem areas are repaired. In
particular, the inherited `EmbeddingService` still uses the external Ollama HTTP
provider: in-process model restoration is **not implemented or verified here**.
No embedding runtime/profile was replaced, no old vectors migrated/truncated,
no dummy vectors added and no server dependency newly introduced by this merge.
Other separately owned rendering/entry/fog/model units require their own
integration and gameplay/inference validation.
