# Wave 2: server landmark extraction

## Owned implementation

Only new `src/main/java/io/github/mysticism/landmark/extract/**`, matching tests, and this document. No initializer, foundation landmark core, build, manifest, networking, terrain or sibling changes.

Public contract:

- `LandmarkExtractionService.init()` registers server start/stop, tick, chunk load/unload and dimension unload handlers. No player objects are retained; a rotating, bounded online-player sample discovers loaded source chunks.
- `LandmarkProfiles.current()` returns the foundation `landmark.EmbeddingProfile` derived from the real embedding manifest/profile: Nomic v2 MoE pinned revision, manifest tokenizer, search-document prefix, 256-dimensional Matryoshka reduction, L2 normalization and actual descriptor fingerprint.
- `LandmarkProfiles.wrap(Vec384f)` copies data, rejects foreign fingerprints, non-finite/zero vectors, and attaches that profile.
- `LandmarkExtractionService.stats(server)` exposes bounds/progress/deferred failure status.

**Foundation naming conflict:** there is no `LandmarkSavedState` in the merged foundation. The implementation uses the real authoritative `LandmarkStore.get(server)`, streamed geometry reads, CAS mutations, `stageMerge` and `stageSplit`. `ExtractionJournal` stores scheduling/version reservations, completed-observation fingerprints and bounded seed history, not a competing geometry repository.

## Parent registrations

Add this common initializer call:

```java
io.github.mysticism.landmark.extract.LandmarkExtractionService.init();
```

Register the supplied common source-edit mixin using a separate parent-owned common mixin config (the existing `io.github.mysticism.mixin` package cannot address this package with a relative class name):

```json
{"required":true,"package":"io.github.mysticism.landmark.extract","compatibilityLevel":"JAVA_21","mixins":["SourceBlockUpdateMixin"],"injectors":{"defaultRequire":1}}
```

Then add that config resource to `fabric.mod.json`'s `mixins` list. The owned Java path is `src/main/java/io/github/mysticism/landmark/extract/SourceBlockUpdateMixin.java`; target is `World.setBlockState(BlockPos, BlockState, int, int): boolean`, RETURN, successful server-world edits only. Without registration, loaded-chunk periodic polling still works, but immediate edit invalidation does not.

## Extraction and budgets

Actual loaded `WorldChunk` block states, state properties, registry biome keys and heightmaps are snapshotted on the server thread. There are no forced chunk loads. Null/missing observations remain unknown, never invented air. A resumable physical-air graph propagates sky/outside and unknown evidence **across biome boundaries**, followed by strictly biome-keyed six-neighbour cave components. This prevents a lush cavity adjoining a different-biome open/unknown cavity from falsely becoming sealed.

Source observation domains are fixed absolute 32-cubes, horizontally offset eight blocks from the chunk grid. These span vanilla source chunk and 16-block observation-page boundaries; landmark IDs derive from absolute source seeds, not chunk IDs. Committed anchors survive one-to-one growth and even filling the original seed cell. Retired aliases are not resurrected: persisted bounded source history chooses the next deterministic available observed seed cell for a new fragment. Shared observation pages have fragment-specific IDs and disjoint masks. New wall edits can produce transactionally persisted merge aliases and split lineage. Merge-input page IDs are distinct from final page IDs, preventing immutable-version collisions.

Actual surface elevation relative to sea level distinguishes mountains from coarse biome/surface observations. Elevated, higher-importance mountains retain block-level samples; low-importance surface observations use a four-block horizontal sampling stride. Air connectivity remains block-level.

Per server: one worker, at most one queued worker task (including canceled work), and one active controller job; at most 32,768 snapshot cells, 1,024 sampled cells/tick with a cooperative 1.5 ms sampling deadline, 256 pending domains, 256 tracked chunks, 256 components/domain, 512 material states/snapshot, 512 persisted domains and 8,192 lifetime tracked seed IDs. Geometry reads and writes advance one page and at most 256 decoded/validated leaves per tick. Graph processing, mask reconciliation, content fingerprints and source-geometry reconstruction run on immutable snapshots on the worker. No model IO, model inference, graph scan, blocking future wait, registry enumeration or whole-world/chunk scan occurs on the tick. Item-index state is obtained during server startup, not lazily cold-loaded on a tick.

Descriptor embeddings use actual block-palette frequencies, exact biome/dimension descriptors and structural classification through asynchronous `EmbeddingHelper.composeDescriptors`. At most 12 material descriptors plus two structural/region descriptors per feature are submitted; weighted compatible item-index vectors are the non-random fallback. Failed/unready embeddings defer publication. Pending edits remain bounded. Failed/unready jobs requeue without losing pre-readiness edits; loaded-chunk polling also retries frontiers. Deferred warning logs are rate-limited to one per 200 ticks. Vanished parents are deleted before new births, and merge reductions precede splits/births so temporary publication does not exceed regional identity caps. Completed observation fingerprints prevent unchanged retries from generating repeated immutable geometry versions. Shutdown cancels reads/mutations/futures, clears queues/references and interrupts the worker; unloaded dimensions discard in-flight work and queued references.

## Activity/ownership integration: parent follow-up required

Automatic physical merge is **already wired** to real `LandmarkStore.stageMerge(proof, time, policy, reconciledGeometry)`. Source-mask revisions precede the atomic ownership/alias merge so changed material is explicitly reconciled. Current source descriptors are refreshed after merge. Splits use `stageSplit`, preserve common activity/ownership metadata and record lineage.

