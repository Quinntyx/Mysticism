# Landmark core integration contract (Java 21 / Yarn 1.21.1)

All types are in `io.github.mysticism.landmark`. This package implements contracts,
geometry storage, queries, selection and placement calculations. **It does not extract
caves/mountains, load source chunks, generate blocks/collision, send packets, or render terrain.**
Extraction, physical terrain, activity and integration agents retain those responsibilities.
No initializer, networking, vector, component, build or client files are changed.

## Units, immutability and identity

* `BlockPoint(long x,long y,long z)` is an absolute **source block** coordinate, not a
  chunk/local offset. `Bounds(long minX,long minY,long minZ,long maxX,long maxY,long maxZ)`
  is a nonempty, half-open source AABB. Negative coordinates work with floor alignment.
  `Bounds.cube(long x,long y,long z,long side)` uses checked arithmetic. Bounds use
  checked long widths; project Minecraft-scale coordinates to preserve double precision.
  Octree roots have side <= 2^32.
* `Point3(double x,double y,double z)` / `RealmBounds(Point3 min,Point3 max)` describe
  finite **absolute realm block** coordinates. Distance methods return squared block
  distances. Semantic distances are Euclidean distances of compatible vector components,
  not block distances or cosine similarities.
* Collections are copied, and `LandmarkEmbedding(EmbeddingProfile profile,Vec384f vector)`
  snapshots mutable `Vec384f`. `vector()`, `data()`, and projection axis getters return copies.
  `profile()`, `distanceSquared(LandmarkEmbedding)`, and
  `dotDifference(LandmarkEmbedding origin,Vec384f axis)` are public. Vectors must be finite.
* `EmbeddingProfile(String model,String revision,String tokenizer,String prefixPolicy,
  int dimensions,Normalization normalization,String descriptorSchema)` compares **every**
  field; `requireCompatible(EmbeddingProfile)` throws for any mismatch. Normalization is
  `NONE` or `UNIT` (validated, not silently applied). Use pinned model/tokenizer revisions
  and a descriptor-schema hash. Dimensions come from `Vec384f.ZERO().data().length`;
  this package never substitutes models or supports implicit dimension migration.
* `LandmarkIds.seed(String dimension,String algorithmVersion,Landmark.Kind kind,
  String biome,BlockPoint anchor)` is `lm-` + SHA-256 of length-delimited source fields.
  Store/world save is the identity namespace; use namespaced dimension and biome IDs.
  An extractor chooses a deterministic absolute seed anchor, never a chunk-arrival seed.
  Identity excludes revision, root bounds, projection, ownership, activity and embedding.
  Unknown-frontier components are provisional: `Landmark.provisional()` is true.
* `LandmarkIds.geometryPage(String dimension,BlockPoint anchor,int side)` identifies a
  shared observation cube; overload `(String dimension,String fragmentSeedId,BlockPoint
  anchor,int side)` isolates disconnected masks occupying that same cube. Side must be a
  power of two <= `GeometryPage.MAX_SIDE` (64). Never reuse a page ID/revision for different
  contents, including after a cancelled write. Increment its revision for edits.

## Immutable landmark and observations

Exact canonical constructor (record accessors have the corresponding names):

```java
Landmark(String id, String dimension, String algorithmVersion, Landmark.Kind kind,
    String biome, BlockPoint anchor, Bounds bounds, LandmarkEmbedding baseEmbedding,
    double baseImportance, ActivityMetadata activity, Ownership ownership,
    SourceGeometry geometry, long revision, String provenance)
```

`Kind` is `BIOME`, `CAVE`, or `MOUNTAIN`; classification is an extractor assertion.
ID must match `LandmarkIds.seed(...)`; anchor must be inside bounds; page bounds must
fit landmark bounds. Revisions/ticks are nonnegative. Importance/activity are in [0,1].
`withActivity(ActivityMetadata next,Ownership claims)` retains base embedding/identity
and increments revision with checked overflow. No vector drift is hidden in metadata.

```java
BlockPalette(List<BlockPalette.State> states)
BlockPalette.State(String blockId, Map<String,String> properties)
BlockSample(BlockSample.Occupancy occupancy, int paletteIndex)
GeometryPage(String id,long revision,Bounds bounds,BlockPalette palette,
    SparseOctree<BlockSample> cells)
SourceGeometry(List<GeometryPage> pages,List<FrontierFace> frontiers)
FrontierFace(String dimension,Bounds missingBounds,FrontierFace.Direction direction,
    long sourceRevision,String cursor)
```

