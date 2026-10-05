# Traversal/activity wave-2 handoff

Scope: `feat-spirit-attunement`; no initializer, manifest/mixin/build configuration, networking, terrain, client, or landmark-foundation changes. No dependencies/agents. Java 21 / Yarn 1.21.1. `AGENTS.md` was absent in this worktree and its searched ancestors; read `src/main/java/io/github/mysticism/landmark/CORE_API.md` and existing tests.

## Parent integration required

1. Call `io.github.mysticism.activity.SpiritActivityService.init()` in common initialization. Keep the existing `SpiritBasisEvolver.init()` call (both are idempotent). Register terrain's tick before the evolver's tick. Do not initialize model providers from the activity service: it uses the existing `EmbeddingHelper`/`AsyncEmbeddingEngine` lifecycle.
2. Existing CCA `latent_attunement` registration with `ALWAYS_COPY` remains sufficient. Its schema-2 payload persists **personal**, **target**, **current**, target mode and revision; legacy `v` remains the CURRENT field for readers. Profile-stamped wave-1 payloads migrate; incompatible/corrupt payloads are archived, not reinterpreted. `get()/target()/personal()` return copies; `set()` chooses an explicit target; `followPersonal()` restores personal-target mode.
3. Parent must create `src/main/resources/mysticism.activity.mixins.json` and add its name to `fabric.mod.json`'s common mixins. No configuration was edited here. Required snippet:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.mysticism.activity.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": ["ActivityStatMixin", "ActivityPlacementMixin", "ActivitySpawnMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

These narrowly relevant event adapters are new files in the owned activity package: actual before/after vanilla Stats deltas, successful `BlockItem.place` returns matching the real placed block, and successful `ServerWorld.spawnEntity` returns. Fabric AFTER-break and AFTER-death are registered by `init()`. They do not modify movement methods. Failed/cancelled break/place/spawn attempts are not counted. The hooks need parent configuration and live Mixin verification; no claim of a booted-server test is made.

4. Cross-feature APIs implemented:
   - `public static void init()`
   - `public static LandmarkEmbedding effectiveEmbedding(MinecraftServer server, LandmarkMetadata landmark)` (copied base when untouched; pinned-profile overlay otherwise)
   - `public static double importance(MinecraftServer server, LandmarkMetadata landmark)` (bounded 0..1; default base importance)
   - `public static boolean tryVerifiedLocalMerge(MinecraftServer server, LandmarkRepository.VerifiedConnectivity proof)` stages, but does not synchronously commit, a local/similar merge. **Extractor must call this with its real physical connectivity proof**; semantic affinity alone never creates proof. Different biome/kind/dimension domains are rejected; core validates all domains/CAS/topology. Combined influence/importance uses capped maxima, not an additive shop-district score; ownership histories combine. No automatic proof inference was implemented.
5. Contract-name conflict: the actual merged wave-1 authoritative common store is `LandmarkStore.get(server)`, not `LandmarkSavedState`. This implementation uses `LandmarkStore` metadata/ranges and bounded `stageActivity`/`stageMerge` plans, without geometry hydration or replacement stores.
6. Terrain's current sibling code selects with `LATENT_POS`, not `LATENT_ATTUNEMENT.get()`; parent/terrain should review which CURRENT semantic state is intended for radius selection. TARGET must not be substituted. This work did not edit terrain or dynamically re-key its frozen projection.

## Implemented behavior/bounds

- Stats-screen deltas cover deaths, mob/player kills, MINED blocks and USED items; build/break descriptors use actual source material. Held/inventory dwell uses real stacks, independent of stack count. A consumable 64-descriptor window requests at most its eight highest-weight retained observations; weights cap at 64. No lifetime-total replay. Existing asynchronous weighted-descriptor APIs perform inference off tick; only completed futures are polled, never joined.
- At most 64 personal sessions; four inventory samples per 20 ticks, up to 41 slots each; personal request cadence >=200 ticks; four personal compositions and eight environmental jobs outstanding. Event backlog <=64. Lifecycle clearing covers dimension change, respawn, disconnect and server shutdown; late results cannot update a removed session/player component.
- Influence persistence is overworld-owned `mysticism.landmark_activity.v1`, separate from authoritative geometry/catalog persistence. <=512 overlays; normalized drift toward real event/personal-dwell vectors; influence importance contribution <=0.35, with 24000-tick half-life. Radius relevance is separately gated at 24 source blocks, never bypassed by importance. Nearby overlapping regions rotate deterministically across pulses.
- Source-range work is explicitly capped at 128 catalog metadata entries and eight results. Catalog/result overflow is rejected and counted by `skipped(server)`, not silently nearest-K truncated. One pending core transaction, advanced at one page/reference operation and <=256 validation leaves per tick. Overlay publication follows successful guarded catalog publication.
- Ownership uses a one-source-block-resolution sparse octree, root growth <=512 blocks, <=256 cells, <=256 update nodes, <=16 owners/landmark, <=8 claimed landmarks/player. Absolute cells/source seed IDs survive growth/reload. Failed ownership expansion retains old immutable claims while vector/activity still advance. No chunk IDs, source-base-vector rewriting, player teleport, or projection re-keying.
- Evolver queries actual `SpiritTerrainService.support(player)`, smooths CURRENT around the supported landmark's tangent/orbit, retains TARGET, and re-attunes toward TARGET on flight/off-support. Basis stays fixed under physical terrain. Vanilla velocity, walking, jumping, flight, sneak and drop are untouched; no forced flying. Teleport displacement >4 blocks does not inject semantic travel. <=64 spirit players receive per-tick vector work.

