# Dimension-scoped shallow terrain integration

Scope: retained `feat-runtime-sol-merge-0029`, for **dev**, never main. Only dimension-specific
source discovery/support and independently projected shallow custom terrain are covered here;
this is not a claim that all eleven reported runtime issues have been fixed.

## Existing work preserved

Read the existing implementation, merge/index state, approved design, build instructions,
parent handoffs, and final previous-session reports before editing. The checkout was already
merging shallow parent `98b31310a1ed874652ab466bf8699b934d12f769` into dimension parent
`c59156736ac4abb9eff36ab5e715da83375c27f9`. No reset, stash, discarded edits, other-worktree
writes, or changes to dev/main were used.

The `SpiritTerrainService.Session` conflict retains `scanDimension` and `shallowTicks` together;
the obsolete cross-world `dimensionCursor` is not restored. Existing dimension gates on selection,
ownership, target metadata, async results, prepared regions and accepted-frame support are retained.
The source-coordinate octree ownership/growth representation is unchanged.

Shallow distortion now resolves owner metadata with `metadata(id, window.dimension)` and uses
`ShallowTerrainProjection.ownerDisplacement`. That production helper rejects a foreign owner
**before** effective-embedding evaluation, clones its location, applies source offsets through the
retained source basis, and projects through this observer's independent basis/q/carrier. Explicit
cross-dimension targets remain possible within their own validated namespace, never automatic
local discovery. No shared projection, normalized semantic position, source-coordinate mutation,
v1 migration/truncation, embedding-runtime substitution or external prerequisite was introduced.

The original protected five-block walking patch, 40-tick entry ramp, bounded far displacement,
affine model/collision columns, local slab/stair/fence primitives, wire deltas and SAT narrow phase
remain intact. Rendering consumes the same accepted affine cells; the vanilla carrier outline
suppression from the shallow parent is preserved.

## Regression discovery and actual verification

Extended the discovered `ShallowTerrainProjectionTest` with combined dimension/projection checks:
identical foreign coordinates/vectors cannot distort local geometry or evaluate activity;
source and observer bases are distinct; returned vectors are not mutated; a correctly namespaced
explicit target can project; changing source dimension revokes old owner projection; the renderer
and SAT consume the same accepted namespaced geometry. Tests use deterministic fixtures, not real
model inference.

Canonical installed Gradle 8.13 / Java 21 command, no exclusions or diagnostic classpaths:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --rerun-tasks build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 39s`; all 14 actionable tasks executed.
Original tmux connection `/tmp/tmux-1000/default,30794,11`, origin `%2238`, session `$11`,
retained window `@1425`, pane `%2252`, run token `9459d5c5a36240b78739ff7b677087c7`.

`runtimeSelfTest` explicitly discovered and passed all **six** top-level main-based classes:

- `DimensionDiscoverySelfTest`: 151 checks, including 140 compressed-catalogue/ownership checks.
- `ShallowTerrainProjectionTest`: 269 checks (12 added to the parent suite).
- `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
- `AsyncEmbeddingEngineSafetyTest`: 184 checks.
- `EmbeddingPersistenceTest`: 90 assertions.
- `EmbeddingPipelineTest`: 135 assertions.

`buildSelfTest` passed decoded metadata, 14 descriptor rejection cases, positive/negative packaging,
and runner discovery/order/assertion/failure checks. Common/client/runtime compilation, remapJar,
remapSourcesJar and check passed. `verifyProductionJar` verified
`build/libs/mysticism-1.0-SNAPSHOT.jar`. The framework `test` action is intentionally disabled;
it is the main-based runtime suite above that executed. `git diff --check` passed.

## Limits / risks

- No Minecraft client/server launch, GL rendering, multiplayer gameplay, IntelliJ launch or native
  model inference was performed. Static packaging approval does not prove inference.
- Coarse affine approximations can leave distant seams; no artificial collision bridges were added.
- Existing API Guardian/mixin/deprecation warnings and expected malformed-save rejection logs remain.
- Other retained units own movement/entry/walk state, render-distance discovery, teleport coverage,
  fog/Kuwahara and in-process embedding restoration. This integration does not implement or verify
  those unrelated fixes. Fresh native v2/768-dimensional configuration is unchanged.
- The parent dimension handoff records a historically stalled baseline async test in a different
  pane. This integration did not alter that process; its own full canonical candidate run passed
  both async suites. No known build blocker remains for this candidate.
