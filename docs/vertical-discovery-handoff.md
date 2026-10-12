# Vertical source discovery (retained runtime unit)

## Scope and behavior

The previous chunk-load path hinted only the center heightmap surface. The retained
partial implementation added underground columns but used a four-block vertical
stride and kept only two bands: thin air layers could be missed and higher caves
were silently discarded. This unit completes that path without replacing Ensure,
source-coordinate ownership, per-player projection, terrain meshes or embeddings.

- Chunk load keeps the existing surface hint and snapshots sixteen independent
  heightmap columns at local X/Z 2, 6, 10, 14.
- A resumable scan tests **every Y** above the world floor and below each column's
  heightmap minus four blocks, clamped to world top. It retains one air candidate
  per absolute 32-block vertical band (negative Y uses floor division).
- Selection is deterministic: lowest Y, then X, then Z. All represented bands,
  rather than just two deepest bands, enter the existing Ensure hint pipeline.
- Chunk load does not synchronously read thousands of blocks. Server ticks spend
  at most 128 vertical reads; these share the existing 512-cell source observation
  budget and are included in `lastSampledCells`.
- Up to 64 pending chunk scans hold detached coordinates, not live chunk objects.
  A full 128-entry hint queue pauses work and retains completed candidates until
  they can be drained. Live lookups use `getWorldChunk`, not loading/generation,
  ticket creation, source mutation or blocking disk/model work. Chunk/dimension
  unload and server shutdown remove scans.
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

The canonical Java 21 Gradle 8.13 wrapper build passed (exit 0, 20 seconds):

```sh
./gradlew --offline --no-daemon --console=plain --max-workers=2 --rerun-tasks \
  build runtimeSelfTest buildSelfTest verifyProductionJar
```

All 14 actionable tasks executed. Runtime discovery reported five main-based
classes, including `VerticalDiscoveryTest` with 8,900 checks. Existing embedding
suites, packaging/runner rejection fixtures and the remapped production artifact
verification passed. Existing API Guardian/mixin/deprecation warnings remained.
Retained tmux build handle: pane `%2016`, run
`b57e20ede1084ec281c83702de520602`, original pane `%1973`, session `$11`.
The exact committed candidate HEAD is in the task report; compiled/packaging
verification is not gameplay verification.

## Limits / integration risks

This is bounded opportunistic discovery, not an exhaustive cave census: cavities
that miss all sixteen X/Z columns, multiple disconnected caves within one band,
chunks overflowing the 64-entry scan queue and already-loaded chunks without a
new load event are not guaranteed discovery. The existing near-player hints and
explicit Ensure requests remain available. A tall first queued chunk can delay
later chunks; inference and source growth still have their existing budgets.
Surface-adjacent/sky air remains the existing surface path's responsibility.

No client/server gameplay launch, render-distance performance measurement or real
model inference was performed for this unit. The approved projection, source
ownership/growth, collision/render consistency and fresh native v2 embedding
contracts were left intact. BUILDING.md contains older integration-era notes;
current suite wiring is authoritative in scripts/selftest.gradle.