## Actual validation

Interactive bash tmux pane **%465**, Java `/usr/lib/jvm/java-21-openjdk`, displayed output, offline/no-daemon/two workers. Temporary outside-repo `/tmp/attunement-scope.gradle` selects only owned production/dependency source and the **real** sibling `LandmarkProfiles`/terrain files, excludes legacy index-generation/KNN and client compilation, and registers main-based checks. It is not a production build-config change or a full-project build.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --no-daemon --max-workers=2 \
  -I /tmp/attunement-scope.gradle -x compileClientJava \
  activityChecks runLandmarkCoreSelfTest runLandmarkPersistenceSelfTest
```

Executed successfully: `ActivitySelfTest` **10018** checks; `ActivityPersistenceTest` **4027** checks; `ActivityAdapterTest` **4 delta** checks; existing `LandmarkCoreSelfTest` **802** and `LandmarkPersistenceSelfTest` **2388** checks. Persistence coverage uses real CCA/state codecs, compressed Minecraft NBT disk reload, migrations/profile archives, independent target/current/personal, ownership quotas/octree growth, production influence reduction/dwell convergence, strict radius and capped importance. Expected corrupt-file errors appear in the existing landmark disk checks. `git diff --check` passed.

Standalone committed runner: `bash src/test/java/io/github/mysticism/activity/run-tests.sh`. Pure checks require only Java 21. Set `MYSTICISM_MINECRAFT_CLASSPATH` for production adapters/NBT. After merge it uses normal in-repository profile/terrain source paths; before merge, optional `MYSTICISM_PROFILE_SOURCE` and `MYSTICISM_TERRAIN_SOURCE` point at the actual siblings (no placeholders).

## Remaining limitations / review targets

- **No live server/model/Mixin execution, physical collision walk/jump exercise, CCA network/respawn boot, or final integrated build/artifact test.** Attempting raw-JVM registry/Stats checks failed with `IllegalAccessError: ... RegistryEntry$Reference.setRegistryKey(...)`; mapped vanilla classes need Fabric's runtime access widening. Registry-dependent adapter checks are explicitly opt-in (`mysticism.activity.registryChecks`) and report NOT RUN by default, not claimed among the four delta checks. Parent should execute them in an access-widened Fabric environment.
- Unscoped worktree compilation initially failed on existing missing `ai.djl.util.Pair` in legacy KNN/command sources. This was not fixed outside scope. Temporary scoped validation bypassed those files; parent must validate the merged build.
- Merges are callable guarded transactions, **not automatically triggered**: extractor connectivity evidence is required. Extractor merges that bypass the activity merge API do not migrate activity overlay/octree records; stale deleted/aliased overlays can conservatively consume ownership quotas until a migration/pruning integration is added.
- Catalogs >128 or local queries >8 stop activity events rather than scan unbounded data. At capacity, excess players/events/descriptors/overlays are omitted and events hitting a busy/stale core plan can be lost. Retained descriptor ranking is a bounded observation-window approximation, not a full registry leaderboard. No historical pre-install lifetime stats replay.
- Uncomposed observation windows/in-flight jobs are transient; restart/logout can discard recent unprocessed activity. Already-submitted engine work may finish after caller cancellation, but cannot publish into cleared components. Persisted vectors/claims survive normal saves.
- Catalog and activity overlay files follow vanilla saves, not a multi-file crash-atomic transaction. Metadata decode/compressed NBT access through foundation/state APIs remains bounded synchronous vanilla work; there is no model IO/inference or geometry hydration on tick, but page/scan limits are not wall-clock guarantees.
- Movement basis evolution is intentionally frozen; renderer/client prediction reconciliation is outside this scope. No source geometry growth/extraction is inferred from claims. Placing fluids/non-BlockItem interactions and unusual later third-party rollback after an accepted return are not covered as block-build events.
