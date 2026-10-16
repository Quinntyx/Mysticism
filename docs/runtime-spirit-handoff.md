# Scoped spirit exit/re-entry handoff

This branch changes the client handoff for validated spirit entry/exit only. It does
not implement the other retained runtime units or change source ownership,
independent per-player deep/shallow projection, terrain collision, or embeddings.

## Behavior

- After entry mesh preparation or successful shallow exit/source-body validation,
  the server sends `mysticism:spirit/transition_v1` immediately before vanilla
  dimension respawn/position packets. The payload contains player UUID,
  source/destination dimension IDs, exact destination feet and yaw/pitch.
- A recent, one-shot client hint is bound to the current connection, player and
  dimension pair. Only an alive player's matching entry/exit suppresses vanilla's
  two loading-screen installation sites. Ordinary `/tp`, portal travel, death,
  unmatched respawns and reconnects retain vanilla behavior. Clients that do not
  advertise the payload retain vanilla behavior as well.
- Vanilla still replaces the world/player, clears old world resources, waits for
  destination data and processes the normal position packet/teleport ACK. At
  respawn tail, the client seeds the same authoritative destination pose and resets
  position/rotation interpolation, velocity and fall distance. This avoids a
  default `(0,0,0)`/wrong-facing frame; it does not add movement or teleport ACKs.
- The most recent outgoing **world color**, captured after world postprocessing
  and before hand/HUD, bridges the delivery gap. Exit uses the current outgoing
  shallow scene, never a stored picture from an earlier entry/source position.
  The bridge survives vanilla reset's forced outgoing-world render. Entry releases
  when the current spirit cache and terrain frame exist; exit uses vanilla world
  loading readiness. The bridge is bounded to five seconds and rejects wrong
  players/connections/worlds, stale captures, reloads and disconnects. There is no
  fabricated source geometry or collision fallback; live destination rendering
  resumes on readiness, cancellation or timeout.

## Regressions and actual verification

Canonical command: `./gradlew --no-daemon --console=plain build`.
`check` discovers and executes main-based runtime suites; JUnit `test` is disabled
by this project's existing runner and must not be reported as the runtime suite.

New discovered suites:

- `SpiritTransitionGateTest`: 625 checks, including 100 exact fractional/negative
  entry/exit pose cycles, one-shot consumption, identity/dimension/death/age
  rejection, disconnect cancellation and malformed pose rejection.
- `SpiritTransitionBridgeTest`: 48 checks, including reset's intermediate forced
  render, destination readiness, exit snapshot provenance and bounded lifetime.
- `SpiritTransitionContractTest`: 76 checks against compiled production and mapped
  Minecraft bytecode, required packaged mixin registration, server validation/hint/
  teleport ordering, pose/interpolation call ordering and actual payload codec.
  These are offline contracts, **not** live mixin application or gameplay tests.

The canonical build also runs all four discovered existing embedding suites and
build/packaging runner tests, and validates the remapped production jar. Existing
embedding malformed-input rejection warnings/stack traces are expected test output.

Remaining verification: launch Minecraft and exercise fractional source poses,
repeated exit/re-entry in Overworld and Nether, delayed packet/terrain delivery,
window resizing, resource reload, death/ordinary dimension travel and reconnect.
Client gameplay, live mixin application, GL output and model inference were not
performed for this scope. The bounded color bridge can display a stationary world
image during delayed delivery; it is not a promise of uninterrupted moving terrain.
