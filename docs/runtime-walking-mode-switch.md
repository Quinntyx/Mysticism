# Retained runtime unit: supported walking across mode switches

Scope: `feat-runtime-walking-walking-mode-switch`, based on retained HEAD `bfbce90`.
The previous session's commits and unfinished navigation/terrain/test work were
retained and repaired. This is one navigation unit, not a claim to fix all eleven
runtime reports. No embedding implementation, dimension, dependencies, mesh
selection, renderer, source ownership/growth, or shared world frame was replaced.

## Behavior

- Shallow entry holds walking on **real accepted mesh ground** while its exact
  octree ownership snapshot resolves. The pending-ground check uses accepted-frame
  producer provenance and the exact floor sample. Another representative's ground
  cannot hold this binding. A current empty snapshot is provisional while initial
  source discovery is outstanding; after discovery is applied, definitively
  unowned ground cannot hold this binding.
- `walkingPosition` retains a pending source identity for navigation mode-keeping
  only. Public `sourcePosition` does not expose this retained identity as capture,
  exit, or entity-binding authority. Exit explicitly refuses pending ownership.
  Ordinary ascending jumps retain the existing 14-tick grace; visible edges still
  enter deep flight without a safety floor or invented source owner.
- Deep flight-off acknowledges authoritative flying=true even when vanilla already
  holds that server state. This prevents the client retaining local gravity/
  walking physics while exact support acquisition is pending. Expiry acknowledges
  usable deep flight again and clears the transient intent/probe/prediction mode.
- A changing real support window, landmark, dimension or source grid re-bases the
  **same** request from the live basis. Repeated flight-off packets cannot restart
  alignment or re-arm its budget. Actual source-grid convergence re-arms the
  200-tick stall budget; repeated contact oscillations and rebinding do not.
  Request, projection and mode state remain independent per player.
- Support movement is exactly bounded actual translation, shared with client
  prediction. The unfinished retained implementation attracted q toward a support
  coordinate and tested against a fixed source world after rotated flight. That
  assumption was incorrect: support feet are inverse-mapped from the accepted
  **live projected cell**. Orthogonal semantic residual is legitimate; acquisition
  checks projected millimeter error, not full vector equality. No support-target
  homing, q replacement, normalization or captured-attunement mutation is added.
- Existing `canAlign`, current-contact/probe ownership and body/floor checks, and
  whole-frame render/collision transition validation remain authoritative. A
  discontinuous basis or unsafe/unready source scene stays deep and can expire;
  the patch does not force an unsafe shallow transition.

## Checks actually performed

Canonical installed Gradle 8.13 / Java 21 command, without task exclusions or
classpath overrides:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

The first run reproduced the retained `WalkingModePolicyTest` failure:
`movement-driven support convergence must make walking reachable after rotated deep flight`.
After correcting the unsupported attraction/fixed-coordinate assumption, the
canonical build passed, including common/client compilation, all five discovered
runtime main suites, packaging/runner fixtures, remapping and `verifyProductionJar`.
The repaired walking suite reported **2576 checks**, covering pending-ground mode
policy, jump/edge behavior, duplicate requests, rebind/oscillation stall expiry,
retry and per-player independence, bounded movement/prediction, actual accepted
cell inverse and SAT support/transition checks during rotated projection, and
real navigation component mode/epoch/captured-target retention.

Four existing embedding suites also passed (28, 184, 90 and 135 checks respectively).
Their providers are test doubles; this is not real model-inference verification.
Expected corruption-fixture warnings occurred. Existing client annotation and
Gradle deprecation warnings are nonfatal.

## Review repair: empty ownership snapshot before initial discovery

The follow-up starts from retained commit `e877a1c9006df539ac00afcd93919062449a77c9`
with a clean worktree; its commits are preserved. The earlier pending check only
tracked octree snapshot readiness. An ownership request could return a current
empty snapshot before `ensureSourceLocation` completed, prematurely enabling deep
flight on accepted local ground. Later anchoring correctly preserves existing
flight, so it could not undo that premature switch.

Each local window now tracks initial discovery separately, including the interval
between worker completion and server-thread result application. `groundPending`
still requires actual accepted mesh ground, matching local producer provenance,
and matching cell identity. It then holds walking while either discovery or exact
ownership revalidation is outstanding. Discovery state does not participate in
`support`, `sourcePosition`, capture, exit, or exact ownership authority. Empty,
failed, cancelled, null, stale-session and exceptional application results release
that discovery permission; a still-invalid ownership snapshot retains its existing
mode-only permission independently.

The discovered `InitialSourceDiscoveryTest` runs **269 checks** using the production
lifecycle helper and shallow mode policy with deterministic worker/server ordering.
It covers an empty snapshot arriving first, worker completion queued for server
application, successful invalidation/revalidation handoff, terminal failures,
submission/application exceptions, duplicate requests, and independent windows.
A deliberate mutation restoring the old snapshot-only decision made
`runtimeSelfTest` fail at `current empty snapshot must not enable deep flight during
initial discovery` (exit 1). The mutation was removed before the final build.

The canonical Gradle command above then passed (exit 0): all **six discovered
runtime suites**, existing packaging/runner fixtures, common/client compilation,
remapping and `verifyProductionJar`. The inherited walking suite still passed
2576 checks; embedding suites still passed 28/184/90/135 checks with test doubles.
Build output remains in original-session tmux pane `%2383`, run
`0f93fe91e7c240ab8652c40d8dbd8fca`; the expected mutation failure remains in `%2381`,
run `08a8b4138fd34ceaa48ec0367783f610` (origin `%2359`, session `$11`).

## Unverified and limits

No Minecraft client/server launch, mixin boot, live source discovery, packet
latency/gameplay, GPU rendering or live model inference was performed. The
regression exercises the real geometry primitives and component state without
bootstrapping a game. Safe acquisition still depends on asynchronous exact source
proof and accepted terrain publication. Slow proof or unsafe rotation may expire;
requesting walking again is possible and deep flight remains available. This
unit does not resolve the other reported terrain, rendering, entry-screen or
in-process embedding tasks.