* Palette entries are namespaced vanilla/mod block IDs with copied sorted property maps;
  `state(int index)` resolves a palette-local index. The terrain agent resolves these to
  actual registry block states **on its own server thread**. No registry/world accesses here.
* Occupancy is explicitly `SOLID` or `AIR`; Minecraft air/cave_air/void_air must be `AIR`.
  Non-air palette states must be `SOLID`; this means observed non-air occupancy, not proof
  that a fluid or decorative block has a full collision cube. Keep actual block-state physics.
* `null` octree samples mean **UNKNOWN**, never air. Frontier directions are
  `WEST,EAST,DOWN,UP,NORTH,SOUTH`. Missing bounds, observation revision and resumable cursor
  are separately persisted; they are never inserted into known-air cells. Empty frontiers
  permit identity finalization, but do not prove geometric classification/connectivity.
* `GeometryPage` is an immutable final value class (constructor/accessors/equality preserved,
  not a Java record); the internal incremental codec certifies leaves as it reads them.
  A page is <=64 blocks on each axis with <=32768 known leaves and <=4096 palette entries.
  Source geometry sorts pages/frontiers. Page AABBs may overlap only if their known masks
  are disjoint; conflicting known occupancy must be explicitly reconciled by an extractor.
* `GeometryPage.knownCells()` returns full known leaves. `SourceGeometry.sample(long x,
  long y,long z)` returns `BlockSample` or null. `materialAt(long x,long y,long z)` returns
  `Optional<SourceGeometry.MaterialCell>` (a one-block material probe). Use this for terrain,
  rather than interpreting a page-local palette index without the page.
* `query(Bounds range,int maxCells)` returns `List<SparseOctree.Cell<BlockSample>>`;
  `queryMaterials(Bounds range,int maxCells)` returns `List<SourceGeometry.MaterialCell>`.
  `MaterialCell(Bounds bounds,BlockSample sample,BlockPalette.State material,String pageId)`
  resolves the correct palette. Queries return **full intersecting leaves**, not clipped cubes.
  Over-budget queries throw, never silently truncate topology. `frontierClosed()` is public.

## Sparse adaptive octree

```java
static <T> SparseOctree<T> empty(Bounds root,int resolution,long maxSide)
SparseOctree<T> with(Bounds region,T value,int nodeBudget)
T sample(long x,long y,long z)
List<SparseOctree.Cell<T>> query(Bounds range,int maxCells)
List<SparseOctree.Cell<T>> cells(int maxCells)
Bounds rootBounds(); int resolution(); long maxSide();
```

`Cell<T>(Bounds bounds,T value)` contains immutable caller values. Root is cubic;
root side, resolution and maxSide are powers of two, with root >= resolution. Region
boundaries must be aligned to resolution (in blocks). Partial writes refine uniform nodes;
identical siblings coalesce. Null erases known data back to unknown. Root doubles toward
out-of-range writes without moving absolute data or changing IDs. Expansion and recursive
updates consume a bounded node budget; a failed write leaves the old tree intact. A caller
must choose its sampling resolution/budget explicitly; no refinement beyond resolution.

## Activity, ownership, merging, splitting and separate queries

`ImportancePolicy(double cap,double halfLifeTicks,double growthPerTick,double activityWeight)`
uses a positive cap <=1, half-life in **server ticks**, growth in activity units/tick, and
activityWeight in [0,1]. `ActivityMetadata(double level,long evaluatedTick)` exposes
`decayed(long tick,ImportancePolicy)` and `advance(long tick,double stimulus,ImportancePolicy)`.
Growth <= growthPerTick * elapsed ticks; same-tick stimulation cannot grow activity.
Time reversal fails. Base embedding never changes. `score(double distanceSquared,double
radius,double base,ActivityMetadata activity,long tick)` gates **before** evaluating
importance/activity; it is exactly zero at/outside radius. Inside, it applies capped
base+activity with `(1-distanceSquared/radiusSquared)^2` compact support.

