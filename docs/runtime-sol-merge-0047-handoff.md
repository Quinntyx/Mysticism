# Retained runtime integration: merge-0047

Owned branch: `feat-runtime-sol-merge-0047`; integration target: dev, never main.
The checkout began in an unfinished merge of retained parents
`0959ecdaa502e8a91351f2a15a17a044687126ba` (merge-0028) and
`a9e52b1cd14b9bbdb45d692e10bdfe121b5fed4b` (merge-0046).
Existing commits and staged/uncommitted work were preserved. No other checkout,
dev or main was modified; no agents were delegated.

## Integration decisions

- Retain actual affine ground contact witnesses and frame-producer ownership,
  not clamped player-center samples or proxy block evidence. Keep both projected
  and source-space contact normals in `WalkSupport`; source normals use the
  affine covector pullback. This allows deep walking to follow its current
  supporting locality without treating a projected source wall as a source floor.
- Keep `Index.ground` selecting a supporting surface independently of an earlier
  simultaneous wall collision. Diagnostics and navigation consume this same
  geometry. Retain exact current ownership/readiness distinctions and bounded
  asynchronous walk recovery rather than command-driven retry loops.
- Retain primitive-specific collision bounds, extended source shapes, continuous
  affine transition guards, normalized inverse-transpose render normals,
  measured uploaded vertex bounds and crack-free affine leaf boundaries. Do not
  restore the endpoint-locality shortcut: far endpoints can sweep through a body.
- Also retain mesh-only body clearance and new-intersection packet checks,
  carrier push/autojump/sneak hooks and removal of obsolete voxel collision paths.
  An existing mesh overlap being escaped is not a new endpoint obstruction.
- Preserve current mesh MAIN depth before the scene snapshot, current uploaded
  geometry's fog medium, and painterly terrain -> volumetric fog -> saturation
  -> transition composition. The immutable original scene and preserved depth
  prevent painterly filtering from dropping fog or feedback-obscuring terrain.
- Keep default in-process CPU/JNI embeddings, exact pinned native v2 weights,
  768-coordinate output, token-overflow rejection and actionable desktop/CPU
  failures. No v1 migration, truncation, dummy vectors or external-service
  prerequisite was introduced. Per-player projection and source octree contracts
  remain unchanged.

`DeepWalkContactPremiseTest` now uses the production ground query, including a
combined regression with an earlier wall sweep and simultaneous real floor:
mesh diagnostics, ownership witness and source-normal ATTACH/FOLLOW policy must
all select the floor. Other cases exercise real tilted mesh contact, guarded
changing-basis alignment, covector pullback, upward-projected source walls and
blocked transitions. This is geometry/policy coverage, not a live server test.

## Actual checks (2026-10-14)

```text
./gradlew --no-daemon --console=plain --continue --rerun-tasks build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 37s`; all 15 actionable tasks executed.
Common/client/runtime compilation, build fixtures, main-based runtime discovery,
Loom remapping and production artifact verification passed. All **25 discovered
runtime suites** ran with assertions enabled; framework `test` is intentionally
skipped by the project's main-based test contract, not used as substitute evidence.

Runtime results:

| Suite | Result |
| --- | --- |
| SpiritPostProcessCompositionTest | 5870 resource/policy/CPU checks, no GPU |
| SpiritSourceFogExitTest | 647 selection/policy/CPU checks, no GPU |
| SpiritTerrainDepthContractTest | 25 static wiring checks |
| SpiritMediumFieldTest | 24601 checks |
| TerrainVertexConsumerTest | 13 checks |
| UploadedTerrainEvidenceTest | 14 combined geometry checks |
| DeepWalkContactPremiseTest | 299 production geometry/policy checks |
| MeshCollisionAuthorityTest | 29 production geometry checks |
| MeshSupportEvidenceTest | 32 checks |
| MeshSurfaceQualityTest | 753 checks |
| WalkSourceReadinessTest | publication/readiness/late recovery regressions passed |
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedCpuIsaTest | 84 checks, including fresh-JVM rejection before JNI |
| EmbeddedEmbeddingSelfTest | 54 fixture/lifecycle checks, no weights |
| EmbeddedNativeRuntimeSelfTest | actual Linux x86_64 JNI ABI b10682-5ea1b124e, no inference |
| EmbeddedPackagedRuntimeTest | isolated remapped jar + five nested dependencies; CPU rejection and actual host JNI bootstrap |
| EmbeddedPlatformTest | 101 platform/failure checks, no inference |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| PinnedEmbeddingModelSelfTest | 25 integrity/provenance fixture checks, no weights |
| SpiritCarrierAutoJumpTest | 40 compiled-handler checks with isolated doubles, not live mixins |
| SpiritMeshCollisionHookTest | 13 registered mapped hook targets, offline |
| DeepWalkAttachmentTest | 47 policy checks |
| WalkRequestRecoveryTest | 9 timing/recovery groups |

Build fixtures cover metadata decoding, 14 descriptor-only rejections, valid jar
and 27 invalid packaging cases, plus runner discovery/order/assertions/failure,
missing-main and empty-suite behavior. `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` and staged diff checks
passed. Existing API Guardian/deprecated API, SLF4J test-classpath and Gradle
warnings remain; corrupt-persistence fixtures intentionally log exceptions.

Original tmux session `$11`, origin `%2603`; retained full-build pane `%2605`,
run `2867375fc4da44f89b00f69a43f73a5f` (exit 0).

```text
./gradlew --no-daemon --console=plain embeddedInferenceProbe
```

**BLOCKED/FAIL**, exit 1 (`BUILD FAILED in 7s`). CPU/JNI preflight passed
`b10682-5ea1b124e`, then the probe failed with `FileNotFoundException` for the
absent exact pinned GGUF at `build/embedding-inference-probe/nomic-v2.gguf`.
No weights were downloaded or model forward pass performed in this integration.
Supply the exact pinned file with `-PembeddingModelPath=/absolute/path` or opt
into provisioning with `-PembeddingProvision=true` to rerun real inference.
Retained probe pane `%2647`, run `81449068b5f1460a9c9b4309edf9fdf6` (exit 1).

## Limits

No Minecraft/IntelliJ gameplay launch, live Fabric mixin application, GPU draw,
framebuffer-driver test, multiplayer/slow-discovery smoke, cross-platform native
execution or real-weight inference was performed. The missing exact weights
block inference acceptance, not compilation, packaging or host JNI bootstrap.
Actual GPU fog/depth composition, render reload/resize/Fabulous behavior, source
stream timing, deep-walk transitions and collision feel still need live testing.
Uploaded bounds are conservative medium hints, not exact volumetric occupancy;
independent source projections can have genuine gaps. Diagnostic publication is
explicitly not client draw acknowledgement or guaranteed walk/exit readiness.
This candidate integrates only the assigned retained scope; it does not claim
all eleven runtime problems have been gameplay-verified.
