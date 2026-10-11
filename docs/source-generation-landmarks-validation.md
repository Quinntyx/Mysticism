# Retained source-generation landmark growth repair

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
