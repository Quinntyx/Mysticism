# Retained runtime integration 0051

Target: `feat-runtime-sol-merge-0051`, for **dev**, never main. This completes the interrupted merge of `062e15f2c2bb07d196cfd2f86f9f09244f0fc17b` and `255e69d931ee3b5e2ff267b5fb265b46c7bf6368`; both histories and the existing staged merge edits are retained.

## Conflict decisions and additional repairs

- Retain the recent-own-frame free-flight claim replay and landing-contact movement core together. The public deterministic `simulate` entry uses the same core as live entity movement. Walking verdicts, stepping, grounded contact and supported landing remain; no block terrain or global/shared projection is substituted.
- Register **both** `SpiritFlightPredictionMixin` and `SpiritMeshMovementMixin`, along with the travel/accessor/toggle hooks. Walking's moved-wrongly short-circuit must open to the real mesh verdict without removing flight's separate recent-frame swept validation. Source-world movement delegates to vanilla.
- Compose persisted geometry, current sampled AIR/SOLID, retained negative masks and current source-coordinate ownership in that order. Ownership refresh/compaction and shallow discovery publication apply the same exact masks. UNKNOWN cells retain real persisted geometry; masks do not grant landing/exit permission.
- Repair exact subtraction for **unaligned octree roots after growth**. A full globally aligned 16-cube AIR bucket cannot erase an unaligned leaf spanning neighbouring buckets. Query intersecting buckets and scan only small leaves; use the full-bucket shortcut only for an actually aligned leaf.
- Preserve the noncommutative source-key hash (including reflected coordinates), bounded spatial discovery/publication reservoirs, actual client render-distance acquisition and round-robin discovery cursor. Retain per-producer deep distortion, mesh render/collision agreement, visual controls, fresh-profile real landmark reload, accepted-movement handoffs, elapsed walk expiry, entry recovery, motion continuity and seamless entry from both parents.

## Regression coverage

`SourceMeshOwnershipTest` now combines full retained AIR coverage with an unaligned grown persisted root, live AIR/SOLID overrides, changed exact ownership and subsequent re-compaction after tile eviction. Every source voxel is checked for loss/resurrection and ownership.

`SpiritIntegratedMixinApplicationTest` now applies the production walk, flight, toggle, collision, sneaking, travel and accessor registrations together to actual mapped Minecraft classes, and both entry hooks on the client. It verifies that walking's collision-method injection and short-circuit redirect coexist with flight's redirects and accepted-move recording; transformed packet/hook stack flow is checked with ASM. This is actual offline Mixin application, not a game launch.

## Verification

Canonical command, cached Gradle 8.13 / Java 21, no installs or test exclusions:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --continue build
```

Initial integrated run passed all **35** discovered main-based runtime suites, packaging/descriptor/runner fixtures, compilation, remapping and production-jar verification. Original tmux session `$11`, origin `%2688`, initial build pane `%2697`, run `08abf66e8d07401cac691cbbd9db6b9e`.

Final verification: **BUILD SUCCESSFUL**, exit **0**, 3m 57s, all **35** discovered runtime main classes executed with assertions, `buildSelfTest` (metadata, descriptor, packaging, test-runner discovery/failure fixtures), remapping and `verifyProductionJar` passed. New ownership regression executed inside `SourceMeshOwnershipTest` (**255,862 checks** total); the combined-hook test applied all selected common hooks and both client entry hooks to real mapped Minecraft bytecode. Additional tests cover real distorted SAT geometry, per-player isolation, persistence, discovery, supported landing, movement/frame handoffs, flight replay and entry recovery. Earlier verification caught and corrected undefined helper names in the new test; it was not a passing run.

Final retained pane **`%2782`**, run **`cb5c4f14190e44d0a998546fe9e45cc0`**, session `$11`, origin `%2688`. That pane also contains earlier failed-run scrollback: the final token's terminal exit and final BUILD SUCCESSFUL are the result. Transient helper workspace-lock errors did not cancel the build. Production artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.

The Gradle framework `test` task is disabled by the project's main-based runner contract and is not counted as test coverage.

## Honest limits / broader-request blocker

No live Minecraft, GPU/shader, multiplayer latency, IntelliJ launch or real model inference was performed. Bounded discovery is progressive coverage, not unlimited complete resident geometry. Real movement/entry/render quality still needs playtesting. The five-second source-view entry snapshot can expire during a long stall. Existing annotation/deprecation/CCA source-remapping warnings and deliberate malformed-embedding fixture logs are not hidden.

**In-process embedding restoration is not part of these retained merge parents.** `EmbeddingService` still uses the existing HTTP Ollama provider. The native-v2 768-coordinate/fresh-profile contract was preserved, not replaced with v1 migration, truncation or dummy production vectors. A successful build does **not** satisfy the broader request's no-external-server requirement, nor verify real model inference. That remains a real follow-up blocker outside this assigned retained scope.
