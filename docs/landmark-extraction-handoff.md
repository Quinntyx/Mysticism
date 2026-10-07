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

## Global boundary stitching continuation (2026-10-07)

`BoundaryCaves` is now a production worker helper, called automatically by `StitchJob` after completed source observations. It streams canonical cave masks from the current domain and its six adjacent **persisted** domains, then proves actual six-neighbour AIR contact. Dimension, biome, algorithm and embedding profile must match exactly. Biome boundaries never disappear because vectors are similar. Connected groups are merged through real `LandmarkStore.stageMerge(proof, tick, policy, reconciledGeometry)` transactions; there is no permanent spatial cutoff at 32-cube boundaries. Repeated unions grow a feature across arbitrary adjacent domains subject to explicit resource bounds: <=256 candidates, <=512 streamed pages, <=131072 expanded observed cells, and <=8 fragments per atomic union. Exceeding a bound defers the union; it does not invent a connecting corridor or average disconnected caves.

For edits to a previously stitched cave, `BoundaryCaves.revise` recomputes its **whole observed** air graph using new observations inside the edited domain and persisted masks outside it. Remote geometry is never truncated to that domain. A changed bridge can therefore produce a real global split/removal via the existing repository split/delete plans. Missing observations of a previously known bridge defer revision until reload, rather than manufacturing a split or closed cave. Unknown frontiers are conservatively retained; this continuation does **not** claim global frontier closure/finalization. Geometry hydration, expanded-air scanning and reconciliation stay on the worker. Reads/mutations still advance one page/256 leaves per tick; no whole geometry scan or inference is added to tick.

Discovery order produces the same final minimum source seed ID when the same domain fragments are observed: the real core records aliases when a newly discovered lower canonical seed wins. This is an explicit merge identity change, not a projection re-key or teleport. Different partial/unknown observations and subsequently retired seeds can still yield different fragment histories. Catalog aliases/lineage survive reload. The controller caches bounded lineage at startup and follows split children incrementally while reading stale journal aliases; a retained parent ID does not hide its other children. <=8192 cached retired IDs/lineage entries; exhaustion defers extraction. Foundation startup catalog-map copies and vanilla multi-file save caveats remain.

## Activity/ownership integration: exact prepare/commit/cancel API

Reviewed the real sibling `LandmarkMerge` and handoff at activity continuation `eadce4d`; no sibling/core/dev edits or dependency added. **After-only COMMITTED_TOPOLOGY is no longer the history adapter.** Install this adapter before gameplay:

```java
LandmarkExtractionService.topologyAdapter((server, change) -> {
    if (change.kind() == LandmarkExtractionService.TopologyKind.MERGE) {
        var plan = io.github.mysticism.activity.LandmarkMerge.prepare(server, change.proof());
        return new LandmarkExtractionService.TopologyPlan() {
            public void commit() { plan.commit(); }
            public void cancel() { plan.cancel(); }
        };
    }
    // Parent-owned real SPLIT/REMOVE preparation: snapshot overlay before core staging,
    // validate quotas and capture change.parents() / immutable change.children().
    // Return guarded commit()/cancel(); never publish history in prepare().
    throw new IllegalStateException("split/remove activity adapter not installed");
});
```

The owned public types are `TopologyKind { MERGE, SPLIT, REMOVE }`,
`TopologyChange(kind, List<RevisionRef> parents, List<Landmark> children, VerifiedConnectivity proof)`,
`TopologyAdapter.prepare(MinecraftServer, TopologyChange) -> TopologyPlan`, and
`TopologyPlan.commit()/cancel()`. Proof is non-null for MERGE. SPLIT carries exact newly recomputed children, including profile, seed, geometry, revision and metadata; REMOVE carries the guarded parent ref. Preparation occurs immediately **before** the corresponding real core staging; a rejection is not swallowed and vetoes that mutation. Commit occurs immediately **after** successful core completion, before topology notifications and descriptor-refresh publication. Cancellation/stale work/dimension unload/shutdown cancels prepared history without publication. Both local topology and global boundary unions use these hooks. An adapter commit failure after core publication is logged/deferred, not falsely described as an atomic rollback of vanilla files.

