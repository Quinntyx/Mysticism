# Render-distance discovery throughput follow-up

Branch: `feat-runtime-sol-merge-0059`, for integration into **dev**, never main.
Retains integration base `ae7bc7ac736f83ffa8e62df93e7c224e5833e4d7` and its parents.

## P1 repair

The old completion allowance counted every source cell, including air, against 64
cells per window per tick. A 32³ observation therefore occupied an admission slot
for at least 512 ticks before compaction. Simply enlarging the planner did not
provide practical horizon discovery.

- `DiscoveryMeshPipeline` indexes real immutable observations on the bounded mesh
  worker: bounds validation, air elimination, deduplication and material grouping.
  Only owner-thread registry/shape evaluations consume the existing shared 1 ms /
  256 evaluations-per-tick allowance (64 per window). Reuse is per material per
  observation and **only** for Minecraft's non-dynamic shape contract, not a guess
  based on two equal shapes. Dynamic shapes retain per-position evaluation.
- Expansion back to exact observed source positions, compaction and retention run
  detached. No coarser invented source cubes, collision truncation or inline
  fallback. The two request slots remain occupied through actual publication;
  saturation retries, invalidation, dimension/range checks and stale-work guards
  cover both indexing and mesh work.
- `GeneratedSourceReader.advanceDiscovery` batches up to four async saved-chunk
  scans/decodes, without copying saved voxels across owner-thread polling turns.
  Live sampling shares **one** 1.5 ms deadline across chunks of a discovery turn;
  live material descriptors are reused per state. Saved section palettes decode
  descriptors once per palette entry, avoiding per-voxel property-map allocations.
  Movement reads retain their urgent lane and small sampling allowances.
  Ungenerated/malformed/missing source data remains unknown, never invented air.
- `SourceDiscovery.discoveryOrder` bootstraps actual source tiles along eight
  bearings at the horizon and successively halved distances, then fills the whole
  center-out volume. It preserves exactly the original plan's tile set. Actual
  source-point quantization fixes signed-tile asymmetry at negative diagonals.
  Near reads continue to protect the movement patch independently.

No changes to source-coordinate octree ownership, dimension namespaces,
independent deep/shallow projection, shared render/collision geometry or native
v2 embeddings. No migration, vector truncation, dummy vectors or server dependency.

## Regressions and checks actually performed

`DiscoveryCoverageLatencyTest` drives the real horizon scheduler, Session /
DiscoveryRead fairness, GeneratedSourceReader saved-NBT path, indexing, bounded
shape resolution, compaction, retention and mesh publication. Test-only ChunkIo
supplies generated flat-ground NBT; the registry adapter is replaced with a static
stone tile. Worker tasks execute on separate real threads with test-only
completion at tick boundaries. These are **simulated tick latency** checks, not
storage timings, real-world throughput, FPS or gameplay verification.

Observed in the passing canonical build:

| View | Published tiles | Simulated ticks | First +X horizon | All eight bearings | Urgent read max |
| --- | --- | --- | --- | --- | --- |
| 8 chunks | 405 / 405 (whole plan) | 2029 | 69 | 107 | 3 ticks |
| 32 chunks | 20 / 5445 (bootstrap only) | 102 | 72 | 102 | 3 ticks |

The full eight-chunk test enforces completion within 3000 ticks, all bearings
within 320, first horizon within 160, bounded queues and real distant SAT
collision. It required only 162 owner-thread static shape evaluations for the
whole view. Dense completion at 32 chunks is **not** measured or claimed.

`DiscoveryMeshCompletionSelfTest` verifies 32768 checkerboard source cells survive
exactly after only two static palette evaluations, plus dynamic per-position
fallback, air handling, deadlines, shared multi-window budgets, held/rejected
workers, late indexing cancellation, mesh cancellation and replan publication.
`GeneratedSourceReaderMultiChunkTest` covers accelerated multi-chunk material /
biome / sky fidelity across an ungenerated hole, worker saturation, shared palette
descriptor identity, partial-live-to-saved replacement without duplicate positions,
and cancellation. Existing dimension and shallow projection
suites remain discovered and pass. SourceDiscoveryReadLatencyTest verifies
sustained urgent reads at no more than four scheduler ticks under discovery and
ownership pressure.

Canonical verification:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --rerun-tasks build
BUILD SUCCESSFUL in 26s; 14 actionable tasks: 14 executed
Feature self-tests passed: 17 main-based classes
Production jar verified: build/libs/mysticism-1.0-SNAPSHOT.jar
```

Runner discovery was also checked against all 17 compiled top-level `*Test.class`
files; the fixture helper does not masquerade as a test. Packaging fixtures,
runner-discovery/failure fixtures and remapped-jar verification ran in `build`.
`git diff --check` passed. Gradle 9 deprecation and missing optional annotation
warnings remain; negative embedding persistence fixtures deliberately log rejected
corruption and pass. No live embedding engine or Minecraft startup was performed.

Retained final build handle: original session `$11`, origin `%3112`, job pane `%3118`,
run `4eb8ad97a9d54859be6a6e5640d0a79e`, exit 0.

## Remaining validation limits

Actual live-chunk sampling speed, GC pressure, disk/worker contention, modded
position-dependent blocks, multi-player wall time and rendered client horizon need
gameplay profiling. The design is progressive, not instantaneous: dense maximum
view-distance coverage still costs more work, bounded by existing memory and queue
budgets. This follow-up does not claim GPU rendering, native-v2 model inference or
verification of all eleven runtime problems outside the retained assigned scope.