`Ownership(List<Ownership.Claim> claims)` / `Claim(UUID player,long claimTick)` preserve
claims separately from feature IDs. `owner()` returns `Optional<UUID>`: earliest tick,
then UUID is the deterministic conflict winner. `merge(Ownership)` is commutative and
retains earliest claim per player. Activity/claim integration must enforce player quotas,
claim radii and budgets; this core does not authorize claims or silently discard them.

`LandmarkRepository()` is a pure server-thread-confined catalog. Public signatures:

```java
record RevisionRef(String id,long revision)
record VerifiedConnectivity(List<RevisionRef> fragments,String evidence)
record Snapshot(List<Landmark> landmarks,Map<String,String> aliases,
    Map<String,Long> tombstones,Map<String,List<String>> lineage)
static LandmarkRepository restore(Snapshot s)
void put(Landmark value,long expectedRevision) // -1=create; otherwise live canonical CAS
String resolve(String id); Optional<Landmark> get(String id);
List<Landmark> landmarks(); Snapshot snapshot();
Landmark mergeVerified(VerifiedConnectivity proof,long tick,ImportancePolicy policy)
Landmark mergeVerified(VerifiedConnectivity proof,long tick,ImportancePolicy policy,
    SourceGeometry reconciled)
void split(RevisionRef parent,List<Landmark> children)
void delete(RevisionRef ref)
List<Landmark> sourceRange(String dimension,Bounds range,int maxResults)
List<Landmark> semanticRange(LandmarkEmbedding current,double radius,int maxResults)
```

A merge requires an extractor's **physical** connectivity proof with revision guards;
semantic similarity NEVER merges feature identity. Domains (dimension/algorithm/kind/biome/
profile) must agree. Canonical seed is minimum lexicographic ID; its original anchor/base
vector survive. Importance/activity use capped maxima, not additive unbounded growth;
ownership merges separately. Revisions become max(input revisions)+1. Aliases flatten
persistently. Default merge unions masks/frontiers; a supplied reconciled geometry can
close frontiers/add observations, but must preserve every previous known material.
Conflicts or stale results fail atomically.

Split children must be freshly recomputed, uniquely seeded, same-domain, within parent
bounds, and higher revision. Child retaining the original seed may retain parent ID;
otherwise parent tombstones. Old aliases resolve the tombstoned parent, **never an arbitrary
child**; sorted one-to-many lineage names all children. The extractor must establish split
connectivity (bounding boxes alone do not). Tombstones/aliases cannot be revived by put.
Source-range queries only intersect source AABBs; semantic queries only use strict radius
and exact profile. Both are deterministic and explicitly reject result-budget overflow.

## Radius-gated representative selection (not nearest K)

```java
RepresentativeSelector.Config(double semanticRadius,double clusterRadius,
    int maxCandidates,int maxRepresentatives,double spatialCellBlocks,int perCellQuota,
    double distortionPenalty,double crowdingPenalty,double replacementPenalty,
    double hysteresis,double blocksPerSourceBlock)
RepresentativeSelector(Config config)
RepresentativeSet select(Collection<Landmark> input,LandmarkEmbedding current,
    long currentRevision,ProjectionFrame frame,Point3 player,FogHorizons horizons,
    ImportancePolicy importance,long tick,long clusterEpoch,
    RepresentativeSet previous,Set<String> pinnedIds)
record Representative(String seedId,String landmarkId,double importance,double distortion)
record RepresentativeSet(long attunementRevision,long projectionEpoch,long clusterEpoch,
    EmbeddingProfile profile,List<Representative> representatives)
record SelectionStats(long semanticDistanceEvaluations,long medoidComponentOperations)
SelectionStats lastStats()
```

Semantic radius is Euclidean semantic units; cluster radius is the strict semantic
reconstruction radius. Cells/crowding use **absolute projected block** coordinates.
Candidate budget <=4096, representative budget <=128. Input IDs must be unique and all
profiles compatible. Pre-query/budget the input enumeration upstream: selection bounds
its medoid candidate work but still inspects all supplied candidates for hard gates.

Gates are strict current-attunement semantic radius, then projected AABB distance <
prefetch horizon, **before** importance, pins, budget scoring, or clustering. TARGET
attunement must not be passed here as if it were CURRENT. Pins never bypass distance gates;
over-budget eligible pins/spatial pin quota conflicts throw instead of silently evicting.

