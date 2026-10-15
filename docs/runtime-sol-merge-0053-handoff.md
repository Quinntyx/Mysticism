# Retained runtime integration: merge-0053

Owned checkout/branch: `feat-runtime-sol-merge-0053`. Integration targets dev,
never main. This checkout began mid-merge with retained parents
`e01b8877262e817b89edc0179c9ce0f6fe6d4664` and
`d001560868b2d66fba918e16bde38fe226dfcde5`. Their commits and existing staged and
uncommitted work were preserved; no other checkout or dev/main was modified.
No agents were delegated and no software or model weights were installed.

## Integration and repairs

- Register both client arrival reseeding and carrier-push hooks, and both server
  teleport reconciliation and mesh packet-collision hooks. Keep per-player entry,
  exit, readiness, navigation, motion and walk state; one player's lifecycle must
  not clear another player's readiness or intent.
- Keep teleport epoch **and actual client arrival** prediction/momentum resets,
  server movement rebaselining and shared custom-mesh stepping. Ordinary terrain
  height changes retain shallow-mode descent grace rather than repeated rollback.
- Preserve the guarded affine-transition narrow phase from merge-0006. Endpoint
  AABBs alone must not veto safe tilted-floor alignment, and distant endpoints
  must not bypass a swept-body obstruction. Retain primitive-specific bounds,
  extended shapes, inverse-transpose surface normals and conforming boundaries.
- Use merge-0047's actual `GroundContact` witness in merge-0006's production
  discovery/acquisition transaction. The contacted shape's interior witness maps
  through shared `MeshContact.source`, not an inset player-center approximation.
  Floor selection remains independent of a simultaneous wall collision. Preserve
  producer binding separately from the discovered target's exact source ownership,
  including current geometry-revision checks, body proof and publication guards.
- Combine asynchronous source waits/recovery and source-locality following with
  natural landing, feedback deduplication and explicit retry cooldowns. A suspended
  intent prevents new natural/explicit requests from resetting its recovery budget.
  Resume the same frozen-target intent from the **current** basis; preserve its
  natural/explicit origin and consumed recovery allowance.
- Count real discovery extraction as pending source readiness. Source waits do not
  spend alignment time; unrelated background streaming cannot block ready contact.
  `/spirit` now reports actual alignment, source-wait and automatic-recovery clocks,
  not elapsed wait as impossible `>200/200` alignment progress. `/spirit terrain`
  exposes current mesh evidence separately from semantic/navigation readiness.
- Teardown also cancels async proof/discovery after the recovery clock has already
  disarmed itself on timeout. The frozen-target snapshot still identifies owned
  resources at that point; otherwise expiry silently retained stale work. This
  cleanup is per session and also used when clearing a player session.
- Retain mesh-only body/sneak/packet collision, removing carrier voxel obstacles;
  current custom terrain writes MAIN depth before scene capture. Preserve measured
  uploaded geometry/medium evidence and painterly terrain -> volumetric fog ->
  saturation -> transition composition without feeding filtered color back into
  fog's immutable source scene.
- Keep the default embedded CPU/JNI provider, desktop/ISA preflight and actionable
  errors, pinned native-v2 assets and full 768-dimensional output. No v1 migration,
  vector truncation, dummy model or external-server prerequisite was introduced.
  Independent deep/shallow projections and source-coordinate octree ownership and
  growth remain intact.

## Regression evidence

The existing main-based runner discovers **38** current runtime suites, with
assertions enabled. Tests exercise production geometry, discovery/candidate/
acquisition transaction seams, timing/policy and compiled hook targets. They are
not a live Minecraft bootstrap, network simulation or GPU test.

Merge-specific coverage includes:

- Actual ground witnesses in `WalkDiscoveryRuntimeTest` (300 checks), retaining
  producer-vs-target ownership, stale revision, body proof and acquisition cases.
