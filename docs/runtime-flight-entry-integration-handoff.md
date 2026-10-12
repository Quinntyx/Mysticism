# Retained flight / entry integration

Scope: integrate `78ff8b28987990000113c90c160d2fdfe7735bc4` (flight reconciliation and
observer mesh isolation) with `8689c12ed77cfc5c59a4340b85a8ad9e173f397a` (consistent
travel and seamless entry), on `feat-runtime-sol-merge-0020`, for later development
integration. No `dev`/`main` update. Both histories and their existing implementation
are retained; this is not a replacement of the eleven-problem project.

## Integration and additional repair

- The common mixin conflict must retain **all three** hooks:
  `SpiritFlightPredictionMixin`, `SpiritTravelMixin`, `EntityFlagAccess`. Keeping only
  one conflict arm would silently remove either packet reconciliation or the actual
  flight model / vanilla fall-flying flag reset.
- Packet admission retains bounded **per-player** published-frame history and resolved
  claim replay. The creative exemption only bypasses the noisy moved-wrongly gate;
  own-mesh wall crossings still fail admission. Vanilla invalid-coordinate, pending
  teleport and speed guards remain. Projection/terrain/collision APIs are unchanged.
- A regression with actual live travel inputs reproduced an overlooked 2% speed
  mismatch: `LivingEntity.tickMovement` multiplies both horizontal axes by `0.98f`,
  whereas the vertical key provider returns `-1/0/1`. The inherited 3D normalization
  accepted pure horizontal input at 0.98 but diagonals and vertical input at 1.
  `SpiritMovement.flightDirection` now undoes that **input** scale before normalization.
  Analog/reduced input stays proportional; there is no speed clamp, momentum reset or
  velocity rescaling. Actual travel uses this helper. Shallow walking remains vanilla.
- Entry retains both narrowly scoped client redirects: `joinWorld` still runs `reset`
  but without the loading GUI for live source-to-spirit entry; `startWorldLoading`
  still installs the vanilla loading state but suppresses its second GUI for that
  same entry. Login/death/ordinary transfer/exit keep the vanilla screen. World/player
  replacement, chunks, pending teleport and readiness handling are not canceled.

## New integration coverage

`SpiritFlightIntegrationTest` combines actual horizontal input scaling, all 26 digital
3D directions, normal/sprint steady-state convergence and proportional partial input
with a 480-tick accelerate/collide/admit/damp sequence. Held flight, repeated contact,
turns, reversals, sprint changes, ascent/descent and idle damping run against rotated
custom collision geometry and recent own-frame admission. Separate deep/shallow
players sharing the same source-cell identity have different geometry; each rejects
its own wall crossing without importing the other player's collision/history.
These frames are deterministic fixtures, not live generation or rendered footage.

`SpiritIntegratedMixinApplicationTest` runs isolated JVMs using the existing lightweight
Mixin service. It reads production registrations (exactly once, required), applies
retained hooks to actual mapped Minecraft `Entity`, `PlayerEntity`,
`ServerPlayNetworkHandler`, `MinecraftClient` and `ClientPlayNetworkHandler` bytecode,
checks the call substitutions and cancellation/fallback paths, and verifies injected
method stack flow with ASM. It also checks both actual vanilla `0.98f` input constants
and the production travel call to `flightDirection`. Unrelated Fabric/CCA/renderer
hooks are intentionally not applied by this isolated host; this is not a Fabric launch.
The previous actual flight-redirect application and missing-receiver negative control
remain independently discovered and run.

## Checks and limits

Canonical command (existing cached Gradle 8.13 / Java 21, no installations):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --continue build
```

The canonical build discovers 13 runtime main-based suites, including the retained
projection isolation, flight replay, movement, entry and four embedding suites.
`buildSelfTest` runs packaging, decoded metadata, descriptor and runner discovery /
ordering / assertion / failure / empty-suite fixtures; `verifyProductionJar` checks
the remapped distributable. The disabled framework `test` task is not counted as
coverage. Expected malformed-persistence warnings are negative fixtures, not inference.

Final canonical result: **BUILD SUCCESSFUL**, exit 0, all 13 discovered runtime suites
passed, including **2514 combined flight checks**, **1274 movement checks**, **304 entry
checks**, **43 projection-isolation checks**, **427 claim-replay checks**, actual Mixin
application and the missing-receiver rejection. Packaging/discovery self-tests and
production-jar verification passed. Retained original tmux session `$11`, origin
`%2127`, build pane `%2133`, run `1e6488bd68054eac98790bde45bffcc0`.
The live-input regression was first observed failing on single-axis horizontal input
with the inherited normalization before the input-scale repair. No test exclusion was
added to obtain the green result.

No live game, GPU transition, multiplayer/latency session or model inference was run
in this integration. The source-view snapshot retains its existing five-second expiry;
visual continuity under long stalls remains a live-test risk. Embedding contracts,
source-coordinate ownership/growth and mesh projection math were not changed. No v1
migration, vector truncation, dummy production vectors or external-service workaround
was introduced. Other reported runtime problems and in-process model restoration
remain outside this assigned scope; test success does not claim their resolution.