Candidate budget uses bounded importance and ID ties, not nearest-distance truncation.
Existing seed IDs survive compatible projection/cluster epochs, then deterministic weighted
farthest-coverage seeds fill uncovered clusters. Members use nearest assignment center inside
cluster radius; ties use IDs. Unpinned clusters use their stable seed as assignment center.
A pinned medoid becomes its seed's assignment/coverage center, so members are deterministically
reassigned before pin decisions; retained unpinned medoids must certify the original group. Representatives minimize importance-weighted semantic
reconstruction error + projection distortion + projected crowding + replacement cost.
Semantic/error terms are normalized by semanticRadius squared; distortion measures lost
squared semantic separation in the frozen 3D basis. Hysteresis is an additive normalized
cost allowance for the retained medoid. Pinned medoids retain prior seed IDs when eligible.
Spatial quotas apply during medoid choice. Every replacement and hysteretically retained
medoid must certify strict cluster-radius coverage of every assigned member. The assignment
center is already certified by membership gates; other candidates use a conservative
coordinate-AABB farthest-corner certificate. This can reject an otherwise valid medoid,
but never accepts an out-of-radius one. Pins remain centers and cannot retain far members.
Uncovered/quota-constrained clusters can remain unrepresented; the result need not contain
exactly maxRepresentatives.

Weighted local-origin Welford moments evaluate quadratic semantic reconstruction and
projected reconstruction in O(dimensions) per medoid, not all-pairs work. Distortion is
clamped aggregate lost squared separation. With C capped candidates, K representatives
and D dimensions, assignment/selection is O(C*K*D), excluding supplied-input enumeration
and sorting; single-cluster medoid evaluation is O(C*D). `lastStats()` exposes deterministic
operation counters for the latest call; selector instances are thread-confined. Retention
hysteresis never bypasses the radius certificate.

## Frozen continuous projection, placement, dither and fog

```java
ProjectionFrame(long epoch,long seed,LandmarkEmbedding semanticOrigin,Point3 realmOrigin,
    Vec384f x,Vec384f y,Vec384f z,double blocksPerSemanticUnit)
Point3 ProjectionFrame.project(LandmarkEmbedding vector)
Placement ProjectionFrame.place(Landmark landmark,double blocksPerSourceBlock)
Placement(String landmarkId,long projectionEpoch,Point3 realmAnchor,
    BlockPoint sourceAnchor,double blocksPerSourceBlock)
Point3 Placement.projectSource(Point3 sourcePoint)
RealmBounds Placement.projectedBounds(Bounds source)
Placement Placement.transitionTo(Placement next,double progress,boolean collisionPinned)
FogHorizons(double visible,double hidden,double prefetch)
double FogHorizons.opacity(double distance)
boolean FogHorizons.shouldPrefetch(double distanceSquared)
double BorderDither.sample(long worldSeed,long featureSalt,long x,long y,long z)
double BorderDither.smoothstep(double t)
boolean BorderDither.chooseIncoming(long worldSeed,long featureSalt,long x,long y,long z,
    double incomingWeight)
```

Frame basis must be unit/orthogonal (tolerance 1e-4); vectors/origins are frozen. Axis/epoch/
seed/origin/scale getters are public. Projection is absolute origin plus dot-product
difference * blocksPerSemanticUnit. No camera-relative origin exists. Source placement is
uniformly scaled translation relative to stable source anchor/ID. Smoothstep transitions
preserve exact endpoints and reject changed identity/source anchor/scale; collisionPinned
returns the old placement. Layout/basis alignment and epoch persistence are upstream
responsibilities, not hidden camera transforms.

Horizons are finite block distances with `0 <= visible < hidden < prefetch`. Opacity is
smoothstep between visible/hidden; prefetch uses strict squared distance. Dither is a pure
53-bit hash sample in [0,1) of absolute block coordinates, world seed and stable feature
salt. Incoming choice is sample < smoothstep(weight), with exact old/new endpoints.
Use the SAME coordinate, salt, weights and neighbor halo on both sides of a chunk border;
never seed with chunk index, frame or camera. Preserve collision floors/portals upstream.

## Paged NBT PersistentState

