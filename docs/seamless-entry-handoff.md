# Seamless `/spirit enter` handoff

## Scope and implementation

This branch fixes the entry loading GUI only. It does not change server teleportation,
abilities, navigation CCA, embeddings, per-observer projection, terrain ownership,
mesh generation or collision.

Minecraft 1.21.1 installs `DownloadingTerrainScreen` twice during a dimension transfer:

1. `MinecraftClient.joinWorld` calls `reset(screen)` **before** replacing `client.world`.
   `SpiritEntryWorldMixin` passes `null` only for a live source-to-`mysticism:spirit`
   transfer with a coherent source player/world. The original reset still runs exactly
   once, including sound/camera reset and forced rendering of the source world.
2. `ClientPlayNetworkHandler.startWorldLoading` constructs/installs `WorldLoadingState`
   and then calls `client.setScreen(screen)`. `SpiritEntryLoadingMixin` suppresses only
   that GUI call. At this point `client.world` is new but `client.player` is still the
   old source player; the incoming player is installed later in `onPlayerRespawn`.
   Initial login has already installed the incoming player and deliberately falls back.

Both redirects require exactly one target invocation. They do not cancel packet
handlers, mark chunks ready, bypass loading state, change player/world fields, or
fabricate teleport confirmations. Source dimension is read directly from the old
world/player, not navigation CCA that may not yet be synchronized.

Login, death respawn, spirit-to-spirit respawn, exit, other dimension transfers and
unrelated screens retain vanilla behavior. The existing `SpiritEntryScene` source
snapshot and mesh-gated `ShaderManager` fade become visible instead of the loading
GUI. Their existing five-second snapshot lifetime is unchanged.

## Automated verification

Canonical command (existing installed distribution; no exclusions/classpath overrides):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --continue build
```

Executed using the original tmux session/pane through `tmux-jobs`. Final result:
**BUILD SUCCESSFUL**, exit 0. Includes:

- `runtimeSelfTest`: discovers `SpiritEntryTransitionTest` plus all four current
  embedding suites (five main-based classes total). Entry regression has **304 checks**.
- Entry policy matrix, client-only registration, redirect descriptors/cardinality and
  actual Yarn bytecode ordering. Checks loading-state construction, independent tick
  and initial-chunks signal, and retained vanilla teleport-confirmation construction.
- Compiled redirect bodies executed against remapped tiny game stand-ins, covering
  live entry and fallback guards. Only the shadow reset is replaced with a recorder;
  tests verify reset runs once and hooks leave world/player identity untouched.
- `buildSelfTest`: packaging, descriptor, metadata and runner-discovery/failure fixtures.
- `remapJar` and `verifyProductionJar`: production artifact
  `build/libs/mysticism-1.0-SNAPSHOT.jar` verified.

Historical shared-frame/block prototype tests remain excluded by the existing runner;
this branch does not re-enable discarded suites. Expected malformed-persistence
fixtures log warning stack traces but pass. Existing CCA source-remapping/API Guardian,
Mixin target-style and Gradle deprecation warnings are not gameplay evidence.

## Remaining live validation (not performed)

No Minecraft client/server, GPU transition or model inference was launched. The
remapped-hook harness is not a real Mixin application test. Validate in a client:

- `/spirit enter` from supported source ground, Nether and a modded source dimension:
  no terrain/dimension loading screen, source image held until custom mesh fade, normal
  movement/teleport acknowledgement after entry.
- Login directly into spirit, death respawn, same-world respawn, `/spirit leave`, normal
  Nether/End travel and disconnect/reconnect: vanilla loading behavior remains intact.
- Slow mesh/CCA delivery, shader failure, framebuffer resize and resource reload: source
  snapshot/fade behavior remains subject to the existing renderer safeguards, including
  snapshot expiry after five seconds.
