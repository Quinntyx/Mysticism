# Truthful runtime mode/source status (retained unit 10)

Scope: the per-player navigation controller, its source-discovery callbacks and
`/spirit` diagnostics. This does **not** claim to implement the other ten assigned
runtime units or restore a model runtime.

## Runtime corrections

- `SpiritNavigation.semanticReady` is the controller's single authority. A known
  landmark ID, existing metadata or a nonzero vector cannot infer readiness on
  session restore. Restore also requires a compatible, available `LatentPos`
  payload. A genuinely initialized ZERO coordinate remains valid; reset/corrupt
  coordinates are explicitly unavailable. Current native-profile saves lacking
  the new availability marker remain readable, but still need saved navigation
  permission. No old-profile/dimension migration, vector truncation or fabricated
  model embeddings were added.
- Source, concept and retained/unknown initialization provenance are persisted
  separately from walking/flight mode. Source callbacks bind the real source
  without transiently toggling shallow/deep or clearing a pending walk request.
  Terrain acknowledges delivery only when navigation accepts that exact source
  binding. Rejected/foreign/concept callbacks cannot silently suppress discovery.
- Every source initialization goes through terrain's carrier-preserving delivery
  path. An unready restored window keeps its original coordinate until acceptance;
  it cannot pre-apply metadata and then double-apply the source-coordinate offset.
  Ready known native source bindings can initialize through exact octree proof
  without waiting for fresh inference.
- Initial shallow gait on actual accepted mesh collision is not evidence of a
  semantic anchor. Existing shallow gait on physical mesh support can await a
  pending ownership refresh; unavailable proof is not automatically leaving the
  floor. A current proof of lost/changed ownership still enters deep. These
  continuation rules grant no source exit, new support acquisition or landing
  permission. Collision/render producers, source-coordinate ownership/growth and
  independent player projection remain unchanged.
- Walk and captured-landing approaches are session-local, cleared on restore,
  disconnect/respawn and explicit concept-position replacement. Walk expiry and
  failure reasons remain visible after the request ends. The displayed countdown
  uses the same 200-tick deadline as the controller, not a guessed duration.
- Explicit ZERO targets are not overwritten as though they were unset. Captured
  destinations remain snapshots with exact fractional source feet.

## Diagnostics and retry

`/spirit` separately reports physical mode, coordinate readiness/provenance,
observer-local source discovery, retained source-window coordinates (explicitly
**not** the current deep-flight location), destination kind, walk/landing approach
and last transition. There is no blanket “free flight awaits discovery” message.

Discovery distinguishes no source, actual embedding/index readiness gating,
queued/running generated-source work, exact ownership validation, verified source
cell, empty ownership and real failures. The actual Ensure operation records its
embedding wait, including the case where a populated item index has no matching
fallback descriptors. Another player's global service status is never used.
Exact current proof outranks an older request's outcome; neither is landing proof.

`/spirit discover` retries only this observer's retained physical source. One
request stays pending through server-thread completion delivery. Replaced or
cancelled callbacks cannot attach ownership to a new window. Failures include a
bounded sanitized cause, `/myst embed status` and a retry action. A spirit arrival
without any retained source instead explains real source-world re-entry or the
existing `/latent set pos item <ready-item>` concept-coordinate action. No
fallback floor, substitute destination, external-server requirement or model
configuration was introduced.

## Regression/verification contract

The two new main-based tests live in `src/runtimeTest/java` (the canonical Gradle
runner intentionally excludes the archived shared-frame/block prototype suites):

- `SpiritNavigationReadinessTest`: actual native NBT serialization, reset vs
  initialized ZERO, missing/corrupt/profile-incompatible coordinate rejection,
  source-less concept restore, unknown/invalid provenance, late discovery without
  mode churn, pending shallow physical support policy, independent components,
  carrier-minus-q invariance and native tail preservation.
- `SpiritNavigationStatusTest`: actionable status combinations, exact fractional
  target feet, unset vs concept destination, walk expiry/approach distinctions,
  per-observer discovery completion/retry/cancellation and stale callback fencing.

Tests do not bootstrap Minecraft, call model inference or download weights.
Gameplay, multiplayer entry/flight/ground contact, GPU visuals and live model
inference still require an actual Minecraft launch. The deterministic checks do
not establish those outcomes.

## Checks actually performed on this candidate

The installed Gradle 8.13 distribution ran the canonical command with no task
exclusions or diagnostic classpaths:

```
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --rerun-tasks build
```

Result: exit 0, 14 executed tasks. `runtimeSelfTest` discovered and executed all
six current main-based classes: callback isolation (28 checks), engine safety
(184), embedding persistence (90), embedding pipeline (135), navigation readiness
(44), navigation diagnostics/discovery (29). `buildSelfTest` passed packaging,
14 descriptor-only rejection regressions and runner-discovery/failure fixtures.
`verifyProductionJar` passed the remapped `build/libs/mysticism-1.0-SNAPSHOT.jar`.
The framework `test` task is intentionally disabled by the existing runner;
these are real executed main-based suites, not inferred test discovery.

Final retained tmux handle: original pane `%1920`, session `$11`, window `@1260`,
job pane `%1975`, run `5e825acd334e4d70a7d5540f6bd9b1ac`. Build emitted existing
API Guardian/mixin/deprecation warnings and expected corrupt-input warnings in
negative persistence tests. `git diff --check` passed. No Minecraft launch,
multiplayer/GPU gameplay or live model inference was performed. This unit leaves
the separately owned in-process embedding restoration requirement unchanged.
