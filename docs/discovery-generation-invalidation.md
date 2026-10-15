# Changed/generated source refresh

Scope: retained `feat-runtime-discovery-generation-invalidation` work only. No changes to
per-player projection, terrain meshes/collision, shaders, movement or embedding providers.
The retained source/queue edits were incomplete and did not compile together.

## Behavior

- Edit and chunk load/unload events invalidate an intersecting active observation and retain
  its original discovery point (the whole observation window for background refresh). Staged
  immutable pages cannot publish after cancellation.
- Chunk-load scans resume frontiers for **every** landmark kind, including intersecting slabs
  whose minimum corner is outside the chunk. Existing known geometry pages are refreshed too;
  generation can invalidate previously incomplete observations, not only cave frontiers.
- Hint FIFO overflow is returned to a conservative, resumable area scan. Retry overflow unions
  same-dimension areas and restarts consumed cursors instead of silently dropping either event.
  Retained memory is <=128 point hints, normally <=64 area retries, plus at most one retry per
  additional loaded source dimension. Unload/stop cancel retained geometry reads.
- Sweep coverage alone is not discovery: refresh preparation finds unowned AIR/sky components
  even when the coalesced window is centred in solid stone. It commits one component at a time,
  refreshing real centre changes first, then retains the **entire original observation area**
  for follow-up. Subsequent overflow/coalescing cannot drop skipped edge cave components by
  retaining only a shifted centre. Exact caller destinations are not substituted.
- Generated immutable geometry page identities include deterministic source-fragment/content
  digests, independent of CAS revision. Cancellation may leave unreachable staged pages, but
  changed terrain at the same uncommitted revision gets a different page key. Landmark seeds,
  anchors, committed CAS rules, old readable page keys and the native-v2 save profile are unchanged.
- Area scans use <=2 metadata records, <=1 geometry page / 512 leaves, or <=8 frontier faces per
  advancement, every five server ticks. Conservative overflow sweeps use 24-block strides for
  25-cube observations and constant-size cursor state. Refresh-only windows may select actual
  nearest generated evidence if their centre is unavailable; exact caller queries still fail
  closed. No chunks are generated/ticketed and unknown cells never become invented air.
- Queue backpressure yields a held geometry read at its last completed page boundary, rather
  than deadlocking an Ensure that needs that same lane. A partially decoded page is not counted
  as complete. The completed-page cursor stays paired with its captured immutable key list;
  revision-only Ensure publications cannot restart the scan. Even genuine replacement/reordering
  cannot reinterpret the saved index against a different list. These pages emit source refresh
  hints only, never authoritative ownership or collision; normal consumers still check currentness.
  Edit/load invalidation continues to retain separate work for changed/new source evidence.
- Source-catalog changes invalidate asynchronous preparation/publication and retain the point
  or, for background refresh, its entire discovery window even before skipped components commit.
  Activity-only CAS can reuse identical geometry references: publication rebases activity,
  claims, base embedding and CAS revision from current metadata under the activity pause.
- Reading a frontier is not proof of ownership. Unabsorbed faces survive with current revision;
  observed/unabsorbed faces receive a bounded follow-up hint for every kind. Known ownership
  across the entire missing slab is required to retire it. Frontier budget overflow fails
  explicitly rather than silently truncating persisted discovery work.

## Latest P1 cursor-progress repair: regression/build evidence

A retry now resumes the same committed immutable page list after geometry-lane backpressure and
finishes it even if its own Ensure publications advance metadata/page revisions. Completion no
longer rewinds the catalogue because the hint snapshot is stale. Retired/aliased queued identities
are skipped; explicitly extended areas still restart their consumed coverage. The store adds a
package-private captured-list resume path without weakening `GeometryRead.isCurrent()` or changing
public current-geometry reads, persistence keys, ownership validation or embedding profiles.

The added regression uses **130 geometry pages and the actual 128-entry hint capacity**. It
interleaves real source preparation/Ensure publications both during a read and after pressure
releases the lane, including revision-only and genuine material/key-order changes. It cold-reopens
the store before resuming, asserts every captured page is visited exactly once, reaches all sweep
windows within a fixed work bound, then discovers a tail cave beyond the original landmark and
verifies its AIR/support ownership after another cold reopen. It also verifies a stale hint read
still reports `isCurrent() == false`, so it cannot masquerade as authoritative geometry.

Canonical Gradle 8.13 / Java 21 `--no-daemon --console=plain build` passed: all **five actually
discovered runtime suites**, **132920 DiscoveryInvalidationTest checks**, packaging/descriptor/
runner fixtures, remapJar and verifyProductionJar. No exclusions, live game or model inference.
Build handle: original tmux session `$11`, origin `%2625`, retained pane `%2649`, run
`450f22edfd7842e392ef3ea6549ccec6`, exit 0. `git diff --check` passed. Existing intentionally
malformed embedding-persistence fixture warnings and deprecated API/Gradle warnings remain nonfatal.

## Earlier cancellation/component review repair: regression/build evidence

Added runtime regressions cover edit/load/unload cancellation after geometry staging, both new
and existing seeds, same-revision changed-material publication and exact AIR ownership after cold
save/reopen. Page identities are checked for deterministic insertion order, revision independence
and fragment isolation. A real bounded hint/retry overflow is driven through source preparation,
paged publication, repeat area coalescing and cold ownership reads: both disconnected underground
caves must own their actual AIR and walls, not merely lie inside an observed window.

The canonical installed Gradle 8.13 / Java 21 `--no-daemon --console=plain build` passed,
including all **five actually discovered runtime suites**, packaging/descriptor/runner fixtures,
remapJar and verifyProductionJar. `DiscoveryInvalidationTest` passed **132056 checks**; runner
output explicitly lists it. Production artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Static `git diff --check` passed. No suites or packaging checks were excluded.

The initial helper launch in session `$11`, origin `%2358` failed before Gradle started:
`respawn pane failed: fork failed: No space left on device` (PTY usage 4094/4096). After the
user-authorized session restart, PTY capacity was available and that former origin was gone.
Using the restarted origin `%2549` in the same session, the first real build exposed a test
fixture's unsupported cold `store.find` call. It was repaired to use the real bounded geometry
read/hydration API; no production bypass or test exclusion was added.

Successful build handle: session `$11`, origin `%2549`, retained pane `%2551`, run
`454cba3cbe31424eb5ea40dd90b57544`, exit 0. The earlier failed build used the same pane with
run `b69c4fb9affb4bc283b8123963dda65b`. An intermediate successful build used
`153e06f7bc114541a1454da217d04c41`; the final run also covers cancellation-window retention. Existing malformed-embedding persistence fixtures
intentionally log warnings; deprecated API/Gradle feature warnings are nonfatal. No software
installation, direct background fallback, foreign-pane cleanup, gameplay or model inference
verification was performed.

## Retained baseline regression/build evidence (before this review repair)

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