```java
static LandmarkStore get(MinecraftServer server)
List<String> ids(); String resolve(String id);
Optional<LandmarkMetadata> metadata(String id)
List<LandmarkMetadata> sourceRange(String dimension,Bounds range,int maxResults,int maxScanned)
List<LandmarkMetadata> semanticRange(LandmarkEmbedding current,double radius,int maxResults,int maxScanned)
GeometryRead beginGeometryRead(String id)
int GeometryRead.advance(int maxPages,int maxLeaves)
List<GeometryPage> GeometryRead.drain()
LandmarkMetadata GeometryRead.metadata()
boolean GeometryRead.complete(); boolean GeometryRead.isCurrent(); void GeometryRead.cancel()
Optional<Landmark> find(String id) // resident-cache only; throws when pages are not resident
record CacheStats(int pages,int leaves,long reconstructedLeaves)
CacheStats cacheStats()
Map<String,String> aliases(); Map<String,Long> tombstones(); Map<String,List<String>> lineage();
PendingMutation stagePut(Landmark value,long expectedRevision)
PendingMutation stageActivity(RevisionRef ref,ActivityMetadata activity,Ownership ownership)
PendingMutation stageMerge(VerifiedConnectivity proof,long tick,ImportancePolicy policy)
PendingMutation stageMerge(VerifiedConnectivity proof,long tick,ImportancePolicy policy,
    SourceGeometry reconciled)
PendingMutation stageSplit(RevisionRef parent,List<Landmark> children)
PendingMutation stageDelete(RevisionRef ref)
int PendingMutation.advance(int maxPages)
int PendingMutation.remainingPages(); boolean PendingMutation.complete();
void PendingMutation.cancel()
```

Types in store signatures `VerifiedConnectivity`/`RevisionRef` are nested in
`LandmarkRepository`; `PendingMutation` is nested in `LandmarkStore`.
Obtain on the **server thread**; store is owned by overworld PersistentStateManager even
for source landmarks in other dimensions. Reads/mutations reject off-thread access. No
source chunk access is performed. One mutation may be pending at a time. Stage calls
validate topology/profile; repeatedly advance with a positive per-tick page budget.

`LandmarkMetadata(Landmark header,List<String> geometryKeys)` is immutable: `header()` has
all identity/embedding/activity/claims/frontier metadata but **no geometry pages**. Its
`id()` and `revision()` delegate to the header; keys refer to opaque immutable page versions.
Do not interpret the empty header pages as known empty terrain. Metadata lookups and
source/semantic ranges never copy or reconstruct geometry NBT. Range calls are separate
source-AABB/semantic-radius operations; require `maxScanned >= ids().size()` and reject
result overflow instead of truncating. Metadata LRU holds at most 128 decoded headers.

One geometry cursor can be active. Advance with 1..8 pages and 1..32768 leaves of work;
return value counts newly reconstructed leaves (cached pages count against the page budget).
Drain the immutable batch before advancing again. Incomplete reads retain only one partial
page and a batch of at most eight pages. Check `isCurrent()` before publishing an asynchronous
result; activity changes also invalidate its metadata revision. Decoded geometry LRU is
bounded by `DECODED_PAGE_LIMIT=8` and `DECODED_LEAF_LIMIT=65536`; original page versions
are immutable and repeated resident reads reuse them. `find()` never performs cold geometry
reads: warm all required pages first, or stream larger landmarks page-by-page via the cursor.
`find()` throws if the full geometry exceeds these residency limits or has missing resident
pages. Geometry-dependent merge/split convenience APIs likewise require resident geometry;
large extraction/topology integrations must explicitly stream/reconcile within their budgets.
`stageActivity()` retains opaque references and CAS-updates activity/claims without leaf
reconstruction; each retained reference still consumes one bounded advance operation.

Save key is `mysticism.landmarks.v1`. Geometry versions and landmark metadata versions are
independent immutable PersistentStates, keyed `mysticism.landmark.geometry.<id>.<revision>`
and `mysticism.landmark.record.<id>.<revision>`. Metadata refs are limited to 4096 geometry
pages, 4096 frontiers, and 256 claims; excessive metadata must be partitioned upstream and
is rejected, never silently truncated. Pages are only encoded when advanced; unchanged
versions consume bounded work without being dirtied. Advance dirties at most maxPages
states, counting final manifest publication. Catalog/aliases become visible only after
all pages are staged. Cancellation leaves old topology and unreferenced pages.

