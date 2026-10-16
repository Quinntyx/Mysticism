# Render-distance, dimension and shallow-mesh integration

Scope: `feat-runtime-sol-merge-0059`, for **dev**, never main. This retained unit
integrates discovery parent `184e86116628115eb25ca0cc3ada5d42b884ff92` and dimension/shallow
parent `dfaca779e544db4bc0aec448cd3e9bbb63a1a2f2`. It does not claim to repair all eleven
runtime issues by itself.

## Preserved work and actual repairs

The checkout already contained their unfinished merge. Existing commits and staged changes
were preserved; the `SpiritTerrainService` conflict was resolved without reset/stash/discard.
Source-dimension catalogue scans, ownership/metadata/target gates and `scanDimension` coexist
with the retained render-distance scheduler, asynchronous readers, bounded completion pipeline,
rectangular batching, frame selection and accepted-frame producer provenance. The obsolete
all-world scan/dimension cursor is not restored.

- Real source discovery retains the client/server effective view-distance horizon, rather than
  falling back to the small near-field capture. Region reads progress through the retained fair
  asynchronous lanes. Materialization remains shared-budget (256 examined cells / one millisecond
  per server tick, 64 per window turn); detached compaction/retention remains bounded-queue worker
  work with no wait or inline fallback. Cancellation and stale-result identity guards remain.
- The new polled completion pipeline now binds its immutable source **dimension** in the actual
  `Window` constructor. A completed region from another dimension or another requested range is
  rejected before registry/shape resolution or worker admission. It grants no coverage, preserves
  prior valid local terrain and schedules a real error cooldown/retry.
- Shallow distortion is applied to the existing `SourceDiscovery.Candidate` affine cell, not a
  reconstruction using legacy `node.side()` cube dimensions. Rectangular source extents, keys,
  ownership, color/light and real local collision primitives survive distortion unchanged. The
  renderer, SAT and wire payload consume the same accepted transformed columns.
- The independent source-basis/observer-basis projection and namespaced owner displacement are
  retained. Distortion ramps after anchoring, is displacement-bounded and protects the full
  five-block walking patch. Whole-cell fade is continuous at that protected boundary, including
  large discovery prisms straddling it. No collision-only bridges or shared per-player frame.

No embedding implementation/policy, source-coordinate ownership/growth representation, or
fresh native v2 configuration was changed. No v1 migration/truncation, dummy production vectors
or external-server prerequisite was added.

## Regression discovery and build evidence

`DiscoveryMeshCompletionSelfTest` now additionally exercises the production completion path with
identical foreign cells, mismatched ranges, rejection before materialization, retained terrain,
coverage cooldown/retry, valid subsequent publication and the real namespaced `Window`
constructor. Its existing multi-player budgets, held worker, stale-result, cancellation and
failure tests remain.

`ShallowTerrainProjectionTest` now coalesces actual source nodes into a 16 x 1 x 8 discovery prism,
applies independent shallow displacement through the production candidate emission method and
checks the distant interior, entry ramp, source identity, protected straddling cells, rendered
vertices, SAT collision and exact wire-delta collision equivalence. Fixture vectors are not
model-inference evidence.

Canonical installed Gradle 8.13 / Java 21 command (via `tmux-jobs`, no exclusions or diagnostic
classpath overrides):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --rerun-tasks build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 23s`, all 14 actionable tasks executed. Retained connection
`/tmp/tmux-1000/default,30794,11`, session `$11`, original pane `%3080`, window `@1831`, build pane
`%3083`, run `32016f1a28154438b79cbf8dadd4cee3`.

`runtimeSelfTest` discovered and passed all **16** top-level main-based classes with assertions:

| Suite | Checks/assertions on this run |
| --- | ---: |
| DimensionDiscoverySelfTest | 151 (includes 140 ownership checks) |
| DiscoveryCancellationSelfTest | 21 |
| DiscoveryFrameSelfTest | 7,982 |
| DiscoveryMeshCompletionSelfTest | 36,254 (includes per-tick checks) |
| ShallowTerrainProjectionTest | 280 |
| SourceDiscoverySelfTest | 296,336 |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| GeneratedSourceReaderMultiChunkTest | 1,036 |
| SourceDiscoveryReadLatencyTest | 14,558 |
| SourceEnsureObservationTest | 14 |
| SourceLaneFairnessSelfTest | 81 |
| SourceOwnershipCursorTest | 542 |
| SourceScheduleSelfTest | 179 |

`buildSelfTest` passed decoded metadata, 14 descriptor rejection cases, packaging (including
19 rejection fixtures), and runner discovery/order/assertion/failure fixtures. Common/client/
runtime compilation, remapping and `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar`. Gradle's framework `test` action is intentionally disabled;
it is the discovered `runtimeSelfTest` classes above that executed. `git diff --check` passed.
Expected malformed-persistence fixture logs, API Guardian/mixin/deprecation warnings remain.
The historical BUILDING.md test layout/runtime migration narrative is not evidence for this
candidate; actual test source sets and discovery are in `scripts/selftest.gradle`.

## Limits / risks

No Minecraft launch, live movement/multiplayer, GPU rendering, IntelliJ launch or native-v2 model
inference was performed. Static packaging approval and deterministic fixtures do not prove any
of those. No known build blocker remains for this scope.

Source views are progressive and budget/retention constrained; vertical discovery remains bounded
at 64 blocks and highly varied far terrain may be evicted/retried. A single modded registry/shape
callback cannot be preempted. Existing near refinement, candidate selection, stitching and transport
still run on the server thread; this is not a hard deadline for the entire terrain tick or proof
of real-world FPS. Coarse affine approximations may leave distant seams and use existing stretched
model textures. Other retained units own unrelated movement, entry/walk, teleport, fog/Kuwahara
and in-process embedding restoration.
