# Retained source-generation landmark growth repair

> The original sections below record candidate `5b93a52` and its then-known limits.
> The reference-directory extension at the end supersedes its 4,096-reference limit;
> the retained commits/review history have not been reset or replaced.

Scope: `feat-runtime-discovery-generation-landmarks`, targeting dev. This is the
source-generation unit, not a claim that the other ten reported runtime units
have been fixed.

## Behavior

Generation surveys retain each actual component/peak probe from loaded source
chunks (including spawn chunks loaded before service startup). Readiness cannot
acknowledge a probe, nor can a nearby landmark's bounding box. The generation
queue retires work only after publication owns the exact sampled source cell.

`Ensure` now reads whole canonical eight-block pages in a source-coordinate
window around its observation and adjacency halo. It no longer hydrates and
expands a landmark's entire lifetime mask. Local growth excludes every competing
owner; disconnected local arms of a globally connected parent do not falsely
reject an adjoining flood. Preferred activity still cannot create a remote
island. The ownership revision guards all candidate masks before publication.

`LandmarkStore.stageSourcePatch` retains unread immutable geometry references and
CAS-publishes only the fully read local pages. Bounded old-leaf validation rejects
loss of any previously owned voxel, while allowing real observed material
changes. Parent identity, full bounds, native embedding profile, activity and
claims remain intact; no chunk-shaped identities, projected block worlds or
embedding migration are introduced.

## Regression coverage

`GenerationSurveyContiguousGrowthTest` is discovered by `runtimeSelfTest`, using
the existing assertion-enabled main-based runner. Its adjacent radius-four 9x9
survey grid prepares against actual persisted parents, publishes through the
production patch API, and checks exact ownership before retiring each probe.
It cold-reloads during growth and again after completion. The final mask owns
421,362 cells across 1,200 pages: beyond both the former 131,072 expanded-cell
and 512 full-parent-page operation limits. All 81 probes drain, with one canonical
source identity and unread remote page versions preserved. Unknown space remains
unowned.

Additional cases exercise rejected ownership loss/stale revisions/unread-page
overwrite, actual source material refinement, local U-shaped connectivity,
remote-island rejection and exclusion of competing biome ownership. Fixture
vectors are explicit store-test data, not production embeddings or inference.

## Verification and limits

Canonical verification uses the installed Gradle 8.13 distribution, offline:

```text
gradle --offline --no-daemon --console=plain --max-workers=4 clean build
```

This invokes all discovered current runtime suites, `buildSelfTest`, common and
client compilation, remapping and `verifyProductionJar`. There are ten discovered
runtime test classes on this branch. The old framework `test` task is intentionally
disabled; it is not the runtime regression runner.

No Minecraft client/server gameplay, frame-time/render-distance convergence
benchmark, or live model inference was performed. The existing separate
4,096-reference metadata ceiling and full-parent transfer/merge budgets remain;
this repair removes the reported survey-operation limits, not those catalogue
or topology limits. Large landmarks still require bounded traversal of their
reference headers, so latency at larger view distances remains unmeasured.

## Reference-directory growth extension (retained review cycle)

The radius-nine rejection was real: local source patches still assembled one
4,096-reference metadata header. The adjoining probes could prepare correctly yet
fail publication forever. This extension pages **aggregate references**, not source
ownership, and leaves the prior five retained commits/history intact.

`GeometryReferenceIndex` stores content-addressed immutable directory pages, each
with at most 256 opaque geometry-version keys and an optional next-page key. A large
record contains one root and an exact count, not a bounded list of directory roots.
There is no configured total reference ceiling. Old inline records remain readable
and keep their existing content keys; paged records explicitly use metadata layout
schema 2 so old readers reject them rather than accept empty geometry. This layout
version is unrelated to embedding generations: native source-v2 profiles and vector
bits are unchanged, with no v1 embedding migration, truncation or server prerequisite.

All metadata publication paths stage directory pages tail-first under the existing
`advance(maxPages,maxLeaves)` work budget before publishing the record/catalogue.
Cancellation, retries and cold reload preserve the committed root. Local patches
still replace only fully read pages, conserve every prior owned voxel, and retain
all unread geometry keys verbatim. A canonical landmark is never subdivided into
chunk-shaped IDs. Claims/frontier and hydrated single-observation budgets remain.
Missing/corrupt directory pages fail loudly and are not treated as empty terrain.

### Final checks actually run

Canonical installed Gradle 8.13 / Java 21 command (offline, no exclusions):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --max-workers=4 clean build
```

Final run: **BUILD SUCCESSFUL**, 15 tasks executed, 1m29s. Original tmux connection
`/tmp/tmux-1000/default,30794,11`, origin `%3568`, job session `$11`, window `@2171`,
pane `%3569`, retained run token `63166891a8334d22a40b5e637d3f99f0`, exit 0.
An earlier iteration also passed; the final run includes explicit schema rejection
and on-disk missing/corrupt directory regressions.

- Runner discovers and executes **11** runtime test mains, assertions enabled;
  the intentionally disabled framework `test` task is not mistaken for coverage.
- `GenerationSurveyContiguousGrowthTest`: 3,617,794 checks. Adjacent 19x19
  generated-chunk survey debt drains all **361** probes through production local
  preparation and store publication against persisted parents. It crosses 4,096
  refs at probe **305**, cold-reloads there, then publishes the remaining **56**
  adjoining probes. Final union: **1,763,442 cells, 4,800 pages**, one canonical ID.
  Every probe's floor/air ownership survives final cold reload; unknown stays
  unowned and unread remote references stay unchanged. The suite also checks
  activity-only publication without leaf hydration, directory staging cancellation
  and retry across restart, and missing/corrupt on-disk directory rejection.
  Retained fixture: `/tmp/generation-contiguous-5286684242992249050`.
- `GeometryReferenceIndexTest`: **655** checks, including 4,096/4,097/4,800 and
  **70,001** reference roundtrips, independent page bounds, exact native embedding
  preservation, incompatible schema/count/type/key/missing-tail rejection and
  duplicate page-ID rejection across directory pages. These are explicit fixture
  vectors, not model inference.
- All other discovered runtime mains pass: embedding callback isolation, engine
  safety, persistence and pipeline; generation overflow/startup/survey; source
  hint retention and hint integration.
- `buildSelfTest` passes packaging, descriptor and test-runner discovery/failure
  fixtures. Common/client compilation, sources/remapping and `verifyProductionJar`
  pass; distributable: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
- `git diff --check` passes. Compiler warnings remain (API Guardian annotations,
  existing mixin/deprecation warnings). Deliberately malformed NBT fixtures log
  expected errors before their rejection assertions pass.

### Remaining limits / not verified

No live Minecraft gameplay, frame-time/render-distance convergence measurement,
GUI/render/collision exercise, or real model inference was performed. This unit
changes neither projection nor mesh/collision implementation nor model transport.
Directory reads, key construction, metadata cache memory and range traversal remain
linear in total reference count; cold metadata IO is synchronous on the owner
thread, so paging is not a whole-tick latency bound. Directory versions can amplify
retained disk/cache usage; no compaction is added. Vanilla multi-file saves are not
crash-atomic and fail on missing dependencies. Full-parent transfer/merge operation
budgets remain separate limits. Background surveys still await real embedding/index
readiness and yield to explicit requests; major-component surveys do not claim to
observe every cell of every source chunk. The other ten runtime units and integrated
gameplay/model verification remain parent-owned, not claimed fixed by this patch.
