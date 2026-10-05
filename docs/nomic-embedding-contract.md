# Nomic v2-MoE embedding contract (schema 2)

## Deployment, not an automatic download

Mysticism uses an administrator-provisioned **Ollama-compatible** HTTP service. No
model weights, software, fallback model, hash vectors, or model-pull endpoints are
used by the mod. The official model exists at
[Ollama nomic-embed-text-v2-moe](https://ollama.com/library/nomic-embed-text-v2-moe).
The upstream [v2 model card](https://huggingface.co/nomic-ai/nomic-embed-text-v2-moe)
documents 768 native dimensions, a 512-token context, multilingual text, and
256-dimensional Matryoshka embeddings.

On a machine where an administrator has **already installed Ollama**, the
administrator may explicitly choose to download/provision it with:

```sh
# Run the service in its own terminal if it is not already running:
ollama serve
```

```sh
# This command DOES download weights. It is an admin action, never a mod action.
ollama pull nomic-embed-text-v2-moe:latest
curl --fail http://127.0.0.1:11434/api/tags
curl --fail http://127.0.0.1:11434/api/embed \
  -H 'Content-Type: application/json' \
  -d '{"model":"nomic-embed-text-v2-moe:latest","input":"search_document: block minecraft:oak_log. minecraft oak log.","truncate":false}'
```

The verified official default manifest digest is
`ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965`.
It was verified against the [official registry manifest](https://registry.ollama.ai/v2/library/nomic-embed-text-v2-moe/manifests/latest)
without downloading weights. If the moving `latest` tag changes, readiness
**fails** until an administrator deliberately selects an approved revision;
changing the revision also changes the fingerprint and requires rebuilding.
Do not overwrite/reload model tags during a server session. Digest checks before
and after inference reject observed tag changes; an HTTP deployment is trusted
to actually serve the declared model. Prefer a stable private deployment.

Java system properties (supply before `-jar`, before mod classes load):

| Property | Default | Contract |
|---|---|---|
| `mysticism.embedding.endpoint` | `http://127.0.0.1:11434/` | HTTP(S) base URL; path prefixes allowed; no credentials/query/fragment; redirects disabled |
| `mysticism.embedding.model` | `nomic-embed-text-v2-moe:latest` | Approved v2-MoE model/tag or a compatible alias; **not** a selector for MiniLM/v1.5/other embedding families |
| `mysticism.embedding.revision` | digest above | Exact full lowercase SHA256 Ollama manifest digest; tokenizer identity is included in that manifest |
| `mysticism.embedding.connectTimeoutMillis` | `2000` | 1..120000 |
| `mysticism.embedding.requestTimeoutMillis` | `10000` | 1..120000 per HTTP operation |
| `mysticism.embedding.deadlineMillis` | `30000` | 1..120000 total queue + inference/readiness deadline |
| `mysticism.embedding.workers` | `2` | 1..32 |

For example, append these JVM options to your existing Java 21 Fabric server launch:

```text
-Dmysticism.embedding.endpoint=http://127.0.0.1:11434/ -Dmysticism.embedding.model=nomic-embed-text-v2-moe:latest -Dmysticism.embedding.revision=ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965
```

All clients and the server must use the same model/revision/profile properties.
Clients do not need a local inference service. The model/revision are a
compatibility contract, not secrets. Remote inference sends descriptors to that
service; choose a trusted endpoint/network. There is no authentication-header
configuration. Endpoint changes alone do not change vector identity.

Absent HTTP deployment, missing/wrong model, malformed response, zero/nonfinite
vectors, context overflow or timeout leave the Minecraft server usable and
explicitly report unavailable embeddings. Startup never joins inference.
Readiness is a real digest + embedding health probe, not just an executor flag.
Requests made before readiness fail immediately; callers can compose readiness.
Readiness is a startup snapshot, not continuous monitoring. Requests recheck the
model digest and return exceptional futures on subsequent failures. Reprovision
and restart the server to retry failed startup readiness.

## Public APIs for downstream landmark/activity owners

Packages are `io.github.mysticism.embedding` and `.vector`.

* `EmbeddingSpace.DIMENSIONS = 256`, `NATIVE_DIMENSIONS = 768`, `SCHEMA = 2`,
  `DESCRIPTOR_VERSION = 2`; `FINGERPRINT` is SHA256 of
  `MODEL + "|" + REVISION + "|" + DIMENSIONS + "|" + SEMANTICS`.
  `SEMANTICS` includes the family, tokenizer=model-manifest, mean pooling,
  `search_document: ` task, first-256 Matryoshka reduction, L2 normalization,
  and descriptor version. Equal dimensions never imply equal semantic space.
* `EmbeddingHelper.profile(): EmbeddingProfile` returns immutable
  `(model, revision, dimensions, semantics, fingerprint)`.
* `EmbeddingHelper.readiness(): CompletableFuture<Void>` returns a caller-owned
  copy. `isReady()` and `getInitializationStatus()` expose state. Lifecycle is
  `initializeServer()` / `shutdown()`; the initializer handles these.
* `EmbeddingHelper.getEmbedding(String descriptor): CompletableFuture<Vec384f>`
  accepts **unprefixed**, nonblank canonical text, at most 8192 Java characters.
  The provider sends `search_document: ` itself. Each success is independently
  owned, finite, nonzero, current-profile, L2-normalized and 256-dimensional.
  The bounded queue has 256 slots and a copied LRU cache holds 8192 entries.
  Queue rejection, provider exceptions, timeouts and shutdown complete futures;
  caller cancellation does not cancel another caller's shared inference.
* `CanonicalDescriptors.item/block/biome(String id, Collection<String> tags)`
  and `region(String dimension, String biome, Collection<String> tags)` produce
  stable ID + human words + sorted/deduplicated tags. Tags are bounded to keep
  descriptions short. Coordinates, weights, counts and player ownership stay
  outside semantic text. Items use registry tags; region rebuilding and seeding
  use biome tags. Downstream scanners supply block tags themselves.
* `DescriptorVectors.WeightedDescriptor(String descriptor, double weight)` and
  `WeightedVector(Vec384f vector, double weight)` validate nonnegative finite
  weights. WeightedVector copies on construction and access.
  `DescriptorVectors.compose(List<WeightedVector>): Vec384f` normalizes each
  positive-weight input, accumulates its weighted mean in double precision and
  L2-normalizes the result. Empty/zero-total weights, zero inputs, incompatible
  profiles and cancelling means fail explicitly.
* `EmbeddingHelper.composeDescriptors(List<WeightedDescriptor>)` (equivalently
  `DescriptorVectors.embed`) snapshots inputs and sequentially composes async
  embeddings, avoiding a worker-queue flood. It does not invent landmark classes.

Example (blocks and biome grounded by another owner):

```java
var descriptors = List.of(
    new DescriptorVectors.WeightedDescriptor(
        CanonicalDescriptors.block("minecraft:oak_log", List.of("minecraft:logs")), 3),
    new DescriptorVectors.WeightedDescriptor(
        CanonicalDescriptors.biome("minecraft:forest", List.of()), 1));
CompletableFuture<Vec384f> semantic = EmbeddingHelper.readiness()
    .thenCompose(ignored -> EmbeddingHelper.composeDescriptors(descriptors));
// Handle failure explicitly. Schedule world mutations via server.execute(...).
```

`EmbeddingProvider` is a blocking, closeable provider contract behind the async
engine: `profile()`, `checkReady()`, `getEmbedding(String)`, `close()`.
`EmbeddingService(URI, Duration connectTimeout, Duration requestTimeout)` implements
`GET /api/tags` and `POST /api/embed`, expects the declared model in the response
and exactly one native 768-number embedding, then takes the first 256 dimensions
and normalizes. Already-reduced 256 responses are deliberately rejected, as are
legacy 384 responses. JSON bodies are bounded to 128 KiB. `truncate:false` makes
actual 512-token overflow explicit; a character limit is not a token estimator.
Custom providers must preserve the complete current profile, not just shape.

`Vec384f`, `Basis384f`, `Projection384f` retain legacy Java names only. Vec384f
remains mutable for existing movement APIs, but input/output arrays, helper
results, weighted inputs, indices and their snapshots are defensively copied.
Clone before your own mutation. Provenance travels with clones; cross-profile
math/index/weighted composition is rejected. `Vec384f.fromBits` is for **already
profile-validated current state**, not a migration API. Never pad/truncate old
MiniLM vectors. `SimpleKnnIndex.kNN` returns an independently owned mutable list, sorted
score-descending with ID-ascending ties (including mutable empty results);
callers may sort or modify that list without changing the index.
EUCLIDEAN scores are negative squared distances. Snapshot iteration/get/upsert
cannot alias cached vectors. `BasisIntegrator384f.step(basis, current, target, ...)`
uses target minus current; convergence clamps its factor to [0,1].

## Persistence and network integration

* Item/spatial state keeps its existing save keys. Headers persist schema,
  dimension, descriptor version, model, manifest revision, semantics and
  fingerprint; **all** are checked. Incompatible vectors are archived under
  `archive` and never made active. Item vectors rebuild from current registry
  IDs/tags; spatial vectors rebuild from retained biome/dimension geometry and
  current biome tags. Malformed current vectors also trigger archive/rebuild.
* `ItemEmbeddingIndexState.populateAsync(MinecraftServer)` and
  `SpatialEmbeddingIndexState.rebuildAsync(MinecraftServer)` must be invoked on
  the server thread. They stage a sorted, complete generation and only activate
  it on the server thread once every descriptor succeeded. A failed/interrupted
  rebuild remains unavailable and resumes on the next startup. They recheck
  canonical descriptors when invoked (call them after relevant registry/tag
  reloads too). Item `populateIfNeeded(Runnable)` is deprecated and cannot
  certify a generation; use the async API.
* Spatial `regionsView()` is a map snapshot and `getRegion(String)` is a cheap
  server-thread lookup. `observeBiome(id, region, descriptor, vector)` merges
  **observed** chunk boxes without filling unseen gaps. Existing canonical
  geometry survives migration even if the HTTP service is absent. This existing
  persistent state serializes **BiomeSpiritualRegion only**; landmark/activity
  owners must persist their own canonical geometry/descriptors, not insert new
  region types through the legacy `putIfAbsent(ISpiritualRegion, ...)` contract.
* CCA position, attunement and basis archive incompatible data under
  `embeddingArchive`; position/attunement reset to zero, basis to deterministic
  default axes. No grounded player references existed for exact reconstruction.
  This intentionally loses old latent location/attunement while preserving its
  raw archive. Sync payloads use the same header validation.
* `SpiritDeltaPayload.ID` is `mysticism:spirit/visible_delta_v2`. Its packet codec
  writes schema, fingerprint, dimensions, additions and removals in that order.
  Added vectors validate dimension/finiteness and copy arrays on both edges;
  `Added.of(id, vector)` enforces current provenance. Counts are bounded to 4096
  per addition/removal stream; IDs are nonempty, well-formed UTF-16, at most 256
  Java characters (at most 768 UTF-8 bytes). `MAX_ENCODED_BYTES = 1_048_000`
  caps canonical payload bytes below 1 MiB with packet/channel/framing headroom,
  independently of compression. Constructor and decoder reject oversized deltas.
  `SpiritDeltaPayload.batches(List<Added>, List<String>)` returns immutable,
  bounded packets preserving each stream's order; empty input returns no packets.
  `encodedBytes()` reports exact codec bytes, excluding wrappers. Send all
  batches in list order before updating the visibility snapshot; additions and
  removals should have disjoint IDs (as visibility-set differences do).
  Old peers cannot interpret this protocol; update server and clients together.
  The existing visibility selection/rendering policy is not replaced here.
* HorizonSeeder no longer mutates cached base vectors or adds random semantic
  jitter. The budget is loaded-**chunk lookups per tick**, 2..64 (initially 16),
  despite the legacy `getRegionsPerTick()` name. `getWorldChunk(x,z)` returns
  completed chunks without joining generation; null results are skipped.
  **`getChunk(FULL,false)` is not a nonblocking guarantee**: it can still wait
  on unfinished ticketed chunks and is not used for observation or spawn
  discovery. Spawn scans read heightmaps/blocks from completed chunks and do
  not cross into neighbors. Cached spawns require a completed chunk too.
  Queue/pending limits are 1024/32; scans resume one lookup at a time and reset
  on shutdown. Biomes are still chunk-center/sea-level observations, not cave
  discovery, grounded cave descriptors or clustering. `ChunkBox` construction
  and codec require ordered local coordinates within [0,31]. Region box lists
  are bounded to 1024 and deduplicated. The pure
  `BiomeSpiritualRegion.spawnCandidates(int budget)` returns at most 1024 unique
  chunks with closest-first/coordinate-tie order and a cap including the center;
  nonpositive budgets return no candidates. Invalid geometry is archived; only
  completely valid sibling regions survive for rebuilding.

## Integration needs outside this worktree's ownership

* `command/EmbeddingCommand.executeGetInit` still performs
  `EmbeddingHelper.getEmbedding(...).get()` and swallows failures. Command edits
  in this assignment are dimension-only; its async feedback change belongs to
  the command/contract owner. Replace that blocking code with `whenComplete`,
  schedule feedback using `server.execute`, and explicitly report exceptional
  completion, also checking server/player lifetime. Readiness is not permission
  to block a tick on subsequent HTTP requests. This review finding remains open.
* `dimension/spiritworld/SpiritVisibilityService` still sends one constructed
  delta for 1643 items. The transport/visibility owner must use
  `SpiritDeltaPayload.batches(added, removed)` and send **each** payload before
  advancing `LAST`. No sender/client-render files were changed here. The byte
  guard intentionally rejects oversized single deltas instead of silently
  truncating or sending frames that Minecraft cannot decode.

## Clustering intent: not a fabricated task prefix

V2 documents `search_document:` and `search_query:`, **not** `clustering:`. Shared
item/block/biome/landmark descriptors use the same document space here. KNN and
three-basis projection are not representative clustering and do not guarantee
stable landmark layout. Clustering quality at 256 dimensions is not yet validated
on a Minecraft corpus; native 768 remains the evaluation baseline.

A downstream cluster owner should use deterministic spherical clustering of
normalized descriptors: sort canonical IDs, use deterministic initialization
(e.g. farthest-first with ID ties), fixed iterations/tolerance, ID tie-breaking,
and medoid representatives with stable memberships/epochs. Persist that owner's
cluster membership/layout separately from this vector profile. This migration
provides vectors/composition, **not** a delivered cluster/landmark algorithm.
If a documented clustering-task model is mandatory, the English-focused
[Nomic v1.5 card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5) documents
`clustering:` and layer-normalize-before-truncation followed by L2 normalization.
That is a different profile/preprocessing/provider migration, not a runtime
fallback or prefix that may be applied to v2 vectors.

## Checks and reproducibility

No new test framework/dependencies are required. Run the pure vector/index/cache/
weighted/provider/HTTP-stub regressions with Java 21 and existing Gson/DJL jars:

```sh
python3 tools/check-embeddings.py
```

It never installs/resolves dependencies or downloads models. If necessary, point
`MYSTICISM_TEST_CLASSPATH` at **existing** Gson + DJL API jars. Minecraft NBT/CCA/
packet regressions and the pure suite can also be run on Loom's runtime classpath:

```sh
/path/to/existing/gradle --no-daemon -I tools/embedding-tests.gradle \
  embeddingPipelineRegression embeddingPersistenceRegression build
```

The optional init script only adds test tasks; it does not override dependencies
or plugin versions and is project-local, deliberately outside chezmoi.
`gradle test` alone does not execute these standalone main-based regression suites.

Validation environment limitation: the project pins Loom `1.11-SNAPSHOT`, which
currently requires Gradle >=8.14, while the supplied binary is Gradle 8.13. The
unmodified configured build fails during plugin resolution. Implementation checks
used a **temporary, uncommitted** init script mapping Loom to `1.10.5` with the
existing Gradle 8.13 binary; no Gradle software was installed. This is diagnostic
compatibility validation, not proof that the original pinned build succeeds.
Live inference/model quality and a running Minecraft server were not exercised;
HTTP and migration behavior are covered by deterministic local stubs/real NBT.