- `NaturalLandingTriggerTest` (73 checks): suspended intent cannot repeatedly trigger natural
  requests or reset the recovery allowance; another player's landing is unaffected;
  expired recovery cancels resources even after active/armed flags are cleared.
- `NavigationReportTest` (18 checks): real production clock source waits,
  suspension and resumed alignment, in addition to discovery/anchor distinctions.
- `WalkTargetAcquisitionTest` (27 checks): shared contacted-cell inverse mapping
  wiring replaces the obsolete private `sourcePoint` method assertion.
- Retained per-player lifecycle (28), teleport arrival reconciliation (87), mesh
  stepping (76), continuous affine transitions (4768), deep walk contact premise
  (299), source readiness/recovery, mesh evidence and authority, shader composition,
  embedding lifecycle/persistence and isolated packaged CPU/JNI suites all execute.

## Actual checks

Original tmux connection `/tmp/tmux-1000/default,30794,11`, session `$11`, origin
`%2802`. All long commands use the tmux-jobs helper with explicit argv, bounded
monitoring and retained pane/run tokens.

```sh
./gradlew --no-daemon --console=plain --continue --rerun-tasks build
```

An initial final run passed: exit 0, **BUILD SUCCESSFUL in 46s**, all **15**
actionable tasks executed, **38** runtime suites discovered/executed, common/client
compilation, build fixtures, remapping and `verifyProductionJar` passed.
Pane `%2815`, run `4924b7d23ee7442b82eb981aea777d8f`.
The **final forced build** also passed: exit 0, **BUILD SUCCESSFUL in 1m 2s**,
all **15** actionable tasks executed and **38** runtime suites passed, including
all 73 natural-landing trigger checks and final expired-recovery resource cleanup.
Pane `%2815`, run `eb4e5a6852114b9f8d492385919d30c4`.
The preceding run `4f2a1584305a4b50b2fee5161e47d036` failed test compilation
because the new cleanup fixture used a nonexistent no-arg vector constructor;
that fixture now uses `Vec384f.ZERO()` and is included in this final passing run.
`git diff --cached --check` passed; no unmerged index entries or conflict markers
remain. Current discovery roots independently enumerate 38 main-based suites.

Packaging fixtures include decoded metadata, 14 descriptor-only rejections,
valid-archive acceptance, 27 invalid-artifact cases and runner discovery/order/
assertions/failure/missing-main/empty-suite behavior. The framework `test` task is
intentionally disabled under the project's main-based test contract, not omitted
coverage. Production artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe
```

**BLOCKED/FAIL**, exit 1, **BUILD FAILED in 39s**. Actual host CPU/JNI preflight
passed `b10682-5ea1b124e`, then `FileNotFoundException`: exact pinned GGUF missing
at `build/embedding-inference-probe/nomic-v2.gguf`. No model forward pass or
weight download occurred. Pane `%2894`, run `1144303ef44142fa91dfbe6913ba709d`.
Supply the exact pinned file via `-PembeddingModelPath=/absolute/path` or explicitly
opt into weight provisioning with `-PembeddingProvision=true` for real inference.

## Limits and remaining acceptance work

No live Minecraft/IntelliJ client/server, multiplayer/slow-discovery gameplay,
Fabric mixin application, GPU/depth-driver execution, render resize/reload or
cross-platform JNI execution was verified. Real-weight inference remains blocked
by the absent pinned model, not compilation or host native bootstrap. Existing
API Guardian/deprecated API, Gradle, Jackson version/semver and test-classpath
SLF4J warnings remain; corruption-fixture exceptions are intentional test output.

Terrain publication is not client draw acknowledgement or guaranteed exit/walk
readiness. Uploaded bounds are conservative fog-medium hints, not exact volumetric
occupancy. Independent source projections may have genuine gaps. Automatic recovery
is bounded (600 source-wait ticks, 1200 suspended ticks, at most three recoveries),
not an indefinite promise to land on unavailable geometry. No substitute floor or
source-coordinate snap is used. These checks do not establish that all eleven
umbrella problems have been resolved in gameplay.
