# Runtime source chunk unload/reload

Scope: source discovery/locality only; no navigation, projection, mesh/collision,
shader or embedding-provider changes. Retained baseline `bfbce90` and the existing
untracked `.pi-build-baseline.log` are preserved.

## Behavior changes

- Chunk unload no longer cancels an active source observation. It is an IO adapter
  transition, not source deletion. The existing generated-only vanilla saved-NBT
  scanner (including pending saves) supplies unloaded data without generation,
  loading tickets, writes or model work on tick.
- An unfinished live chunk slice is discarded before saved rows replace it.
  Missing saved cells stay UNKNOWN, rather than retaining partial stale live proof.
  Other completed chunk slices remain intact.
- Live reload supersedes a pending, failed or partially merged saved scan. The
  abandoned future is cancelled and the current slice restarts. A changed live
  chunk identity also restarts it, so adapter snapshots are not mixed.
- Reload retries cover BIOME, CAVE and MOUNTAIN frontiers. Intersecting frontier
  bounds seed inside the loaded intersection, not outside it. Retries retain a
  rejected seed while the hint queue is full. Duplicate pending hints/retry bands
  are coalesced; the existing 8-face/2-metadata per-step budgets remain.
- Source edit, dimension unload, caller cancellation and server stop still
  invalidate/cancel work. Canonical source IDs, anchors, octree material masks,
  ownership, revisions and full native embeddings retain their existing contracts.

The only new implementation seams are package-private reader adapters and retry
callbacks, used by production as well as deterministic runtime regressions.

## Discovered coverage

Both new main-based suites live in `src/runtimeTest/java`, **not** the historical
`src/test/java` tree excluded by the current build configuration.

- `GeneratedSourceLifecycleTest`: 55 checks. Partial live-to-saved transition,
  incomplete/absent saved data, pending/partial/failed saved scan superseded by
  reload, changed live identity, per-step budgets, cancellation, negative/cross-
  chunk coordinates, actual vanilla NBT decoding, absent section/biome/height
  proof, non-FULL status and missing packed data.
- `SourceFrontierReloadTest`: 723 checks. Real compressed PersistentState reload,
  every feature kind, a 144-record catalogue, metadata-only bounded retries,
  foreign/outside frontier exclusion, intersection seeding, repeated reload and
  queue backpressure. Actual source preparation + octree persistence + exact
  ownership demonstrate connected growth across the x=16 chunk boundary under
  the same ID/anchor, retaining walls and known air without owning unknown cells.

## Checks performed

Installed Gradle 8.13, Java 21, offline; no software/model installation. Canonical
build and every discovered runtime/packaging suite, without exclusions or diagnostic
classpath overrides:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
  --offline --no-daemon --console=plain --continue
  build buildSelfTest runtimeSelfTest verifyProductionJar
```

Exit 0, `BUILD SUCCESSFUL`. `runtimeSelfTest` discovered and passed six classes:
the four retained embedding suites plus the two new source suites. Packaging,
runner discovery/order/assertion/failure fixtures, remapping, and the production
jar verifier passed. Expected corruption warnings from embedding persistence
negative fixtures do not indicate a failed suite. `git diff --check` passed.
The remapped artifact is `build/libs/mysticism-1.0-SNAPSHOT.jar`.

The final build pane/run token and exact candidate HEAD are included in the
completion report; panes are retained under original tmux session `$11`, origin
`%1872`.

## Honest limits

No live Minecraft client/server, real chunk IO lifecycle, multiplayer movement,
GPU/render-distance behavior or model inference was exercised. Reader lifecycle
uses a deterministic adapter; NBT decoding and store reload tests use real Minecraft
classes/serialization. The other ten runtime repair areas are not claimed fixed by
this scoped branch. No embedding migration/truncation/provider change was made.

Unknown/non-FULL/malformed saved data still fails closed. Rapid repeated adapter
changes can delay a bounded read; source edits still require a retry after cancellation.
The existing 64 retry-band/128 hint limits and serial source-operation queue remain;
more than 64 distinct simultaneous reload bands can be deferred/dropped at admission.
Cold metadata/page IO is bounded by records, not a hard wall-clock tick deadline;
this patch does not make discovery globally unlimited or guarantee terrain support
before source proof becomes available.
