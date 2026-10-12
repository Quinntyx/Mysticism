# Changed/generated source refresh

Scope: retained `feat-runtime-discovery-generation-invalidation` work only. No changes to
per-player projection, terrain meshes/collision, shaders, movement or embedding providers.
The retained source/queue edits were incomplete and did not compile together.

## Behavior

- Edit and chunk load/unload events invalidate an intersecting active observation and retain
  its original discovery point. Staged immutable pages cannot publish after cancellation.
- Chunk-load scans resume frontiers for **every** landmark kind, including intersecting slabs
  whose minimum corner is outside the chunk. Existing known geometry pages are refreshed too;
  generation can invalidate previously incomplete observations, not only cave frontiers.
- Hint FIFO overflow is returned to a conservative, resumable area scan. Retry overflow unions
  same-dimension areas and restarts consumed cursors instead of silently dropping either event.
  Retained memory is <=128 point hints, normally <=64 area retries, plus at most one retry per
  additional loaded source dimension. Unload/stop cancel retained geometry reads.
- Area scans use <=2 metadata records, <=1 geometry page / 512 leaves, or <=8 frontier faces per
  advancement, every five server ticks. Conservative overflow sweeps use 24-block strides for
  25-cube observations and constant-size cursor state. Refresh-only windows may select actual
  nearest generated evidence if their centre is unavailable; exact caller queries still fail
  closed. No chunks are generated/ticketed and unknown cells never become invented air.
- Queue backpressure yields a held geometry read at its last completed page boundary, rather
  than deadlocking an Ensure that needs that same lane. A partially decoded page is not counted
  as complete. Changed geometry references restart that page cursor.
- Source-catalog changes invalidate asynchronous preparation/publication and re-hint the point.
  Activity-only CAS can reuse identical geometry references: publication rebases activity,
  claims, base embedding and CAS revision from current metadata under the activity pause.
- Reading a frontier is not proof of ownership. Unabsorbed faces survive with current revision;
  observed/unabsorbed faces receive a bounded follow-up hint for every kind. Known ownership
  across the entire missing slab is required to retire it. Frontier budget overflow fails
  explicitly rather than silently truncating persisted discovery work.

## Regression/build evidence

`src/runtimeTest/java/io/github/mysticism/landmark/DiscoveryInvalidationTest.java` is in the
**actual current runtime source set**, not the excluded historical `src/test` landmark suites.
The normal main-based runner discovers it automatically. Tests exercise real source preparation,
real paged LandmarkStore mutations, edit/load/unload cancellation, all three landmark kinds,
negative/half-open sweep coverage, slab ownership proofs, activity-only rebasing, topology CAS,
retired headers, overflow cursor restart, and geometry-lane backpressure. No live game, network
embedding endpoint, model download or model inference is used.

Canonical installed Gradle 8.13 / Java 21 command:

```
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

Passed: common/client compilation; all **five discovered runtime suites** (new discovery test:
131551 checks); packaging/descriptor/runner fixtures; remapJar and verifyProductionJar.
Production artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Nonfatal existing warnings: initial CCA source-remapping/Nullable and API Guardian warnings,
deprecated APIs/Gradle features, and intentionally malformed embedding persistence fixtures.

Final source build handle: original tmux session `$11`, origin `%1988`, retained pane `%1995`,
run `e71a669fcb244f3faaa4cea43aae090d`, exit 0. Earlier successful incremental runs are superseded.
An intermediate test compile rejected a fractional fixture claim clock; it was corrected to
an integer. The following build (`f58287578e584b71b2708d371e3f98bf`) hit an intermittent existing
`AsyncEmbeddingCallbackIsolationTest.isolatedTimeouts:40` readiness deadline (120ms), before
running the discovery test. An unchanged canonical rerun passed all five suites. That timing
failure is reported, not hidden by excluding/changing the embedding suite.

## Limits / unverified behavior

Conservative overflow unions of distant areas can increase refresh latency substantially.
Continual generation/edits can restart scans; resource/cell/page/component limits still defer
very large features. Corrupt stored pages and deterministic budget failures are logged, not
retried in a hot loop. Refresh queues are runtime state, not a new persistence schema.
No Minecraft/IntelliJ launch, gameplay, GPU or native-v2 model-inference verification was
performed. This is not a claim that all eleven parent runtime problems are fixed.