The default adapter publishes no overlay history, because activity-owned split/removal copying does not exist here. Parent MUST install the real adapter; no fake activity implementation or reflective placeholder was added. The sibling merge plan has additional radius, effective-vector, importance/owner and **128-block union extent** limits. Those can veto otherwise physically valid global unions: parent must deliberately review/widen that activity policy if large caves should merge, not discard histories or silently bypass preparation. Coordinate overlay edits while a prepared mutation is pending, preload activity state, and delegate bounded importance as documented in the sibling handoff. Avoid double-summing histories in COMMITTED_TOPOLOGY.

### Exact continuation validation

No whole Gradle task was run in this continuation; parent serializes those. Visible tmux `%463`, explicit bash, Java 21, existing cached mapped Minecraft/Fabric jars, no downloads, outputs exclusively retained `/tmp/mysticism-boundary-service.*` and `/tmp/mysticism-extraction-tests.*`:

- Owned `extract/*.java` production compilation: passed using `javac --release 21 -proc:none`, actual existing baseline common classes and cached runtime classpath.
- `run-self-tests.sh` with actual Minecraft classpath: ExtractionSelfTest **104063**, BoundaryCavesSelfTest **6416**, ExtractionPersistenceSelfTest **3212** checks passed.
- New tests exercise three adjacent domains, alternate discovery order/canonical aliases, exact cave-biome/dimension cutoffs, disconnected masks, no remote truncation, global bridge split/removal, unknown-bridge deferral/reload, explicit bounds, actual canceled/completed streaming store merges, compressed disk cold reload, split lineage and deletion tombstones.
- Initial new test attempts failed on compound `var` declaration and incorrectly dimensioned fixture frontiers; corrected, rerun passed. No live source-world/player tick, real runtime inference, activity adapter lifecycle, integrated Gradle artifact or GPU validation was exercised.

## Earlier wave-2 integration notes (superseded where stated above)

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

This is a real emitted notification, not an activity implementation. **Use the new prepare/commit/cancel adapter above for histories**, not an after-only absorption listener. Explicit original IDs remain available even after aliases resolve. Without it, common repository ownership survives, but activity's separate influence-history map is not absorbed/copied. Listener exceptions are logged without pretending to undo an already committed topology mutation. Similarity alone is never accepted as connectivity, and different biome keys never merge.

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

- Sampling remains in 32-cube observation domains, but global air-face stitching is implemented as described above. Unresolved frontiers remain provisional. Global page/cell/candidate caps and activity-policy vetoes can still defer very large unions; this is not an unlimited whole-world resident flood-fill.
- Seed selection is deterministic within each observed snapshot and persistent lineage. Different initial unknown-frontier/chunk-discovery histories are not guaranteed to converge to identical final IDs in independently created worlds; existing committed anchors are intentionally not dynamically re-keyed.
- Many-to-many topology edits (simultaneous split and merge sharing parents) defer rather than publishing an invalid partial topology. Catalog/seed-history exhaustion and excessive local fragmentation also explicitly defer; there is no unbounded eviction/scan workaround.
- Initial chunk discovery covers chunk events plus loaded spawn/player chunks, not an enumeration of all already-loaded remote forced chunks. Poll retries are bounded and can have substantial latency in a busy world. No new chunks are loaded to reduce that latency.
- Unchanged snapshots do not grow immutable storage, but actual source revisions leave historical immutable foundation pages; no garbage collection API exists in the owned foundation contract.
- Heightmap outside-air proof is conservative. Cavities connected beyond an observation domain to undiscovered outside air stay provisional; closure requires observed local walls. Modded dimensions with unusual terrain/heightmaps have not been live-tested.
- The 1.5 ms deadline limits the sampling loop, not every Minecraft/core single-page decode/encode operation or startup persistent-state IO. Bounds are cooperative, not a real-time guarantee.
- No live Minecraft server, real Nomic/Ollama inference, cross-feature activity hook, terrain playback, GPU or rendered-world validation was performed by this leaf. Parent owns integrated build and runtime review.
