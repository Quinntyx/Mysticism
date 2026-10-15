# Discovery refresh / priority integration

Integrates the retained generation-invalidation parent
`b74f9a19b92e6feba87e8b37dd60a54fd5d2b0d5` and discovery-priority parent
`e82dbdd9320a9a79ab5e90aea8221383d2352aab` on `feat-runtime-sol-merge-0054`.
The two parent handoffs describe their historical builds; this document describes
the combined implementation and supersedes their differing queue/load behavior.

## Combined behavior

- Explicit source-location requests remain exact. Background hints use refresh
  preparation, including generated-evidence fallback and retained whole-window
  follow-ups for undiscovered components. Chunk-load observation restarts preserve
  this distinction, the same caller future, activity/preferred-owner intent and
  discovery priority. A newly available neighbor cannot consume the caller's
  one-shot binding callback with an exceptional cancellation.
- Edits and unloads still cancel stale active observations and staged mutations,
  retaining the original point or refresh area. Cancelled/done observations do not
  suppress their replacement hints. Transient geometry/catalog changes keep their
  existing point/area retry; deterministic budget/corruption errors are explicit.
- One bounded priority hint queue replaces the incompatible FIFO implementation.
  Admission reports both displaced existing hints and rejected incoming hints;
  required refresh work spills into conservative area retries instead of silently
  disappearing. Oldest aged locality is still protected. Read/player urgency,
  cross-queue aging and the shared single-background-dispatch fairness budget are
  preserved, including the reserved active slot during chunk-load restarts.
- Repeating visible samples use at most 120 of 128 hint slots. Eight slots remain
  available for a frontier step, so four new visible samples per tick cannot hold
  all generation-refresh scans behind backpressure forever. Deferred visible
  samples are revisited by the rotating observer/render-distance sweep; they do
  not manufacture unbounded overflow work. Completed background samples remain
  invalidatable by intersecting edits/chunk availability, including partial reads.
- Each five-tick retry turn advances one bounded step, then rotates without
  coalescing or rewinding its cursor. Small refreshes no longer wait for a large
  area sweep to finish. Failed entries remain identifiable for explicit cleanup
  and transient retry. Conservative overflow coalescing retains source coverage.
- Captured immutable geometry key lists/page cursors survive interleaved source
  publications and backpressure. They supply hints only, never current ownership.
  Currentness checks, content-addressed page identity, exact AIR/SOLID ownership,
  activity/claim CAS rebasing and cold persistence behavior are unchanged.

No per-player projection, collision/rendering, movement, shader, embedding
provider, native-v2 profile or vector migration behavior was changed. This is the
two assigned discovery scopes, not a claim to fix all eleven parent problems.

## Regression coverage

The normal main-based runtime runner discovers the tests in `src/runtimeTest`.
The obsolete test-only FIFO was removed; refresh regressions now exercise the
actual priority hint queue, including the existing 130-page/128-hint interleaved
publication, material-change, cold-resume and tail-cave ownership scenarios.

Additional combined regressions check:

- Required-hint displacement AND rejection, aged-locality reservation, bounded
  spill recovery, real preparation/publication and changed material after cold
  reopen.
- Sustained four-visible-samples/tick admission while a generation refresh must
  advance and dispatch; the eight-slot reserve and bounded queue sizes.
- The exact production retry-rotation method: near refresh completion alongside
  a huge sweep, stable captured cursor, progress for both, and failure isolation.
- Repeated/coalesced chunk loads for every discovery tier in both exact and
  refresh mode, retaining caller futures and whole-window refresh semantics.

Tests use explicit embedding metadata fixtures, not inferred vectors. No live
model/service, model download, game bootstrap, GPU or gameplay was exercised.

## Verification and limits

Canonical Java 21 / cached Gradle 8.13 command (no task exclusions):

```
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --rerun-tasks build
```

The combined build before the final shared-rotation extraction passed all 11
runtime test mains, packaging/descriptor/runner fixtures and `verifyProductionJar`:
`DiscoveryInvalidationTest` 132927 checks; `SourceAvailabilityRestartTest` 2013
checks; 14 actionable tasks executed. Retained tmux session `$11`, origin `%2819`,
pane `%2851`, run `f884f614544d476b94a203f311902d9f`, exit 0.

The final canonical build is retained in the same original session/origin, pane
`%2896`, run `a96388a248264137a1404e392d4d11f9`: **BUILD SUCCESSFUL**, exit 0,
all 14 actionable tasks executed. All **11 discovered runtime mains** passed,
including `DiscoveryInvalidationTest` **132929 checks** and
`SourceAvailabilityRestartTest` **2013 checks**. Packaging, descriptor and runner
self-tests plus remapped production-jar verification passed. `git diff --check`
and the independent runtime-source inventory (11 mains) also passed. Production
artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.

This is work/dispatch fairness, not a wall-clock guarantee: active source IO,
extraction, model readiness/inference and publication remain non-preemptive.
Continual overlapping edits/generation can restart observations, and conservative
unions or operation budgets can delay very large features. Queues are session
state; no new persistence schema was added. Expected malformed persistence
fixture logs, annotation warnings and deprecated API/Gradle warnings remain.
No Minecraft/IntelliJ launch, gameplay or real native-v2 inference was verified;
those remain integration/runtime acceptance work outside this assigned unit.