Vanilla `PersistentStateManager.save()` performs disk writes; staging does not force IO.
States use `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` (required non-null in Yarn 1.21.1),
with independent strict landmark schema 1; no automatic custom-schema migration. Missing
or invalid refs/pages/embeddings fail loudly. Vanilla conflates missing files with decoder
failures; this store only creates a page after positively checking file absence. An existing
unreadable geometry/record file throws and is never dirtied or replaced, even when vanilla
has cached a failed read as null. Vanilla files are **not a multi-file crash
transaction**: an interrupted cross-file save may require repair and will fail on missing
pages rather than invent empty geometry. Old/unreferenced versions are retained (no unsafe
automatic deletion); vanilla owns its state cache. Budgeted writes are not a claim of
whole-world memory eviction or disk compaction.

`LandmarkNbt` public strict codecs: `encodeProfile(EmbeddingProfile)`,
`decodeProfile(NbtCompound)`, `encodeGeometry(GeometryPage)`, `decodeGeometry(NbtCompound)`,
`encodeLandmark(Landmark,List<String> geometryKeys)`,
`decodeLandmark(NbtCompound,Function<String,GeometryPage> geometryLoader)`,
`encodeMetadata(LandmarkMetadata)`, `decodeMetadata(NbtCompound)`,
`hydrate(LandmarkMetadata,List<GeometryPage>)`,
`putBounds(NbtCompound,String,Bounds)`, `getBounds(NbtCompound,String)`.
Encoders return compounds except putBounds. Store also exposes standard
`fromNbt(NbtCompound,RegistryWrapper.WrapperLookup)` / `writeNbt(...)` PersistentState hooks.
Manifest aliases/tombstones/lineage are validated without loading geometry pages.
`GeometryDecoder(NbtCompound)` offers `advance(int maxLeaves)`, `leafCount()`, `complete()`
and `result()` for incremental reconstruction. Do not mutate its NBT input while a decode
is active. Serialized leaves must be aligned power-of-two cubes so one entry cannot expand
into many leaves and evade budgets. Header/palette decode and vanilla compressed-NBT reads
are page-bounded but synchronous: leaf/page budgets are **not** a wall-clock/byte IO guarantee.
Vanilla retains its raw PersistentStates independently of this bounded decoded cache.

## Checks and actual build status

Run from repository root:

```bash
bash src/test/java/io/github/mysticism/landmark/run-self-tests.sh
# Optional, only using already available mapped Yarn Minecraft/runtime dependency jars:
MYSTICISM_MINECRAFT_CLASSPATH="<existing classpath>" \
  bash src/test/java/io/github/mysticism/landmark/run-self-tests.sh
```

No JUnit/new dependencies, assertion-enable flags, models, server/world boots or installs.
The runner reports persistence checks NOT RUN when that classpath is absent. It retains
scratch class/test data directories. Tests use explicit deterministic checks, fixed-seed
randomized octree writes, negative boundaries, order-independent medoids/aliases, radius
caps/pins, projection/dither borders, reconciliation, strict NBT, bounded staging/cancel,
thread guards, and actual compressed PersistentState disk reload of aliases/splits.
The disk fixture uses an identity DFU test helper; production uses the server's real fixer.
Review regressions cover compressed missing-payload and raw unreadable geometry/record
files (expected vanilla error logs), byte-for-byte preservation after failed updates/save,
metadata-only lookup/ranges/activity CAS, incremental 32768-leaf checkerboards, decoded LRU
limits, cancelled/stale cursors, adversarial radius-invalid replacements/retention/pins,
weighted-moment agreement with a quadratic reference, and deterministic operation caps for
4096-candidate single-cluster selection. Timings are printed for diagnostics, never flaky
pass/fail thresholds. Latest executed run: 802 core checks and 909 disk/NBT checks.

Full Gradle `compileJava compileTestJava` was attempted with the supplied Gradle 8.13;
it fails during existing build configuration because Loom 1.11-SNAPSHOT requires >=8.14.
No Gradle/plugin installation or out-of-scope build modification was made. Separate Java21
compilation and both explicit test mains run against the existing real mapped Yarn1.21.1
Minecraft/runtime artifacts; this does not claim full Gradle/project build success.