The sibling activity service's actual `tryVerifiedLocalMerge(server, proof)` lacks reconciled geometry. Its ordinary `stageMerge` cannot accept disconnected masks with overlapping observation-page bounds; therefore simply calling that two-argument hook would reject these real merges.

The extraction service exposes and invokes `COMMITTED_TOPOLOGY` **after each successful atomic merge/split**, on the server thread:

```java
LandmarkExtractionService.COMMITTED_TOPOLOGY.register((server, previousIds, replacementIds) -> {
    // Parent/activity reviewer: migrate LandmarkActivityState influence vectors,
    // influence levels, owners and sparse claim histories using these explicit IDs.
    // Do not stage another repository mutation here.
});
```

This is a real emitted event, not a substitute activity implementation. A new activity-state absorption/split-copy listener is still required in the activity-owned scope and must be registered before gameplay. Explicit original IDs remain available even after aliases resolve. Without it, common repository ownership survives, but activity's separate influence-history map is not absorbed/copied. Listener exceptions are logged without pretending to undo an already committed topology mutation. Similarity alone is never accepted as connectivity, and different biome keys never merge.

## Validation

Tests are standalone `main` self-tests, consistent with the foundation, not silently discovered JUnit tests.

Final validation commands ran visibly in tmux pane `%463`, explicit `bash -lc`, Java 21, no downloads:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --no-daemon --max-workers=2 \
  -I /tmp/mysticism-extraction-check.gradle extractionSelfTest extractionPersistenceSelfTest --console=plain
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
  bash src/test/java/io/github/mysticism/landmark/extract/run-self-tests.sh
```

**Passed:** Gradle production/test compilation and `ExtractionSelfTest` (104,063 checks), `ExtractionPersistenceSelfTest` (2,186 checks); independent Java-only script graph/profile run (104,063 checks). The optional disk branch of that script was **not run** because its Minecraft classpath variable was unset; the same disk test ran and passed via Gradle.

The outside-repository diagnostic init script registers the two JavaExec tasks and adds only an already-cached DJL API jar to compile-only classpath. This is needed solely because this baseline still imports legacy `ai.djl.util.Pair`; parent reports that dependency was removed in integrated dev. No project dependency/build file was changed. Parent can register the JavaExec tasks against normal integrated `sourceSets.test.runtimeClasspath`, or use the standalone script. No shim source or sibling source is needed for these tests.

Earlier diagnostic iterations **failed**, then were fixed: cold-read leaf budget assertion incorrectly treated leaves as pages; merge-input and final geometry reused an immutable page ID/version; split attempted to resurrect a retired merge alias. Final tests cover those corrected paths. No final scoped test failure remains. Full integrated `selfTest`, `verifyProductionJar`, live server/model and GPU checks were **not run** by this extraction continuation.

- `ExtractionSelfTest`: production graph, chunk-edge crossing, strict biome cutoff, sky/unknown propagation, frontier retry, disconnected shared-page masks, revisions, stable seeds/filled anchors, merge/split discovery, graph/cap budgets, real surface classification/refinement, fingerprints and defensive embedding/profile copies.
- `ExtractionPersistenceSelfTest`: production graph -> real streaming `LandmarkStore` mutations -> compressed disk -> cold reads; preserved profile, source version reservations, content fingerprint, bounded journal, reconciled opened-wall geometry, merge aliases/ownership, split lineage, retired-alias avoidance and stable restored positions.
- `src/test/java/io/github/mysticism/landmark/extract/run-self-tests.sh` runs pure checks with Java 21 and optional disk checks with `MYSTICISM_MINECRAFT_CLASSPATH`; no sibling sources or dependencies are required.

## Remaining limitations (not aspirational completion)

- Flood-fill is bounded to one 32-cube observation domain. Components cross source chunks/pages inside it; components crossing the **extraction-domain boundary** remain provisional frontier fragments and are not yet globally stitched/merged. Boundary frontiers are conservative, so they never falsely claim closure. This is not an unlimited whole-world cave extractor.
- Seed selection is deterministic within each observed snapshot and persistent lineage. Different initial unknown-frontier/chunk-discovery histories are not guaranteed to converge to identical final IDs in independently created worlds; existing committed anchors are intentionally not dynamically re-keyed.
- Many-to-many topology edits (simultaneous split and merge sharing parents) defer rather than publishing an invalid partial topology. Catalog/seed-history exhaustion and excessive local fragmentation also explicitly defer; there is no unbounded eviction/scan workaround.
- Initial chunk discovery covers chunk events plus loaded spawn/player chunks, not an enumeration of all already-loaded remote forced chunks. Poll retries are bounded and can have substantial latency in a busy world. No new chunks are loaded to reduce that latency.
- Unchanged snapshots do not grow immutable storage, but actual source revisions leave historical immutable foundation pages; no garbage collection API exists in the owned foundation contract.
- Heightmap outside-air proof is conservative. Cavities connected beyond an observation domain to undiscovered outside air stay provisional; closure requires observed local walls. Modded dimensions with unusual terrain/heightmaps have not been live-tested.
- The 1.5 ms deadline limits the sampling loop, not every Minecraft/core single-page decode/encode operation or startup persistent-state IO. Bounds are cooperative, not a real-time guarantee.
- No live Minecraft server, real Nomic/Ollama inference, cross-feature activity hook, terrain playback, GPU or rendered-world validation was performed by this leaf. Parent owns integrated build and runtime review.
