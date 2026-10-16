# Vertical source discovery (retained runtime unit)

## Scope and behavior

The previous chunk-load path hinted only the center heightmap surface. The retained
partial implementation added underground columns but used a four-block vertical
stride and kept only two bands: thin air layers could be missed and higher caves
were silently discarded. This unit completes that path without replacing Ensure,
source-coordinate ownership, per-player projection, terrain meshes or embeddings.

- Chunk load keeps the existing surface hint and schedules scans of sixteen
  independent heightmap columns at local X/Z 2, 6, 10, 14.
- A resumable scan tests **every Y** above the world floor and below each column's
  heightmap minus four blocks, clamped to world top. It retains one air candidate
  per absolute 32-block vertical band (negative Y uses floor division).
- Selection is deterministic: lowest Y, then X, then Z. All represented bands,
  rather than just two deepest bands, enter the existing Ensure hint pipeline.
- Chunk load does not synchronously read thousands of blocks. Server ticks spend
  at most 128 vertical reads; these share the existing 512-cell source observation
  budget and are included in `lastSampledCells`.
- Up to 64 full chunk scans hold detached coordinates, not live chunk objects.
  Overflow retains deduplicated chunk/dimension keys in FIFO order; at most one
  deferred key is promoted per tick, snapshotting its sixteen heightmap columns
  only if still loaded. New loads cannot bypass older deferred work. Key memory
  is bounded by pending resident source chunks, not a second fixed queue cap
  that could silently lose discovery. Chunk/dimension unload and server shutdown
  remove both active and deferred work; non-loading misses drop stale keys.
- A full 128-entry hint queue pauses block reads and retains completed candidates
  until they can be drained. Live lookups use `getWorldChunk`, not loading/generation,
  ticket creation, source mutation or blocking disk/model work. Deferred scans
  contribute to `SourceLandmarks.pending`; their reads share the existing budget.
- A candidate is not ownership or proof of enclosure. Existing source extraction
  still checks connected air, sky escape, biome boundaries and exact wall masks;
  cave seeds use their actual source Y and are not snapped to ground or a chunk.

## Regressions and verification

`src/runtimeTest/java/io/github/mysticism/landmark/VerticalDiscoveryTest.java` is
in the current Gradle runtime discovery set, not the historical prototype suite.
It exercises all vertical stride residues, negative bands, deep/middle/high caves,
per-column heightmaps, strict resumable read limits, deterministic selection,
full-queue retention, solid/empty/world-top cases and invalid inputs. Three cave
seeds also run through the real `SourceLandmarks.prepare` extraction path, checking
source anchors and exact air-plus-six-wall geometry. No fake embedding vectors or
live inference are used.

`src/runtimeTest/java/io/github/mysticism/landmark/VerticalSchedulerTest.java`
exercises the actual scheduler used by `SourceLandmarks`: 193 initial chunk loads
(64 active, 129 deferred), duplicate events, a later arrival during draining,
FIFO eventual discovery at deep/middle/high levels without new load events,
one promotion per tick, exact shared read accounting, hint backpressure,
active/deferred chunk and dimension unload, stale non-loading misses, reload
and shutdown cleanup. A build-local mutation restoring overflow discard is
rejected by this regression; tracked production sources are never mutated.
Mutation-check handle: pane `%3082`, run `ec22ef52a3c44edc90f39e65bdf82812`
(exit 0: expected assertion failure observed).

The final canonical Java 21 Gradle 8.13 wrapper build passed (exit 0, 3m 49s):

```sh
./gradlew --offline --no-daemon --console=plain --max-workers=2 --rerun-tasks \
  build runtimeSelfTest buildSelfTest verifyProductionJar
```

All 14 actionable tasks executed. Runtime discovery reported six main-based
classes, including `VerticalDiscoveryTest` with 8,900 checks and
`VerticalSchedulerTest` with 43,324 checks. Existing embedding suites (28, 184,
90 and 135 checks), packaging/runner rejection fixtures and the remapped
production artifact verification passed. Expected invalid-persistence fixture
warnings and existing API Guardian/mixin/deprecation warnings remained.
Retained tmux build handle: pane `%3084`, run
`a52c6b605915453ebe2edf25c1462ade`, original pane `%2336`, session `$11`.
Checks were rerun after session continuation; the final handle above was
captured with exit 0.
The exact committed candidate HEAD is in the task report; compiled/packaging
verification is not gameplay verification.

## Limits / integration risks

This is bounded opportunistic discovery, not an exhaustive cave census: cavities
that miss all sixteen X/Z columns, multiple disconnected caves within one band,
and already-loaded chunks without a new load event are not guaranteed discovery.
Overflowing the 64-scan cap no longer loses discovery: a finite loaded backlog
is eventually scanned if the hint sink drains. It retains one lightweight key
per waiting loaded chunk, so memory scales with resident source chunks, while
full scans and tick admission/read work remain capped. No tickets keep waiting
chunks resident; unloading cancels discovery until another load. The existing
near-player hints and explicit Ensure requests remain available. A tall first
queued chunk can delay later chunks; stalled embedding readiness can keep the
hint sink full. Inference and source growth still have their existing budgets.
Surface-adjacent/sky air remains the existing surface path's responsibility.

No client/server gameplay launch, render-distance performance measurement or real
model inference was performed for this unit. The approved projection, source
ownership/growth, collision/render consistency and fresh native v2 embedding
contracts were left intact. BUILDING.md contains older integration-era notes;
current suite wiring is authoritative in scripts/selftest.gradle.
