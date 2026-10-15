# Retained runtime unit: late walking recovery

Scope: one already-issued current-support walk request survives asynchronous source
ownership, geometry and exact material-proof publication, without another command.
No entry teleport, rendering, collision algorithm, projection/octree ownership or
embedding backend/profile change is part of this unit.

## Production path

- Each navigation session owns a `WalkRequestClock`. Explicitly repeated walking
  packets do not replace an active/suspended intent or reset its budgets.
- Terrain's `currentSupportReadiness(player, candidate)` distinguishes a candidate
  from published material proof. Future completion alone is not publication.
  Missing/stale ownership, source ingestion and mesh streaming pause alignment
  only while waiting for the actual current support; unrelated representative or
  target work is ignored. Once current support is proved, background streaming
  does not block it. Failed/cancelled material proof is not declared ready.
- Alignment still has 200 ticks; source wait has 600 ticks, both cumulative per
  attempt so intermittent readiness cannot reset either budget. A source-wait timeout
  clears the synchronized support-approach prediction flag and resumes normal
  deep flight, retaining the issued intent and in-flight proof for at most 1200
  more ticks. These are server movement ticks, not wall-clock guarantees.
- Published proof on current owned contact automatically resumes the same intent,
  at most three times. Recovery starts interpolation from the *current* basis,
  retaining the previous support identity/grid if one was validated, and keeps
  the frozen captured target. This is not a remembered-entry/target landing.
- Changed captured attunement, explicit flight, lifecycle cancellation, alignment
  failure, recovery timeout and successful acquisition disarm recovery. Unload,
  disconnect and shutdown cancel retained proof as well. Late real initialization
  of an absent default attunement updates the pending snapshot, not user capture.
- Actual acquisition still runs the existing window/ownership-revision, source
  body/floor, basis and whole-frame continuity checks. No fabricated q, fallback
  floor, teleport, synchronous source IO, or substitute ownership was introduced.

## Regression coverage and validation limits

`src/runtimeTest/java/io/github/mysticism/navigation/WalkRequestRecoveryTest.java`
exercises nine groups against the actual production clock: late arrival beyond
200 ticks, preserved alignment and source-wait budgets across intermittent readiness, single-shot recovery,
finite retry/deadline bounds, alignment-only failures, ready contact versus
background streaming, cancellation/new requests and independent player clocks.

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/WalkSourceReadinessTest.java`
runs the production proof classifier with inflight/completed/unpublished/published,
failed/cancelled/missing and mismatched-contact cases; it also exercises late proof
publication through the real clock without reissuing. Neither suite creates a
Minecraft server/player or a fake terrain/landing implementation.

Canonical validation command, using the cached distribution in the assigned
checkout and a retained `tmux-jobs` pane:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain --rerun-tasks build
```

This invokes discovered runtime suites plus packaging/runner regressions,
common/client compilation, remapping and production-artifact verification. The
final rerun completed successfully (exit 0): all 14 actionable tasks executed;
`runtimeSelfTest` discovered and passed six classes, including both new walking
suites and four existing embedding suites; `buildSelfTest`, `remapJar` and
`verifyProductionJar` passed. Static build success is not gameplay or
model-inference verification. Still needed in integrated client/server play:
walk during genuinely slow discovery, cancellation while suspended, continuous
basis alignment and support acquisition, and repeated abilities packet behavior.
No embedding model/server was exercised or added, and no native-v2/v1 migration,
truncation or dummy-vector behavior was changed.
