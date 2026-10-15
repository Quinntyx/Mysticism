# Dimension-specific runtime discovery

Scope: `feat-runtime-discovery-dimension-discovery`, intended for integration into **dev**, not main.

## Behavior

- Automatic terrain sweeps use the player's retained source dimension, not the spirit carrier's
  dimension and not every loaded world. Representative hysteresis resets when that namespace changes.
- Bounded semantic source selection filters foreign metadata before invoking effective-embedding
  callbacks. Foreign records still advance exclusive cursors and count against scan budgets.
- Landmark alias lookup, restored bindings, walk proof, source anchoring, target validation,
  asynchronous region results and ownership snapshots must match their declared source dimension.
  Invalid restored IDs lose semantic authority and rediscover locally; physical pose/mode and
  explicit target state are retained.
- Old prepared/held discovery windows cannot render, collide, or grant local support after
  source-dimension acquisition changes. A separately validated explicit cross-dimension target
  remains available in its own namespace; cross-dimension travel is not globally disabled.
- Per-player projection, source-coordinate octree ownership, source growth, shared render/collision
  frames and native v2 embedding representation are unchanged. No v1 migration, model/vector
  truncation, native engine changes or external service requirement is introduced.

## Regression and actual verification

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/DimensionDiscoverySelfTest.java`
uses the production representative selector and namespace gate, plus the real compressed landmark
catalogue and bounded ownership traversal through `DimensionOwnershipChecks`. It cold-reloads
162 landmarks with identical vectors across dimensions; checks cursor progress and budgets,
no foreign embedding callbacks or geometry hydration, namespaced metadata/targets, independent
SOLID/AIR ownership at identical coordinates, unknown masks, dimension switches/hysteresis and
explicit-target exception. Synthetic vectors are test fixtures only; no inference is represented.

Final canonical command (no task exclusions or diagnostic classpath):

```sh
./gradlew --no-daemon --console=plain build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 20s`. Original tmux origin `%1797`, retained pane `%1832`,
run token `a022f2ddc13448e291296fb9359fc27b`.

Normal `runtimeSelfTest` discovered and ran all five top-level main-based classes:

- `DimensionDiscoverySelfTest`: 151 checks (including 140 ownership/catalogue checks).
- `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
- `AsyncEmbeddingEngineSafetyTest`: 184 checks.
- `EmbeddingPersistenceTest`: 90 assertions.
- `EmbeddingPipelineTest`: 135 assertions.

`buildSelfTest` passed decoded metadata, descriptor, packaging positive/negative and runner
(discovery/order/assertions/failure) suites. `verifyProductionJar` verified
`build/libs/mysticism-1.0-SNAPSHOT.jar`; `remapJar`, `remapSourcesJar`, common/client compilation
and `check` passed. Persistence warnings/exceptions are expected malformed-input rejection
fixtures. API Guardian annotation/deprecation warnings remain nonfatal.

Independent scoped run also passed compilation, packaging, production-jar verification and
151 regression checks while listing all five discovered runtime classes. Retained pane `%1823`,
run token `27b11faea17c4cad85bc004453177545`; command used installed Gradle 8.13 and a local,
ignored `build/dimension-discovery-check.gradle` helper. Canonical build above did not use it.

## Limits / residuals

- No client/server gameplay, render/fog, IntelliJ launch or native model inference was performed.
  This unit addresses dimension isolation only, not the other independently assigned runtime fixes.
- An earlier pre-edit baseline build remains stalled in the existing
  `AsyncEmbeddingCallbackIsolationTest` (pane `%1814`, run token
  `309c354b5150446b9c5b1c3bd4f06347`). It was not canceled or presented as passing. The same
  test passed in the final canonical candidate run; timing-dependent baseline behavior is outside
  this scope and remains worth investigation.
- Metadata scans remain bounded catalogue scans, not a new dimension index; a large foreign
  catalogue can take more ticks to traverse, but cannot supply local support.
