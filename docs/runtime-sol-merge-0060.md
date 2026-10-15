# Retained supported-walking / mesh-movement integration

This development-branch continuation resolves the interrupted merge of
`bc75c3c3f3acc5019182fa9ccb5198774bb9f260` (supported entry, bounded walk
recovery, native-v2 preprocessing) and
`1e9d606635dd3f763e8af92189caa101bdddc4b0` (swept movement, prediction,
approach/arrival and destination arbitration). Both histories are retained.

## Conflict resolution

- `SpiritNavigationService` uses ONE `WalkingModePolicy.Alignment` per pending
  request, plus the bounded `WalkIntent` lifetime and `WalkIntentTracker`
  destination/correction epochs. Duplicate off gestures cannot reset either
  budget. New explicit destinations cancel old support/capture approaches.
- `Alignment` now owns the guarded progress/pace schedule too. Refused mesh
  transitions rebase from the live basis and reduce pace; accepted transitions
  recover it. Support rebinding resets pacing but NOT request lifetime/stall
  budgets. Final float accumulation completes exactly at one; the final step
  still passes the same continuity and swept-mesh guard.
- Approach discovery/prewarming runs below the actual player, with exact
  source ownership and producing-window provenance. It grants no walking or
  exit authority. Acquisition requires current contact, current source/body
  proof, current publication identity and safe frame continuity.
- Real pending proof work in the approach column uses the bounded readiness
  allowance rather than the old 200-tick convergence deadline. Genuine stalled,
  unavailable or unsafe requests still fail truthfully with usable flight.
  Expiry retains prepared proof for a genuine retry, while explicit cancellation
  releases it. A newer destination cannot be revived by old off packets or
  stale async capture completion; flight corrections use an acknowledged barrier.
- The client predictor retains both parents: server-owned approach trajectories
  are adopted, healthy local q/basis prediction is reconciled, and component
  hooks are retired on player/world replacement. Shallow semantic progress is
  not conditioned on a transient authority/ownership gap. Supported source entry
  remains shallow walking; lack of semantic readiness never disables usable
  deep recovery flight.
- `SpiritTerrainService` retains completed, matching absolute source proof while
  recentering descent probes, and offers a depth-aware pending-proof query using
  the same producing floor as the approach query. Ordinary acquisition remains
  at the unchanged .15-block contact distance.
- The mesh parent's SAT/transition guards and final vanilla packet-pose acceptance
  fix remain intact. Packet correction tolerance is not penetration permission.
  Source-coordinate ownership, independent player projections and rendered /
  collidable geometry have not been replaced with block-like collision.
- The selected v2 tokenizer/preprocessor is retained unchanged from the first
  parent: pinned full Unigram vocabulary, Unicode normalization/graphemes and
  512-token overflow rejection, no v1 WordPiece assumptions or vector resizing.

## Regression coverage and actual verification

Cached Gradle 8.13 / Java 21 command:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --continue build --rerun-tasks
```

Exit **0**, **15 tasks executed**, including common/client compilation,
`runtimeSelfTest`, `buildSelfTest`, `remapJar`, `remapSourcesJar` and
`verifyProductionJar`. All **21** runtime main-based suites were discovered and
passed (the framework `test` task is intentionally disabled, not used as proof).

Integration-specific regressions exercise the production alignment object,
not a second test-only schedule: refusal/recovery, late proof after 600 ticks,
duplicate requests, retarget/correction barrier and a fresh binding epoch.
`WalkingModePolicyTest` passed 16,595 checks. The actual mesh-transition
regression now drives that same production object: safely touching tilted/slab
surfaces, two-tick publication cadence, unsafe crossings and sheared samples
passed 5,917 checks. Strict final fraction is separately asserted by
`SupportApproachAlignmentTest` (590 checks). Current support source-contract
checks cover depth-aware proof wiring and retained support budgets.

Other retained coverage: swept collision (110 checks), movement/arrival (737),
entry stance/wiring (555), current supported-walking integration (93), local
pose reconciliation (781), lifetime (9,457), destination/correction arbitration,
source discovery and four embedding pipeline/persistence/async suites. The
v2 tokenizer passed 8,455 checks against the full pinned vocabulary and 2,058
Hugging Face reference cases; all 766 official Unicode 17 grapheme cases passed.
Packaging fixtures cover discovery/order/assertion/failure propagation,
metadata/descriptors, invalid archives and the remapped production jar.

Retained successful build: original tmux session `$11`, origin `%3099`, pane
`%3103`, run `c4e8ba2428c548dab34ec92dc018b53d`. Earlier integrated runs caught
and corrected an actual import/test API mismatch and the 40-step float endpoint
regression; they are not counted as successful verification. Nonfatal annotation,
Mixin/deprecation warnings and expected corrupt-embedding fixture warnings remain.

Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar` (not the dev or sources jar).

## Remaining limits / full-request blocker

No Minecraft/IntelliJ launch, gameplay, packet-latency measurement, visual/GPU
inspection, live async world-generation timing or native model inference was
performed. JVM geometry and Minecraft bytecode-wiring checks do not establish
Fabric injection at launch or multiplayer experience.

The tokenizer is an in-process, model-correct frontend, NOT an inference engine.
`EmbeddingService` still constructs the HTTP Ollama provider in this checkout;
no local weight/runtime provisioning or native inference implementation was
supplied by either retained parent. The broader request to launch with no
external embedding server is therefore **still blocked/not implemented here**.
This assigned tokenizer integration does not disguise that blocker with a dummy
vector, external fallback, v1 migration, truncation or a source-only inference
claim. Fresh native-v2 768-coordinate/profile checks remain enforced.

Unsafe, singular, unowned or never-ready support still legitimately remains
deep after the bounded request fails. Rendering/fog/locality and other full
request items outside this assigned retained unit are not claimed repaired.
