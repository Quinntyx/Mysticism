# Runtime integration 0056

## Candidate and retained work

Owned branch: `feat-runtime-sol-merge-0056` (development only). This completes the
interrupted merge of `8265011603975edcc72328376efecfe643b8cb61` and
`8c79fea0fec1d25d6e63b75ebea0d48098b2a471`; neither parent's commits were rewritten.
Existing staged and unstaged work was retained and integrated, not reset/replaced.
The final candidate is the commit containing this handoff (`git rev-parse HEAD`).

## Reconciled runtime behavior

- Keep observer-frame prediction and both teleport re-baselines: epoch changes and
  actual arrival packets. The predictor publishes its render frame even at rest;
  it never writes predicted coordinates into authoritative synced components.
- Pending walk movement uses the same current observer basis as client prediction,
  not a prematurely substituted destination grid. Alignment/acquisition still use
  validated source-owned geometry. Post-alignment residual slides remain guarded.
- Merge swept mesh stepping/depenetration with claimed-endpoint validation and
  natural descending contact. Preserve body clearance, source-cell ownership and
  independent per-player deep/shallow collision/render frames.
- Shallow walking uses the single physical landing-envelope decision, with a
  bounded unsupported interval. Remove the dormant competing rising-latch state
  and ejection API. A one-source-block descent fits the envelope; real cliffs
  still enter flight. Arrival clears measured support and accepted momentum only
  for that player; acquisition seeds its new measured floor before handoff.
- Retain shallow source cells and their producer provenance intact. Deep-only
  duplicate stitching must not rewrite shallow locality membership or ownership.
- Combine asynchronous walk discovery/retry and natural-landing recovery with
  independent entry/exit, source-anchor readiness and restore state. Entry remains
  seamless, recoverable and per-player; no shared readiness flag substitutes for
  an individual player's scene acceptance.
- Keep real uploaded mesh depth/evidence, source-space ownership/growth and
  distortion, plus bounded render-distance discovery/culling. Merge geometry
  normals/bounds/support fixes rather than restoring proxy block obstacles.
- Compose Kuwahara on unfogged terrain, then volumetric fog, saturation and source
  transition. Bind medium occupancy after this frame's terrain publication, clear
  stale medium/depth evidence, reset fog-hook observations each frame and use the
  same fade policy for native-fog bypass and postprocess. Retain effect toggles
  and real fallback/disabled reporting.
- Retain fresh-profile landmark/activity persistence and the in-process CPU/native
  v2 embedding backend, packaged dependencies and actionable desktop failures.
  No fabricated legacy landmarks, v1 migration, dimensional truncation, dummy
  production vectors or external-server prerequisite was introduced.

## Regression integration

The canonical task still discovers all current main-based suites; none was
excluded to make the merge pass. Updated old source-shape assertions to inspect
current render/helper and mixin targets. Shader tests now require the actual
Kuwahara-before-fog chain. Shallow isolation checks assert preserved complete
producer cells rather than deep-only deduplication.

New/expanded exercised regressions include observer-frame publication and
movement through changing frames, split-tick teleport resets, current-frame
medium binding/fog reset contracts, stepped depenetration, half/full-block
terrain descents and a direction-changing pillar detour. The detour reproduces
real endpoint/straight-replay divergence without requiring the old ledge-step
snag to remain broken. Landing-envelope and arrival checks exercise the same
session state/policy as production. Integrated Mixin application covers the
combined entry, arrival, carrier push/auto-jump, collision and movement hooks on
mapped Minecraft classes; it is not a running game.

## Checks actually performed

Original tmux origin `%2945`, session `$11`; long commands used the required
`tmux-jobs` helper and retained pane/run handles.

```text
./gradlew --offline --no-daemon --console=plain --continue build
BUILD SUCCESSFUL in 3m 56s
15 actionable tasks: 9 executed, 6 up-to-date
pane %2960 / run 45753487ffe448a5a92c754384cf6d05 / exit 0
```

- Common/client compilation and source/production remapping passed.
- All **70** discovered current main-based runtime classes passed. Independent
  source enumeration also found 70 `*Test.java` sources, all with public mains.
  Assertions, discovery/order, missing-main/empty-suite rejection and propagated
  test failures were checked by the runner's own fixtures. The framework `test`
  action is intentionally disabled; `runtimeSelfTest` is the real suite.
- Packaging self-tests passed: 27 rejection cases plus valid archive and stable
  intermediary-name cases; descriptor checks passed 14 rejection cases and valid
  signatures/literal preservation; decoded Java 21 metadata checks passed.
- `verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar`.
- Actual bundled JNI ABI/build bootstrap passed (`b10682-5ea1b124e`), including a
  fresh JVM using only the remapped artifact and five extracted nested runtime
  libraries. CPU/platform rejection fixtures passed before JNI/weight access.
- `git diff --check` passed. Earlier build failures exposed conflicting parent
  contracts; the successful full run above includes the reconciled tests/code.

## Real inference blocker and remaining acceptance risks

The separate explicit command was attempted, without enabling weight downloads:

```text
./gradlew --no-daemon --console=plain embeddedInferenceProbe
pane %2964 / run 691559d33bf940fd9778b599aaf40bd8 / exit 1
CPU platform preflight passed: b10682-5ea1b124e
Pinned Nomic v2 GGUF missing: build/embedding-inference-probe/nomic-v2.gguf
```

No model forward pass was performed. Provision the exact 957,680,480-byte GGUF
with SHA256 `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`,
then rerun the probe with `-PembeddingModelPath=/absolute/path/to/exact-v2.gguf`.
Do not substitute a different model or infer inference success from JNI bootstrap.
See [the embedding contract](nomic-embedding-contract.md) and
[desktop runtime requirements](embedded-desktop-runtime.md).

No live Minecraft client/server, IntelliJ launch, two-player network session or
GPU shader execution was performed. Still validate sustained/reversing flight,
jump/step/landing, seamless repeated entry/exit and reconnect, asynchronous walk
recovery, teleport arrival, render-distance growth, two independently projected
players, save/reload, visual toggles, GUI scales/resource reload and fog/filter
composition in the game. Other supported desktop platforms were not executed.
Build logs include nonfatal Gradle 9 deprecation and development-classpath SLF4J
provider warnings; the verified distribution does not bundle duplicate logging
providers. Build/native bootstrap success is not full gameplay acceptance.
