# Retained merge-0067: source discovery and native-v2 integration

Branch `feat-runtime-sol-merge-0067`, for **dev**, never main. This is the combined
retained merge, not a replacement implementation of only its first parent:

- `698cc6c77308758ec373966634d2a5716a8546d0` (merge-0063: vertical discovery,
  supported local assets, native pooling, operational discovery readiness).
- `55e971eaeb417d239827c61af35e83e72391e664` (merge-0032: truthful generated-source
  unload/reload, inference ownership and shutdown, projected picking/feedback).

Existing commits and the in-progress merge were preserved during session recovery.
No reset, stash, other-checkout edit, dev/main update, software installation,
model download or child delegation was used.

## Reconciliation and regression

`SourceLandmarks` retains early `SERVER_STARTING` chunk keys, later adopted by
the source Session, and bounded tick admission across all relevant vertical levels.
The admission path also schedules the other parent's deduplicated full-height
persisted-frontier retry. Hint backpressure retains rejected frontier seeds.
Chunk unload removes only live vertical work: it no longer cancels the generated
observation. The generated-only reader can restart unfinished slices from detached
saved NBT or a newly loaded authoritative chunk. Edits, world unload and stop still
invalidate the corresponding work. Neither absent chunks nor model readiness
invent source cells, owners or navigation anchors.

`EmbeddedInferenceProbe` includes **both** original parent probes: direct native
pooling/tokenizer/L2 checks and the real shared discovery composition, plus complete
real helper lifecycle/concurrency checks across three server-owner identities in
one JVM. The shared discovery probe now observes completed native teardown before
continuing. No probe contribution was dropped to resolve its merge conflict.

The additional `SourceFrontierReloadTest.fullHeightReload` regression extracts
independent generated caves at **y=-55, 40 and 150**, persists and cold-reloads the
actual octrees, then exercises the bounded production retry and ownership readers.
It checks all three height bands resume deterministic frontier hints while known
air retains its canonical ID/anchor and unknown neighbors remain unowned. Together
with the inherited regression cases, the suite now passes **853 checks** (previously
723). These are generated-source fixtures, not a real world/chunk-IO launch.

The merged runtime retains process-exclusive native ownership, serialized complete
inference/output snapshots, asynchronous preparation, owner-identity isolation,
nonblocking stop with worker drain before JNI teardown, and lease poisoning after
failed native disposal. Model errors revoke operational readiness and fresh
discovery; physical source reads remain independent of embedding initialization.
Supported exact local/cache assets and truthful missing/corrupt-asset failures are
unchanged. Native schema 2, all 768 coordinates, exact approved model/tokenizer,
MEAN pooling and stable L2 composition are preserved; no v1 migration/truncation,
dummy vectors or automatic external-server fallback is introduced.

Vanilla spirit carrier picking/highlight suppression and mesh-occluded projected
peer-touch/crosshair feedback are retained. Independent per-player deep/shallow
projection, source-coordinate ownership/growth and custom terrain render/collision
algorithms are not replaced by this merge.

## Checks on this combined tree

Java 21, cached Gradle 8.13; required `tmux-jobs` helper in original session `$11`,
origin `%3663`, actual command argv and bounded monitors. Every recorded result
below is execution in **this** checkout, not inherited parent evidence.

1. `./gradlew --no-daemon --console=plain build`: **PASS**, exit 0, 52 seconds,
   all 15 actionable tasks executed. Pane `%3683`, run
   `258af1b961c04236a93818dcb393fef1`.
   All **25 discovered runtime suites**, packaging/runner fixtures,
   `remapJar` and `verifyProductionJar` passed.
2. `./gradlew --no-daemon --console=plain embeddedInferenceProbe`: **PASS**,
   exit 0, 26 seconds. Pane `%3686`, run `ae5a4925d50249fe8c978cad7b2d5f0d`.
   The normal read-only default cache path resolved the authorized asset; there
   was no explicit model override, provisioning flag, substitute or HTTP service.
3. After adding the full-height reload regression,
   `./gradlew --no-daemon --console=plain runtimeSelfTest`: **PASS**, exit 0,
   16 seconds. Pane `%3689`, run `00e6ece8cb4447e3b2dca8ddbbdf4d89`.
   All 25 discovered runtime suites passed; source frontier reload now 853 checks,
   vertical lifecycle 1000, vertical discovery 8900 and vertical scheduler 43324.
4. Final forced full rerun:
   `./gradlew --no-daemon --console=plain --rerun-tasks build embeddedInferenceProbe -PembeddingProvision=false`:
   **PASS**, exit 0, **47 seconds**, all **16 actionable tasks executed**.
   Pane `%3691`, run `9d4b0340aff148f18ab4f95cbf3ec63b`.
   This includes the new full-height reload regression, all 25 runtime suites,
   packaging/discovery/runner checks, remapped production jar verification and
   the complete real native pooling/concurrency/lifecycle/discovery probe. The
   observed oak/birch cosine was 0.86688715 and oak/lava 0.8003141; these are not
   semantic-corpus quality claims.

The exact approved cache asset is
`/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf`,
957680480 bytes, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
The runtime checks complete-file length/hash before and after allocation. The
standalone real probe reported JNI **b10682-5ea1b124e**, full-768 pooled output and
independent coordinate-by-coordinate L2/weighted composition references, stable
multilingual/canonical landmark requests, no HTTP service, real token-overflow
rejection, concurrent native requests, cache isolation, replacement cancellation,
late-stop isolation, and completed JNI teardown/reopening. Operational native
readiness released discovery admission but did not fabricate a source anchor;
shutdown revoked fresh discovery/inference readiness.

## Limits and risks

No Minecraft client/server/IntelliJ launch, real chunk IO, movement, screenshot,
shader/GPU effect or render-distance performance validation was performed here.
Picking tests exercise offline mixin contracts and projected geometry, not a live
crosshair. This assigned combined unit does not claim all eleven original runtime
problems are gameplay-verified. Native model output checks do not establish
Minecraft-corpus clustering quality or cross-platform compatibility.

The approved runtime emits newline/mask-token, speculative-decoding and absent-GPU
warnings; CPU inference passed without changing the asset. Existing Mixin/annotation,
SLF4J, Jackson-version and Gradle warnings remain. Deliberate fixture teardown and
malformed-state exceptions are negative tests, not actual model failures.
A hung native call/failed close intentionally prevents reopening until it returns
or the JVM restarts; serialization favors safety over throughput. Initialization
performs two full-asset verification reads. Vertical sampling remains bounded,
not an exhaustive cave census. No executed-check, asset or HTTP-service blocker
remains for this combined unit.
