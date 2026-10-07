# Shader/rendering handoff — authoritative glyph follow-up

Original renderer commits: `fc30ce4`, `885a8de`; reviewer base: `ac3d0a4`.
Original shader implementation/source ordering/manual smoke details remain in
`docs/spirit-volumetrics-integration.md`. This follow-up supersedes its local glyph
placement limitation, **only after parent wires the APIs below**. GPU limitations remain.

## Exact parent integration

1. Common initializer: after existing `SpiritNetworking.init()`, call
   **`io.github.mysticism.net.SpiritProjectionService.init()`** once. Registers disconnect,
   dimension-change, respawn and server-stop invalidation. Existing client
   `SpiritNetworkingClient.init()` now registers frame receiver and lifecycle guards.
   Existing `SpiritNetworking.init()` additionally registers the new frame payload.
2. **`SpiritVisibilityService`**: before computing/sending a spirit player's delta, obtain
   the **real frozen terrain `ProjectionFrame`**. Inspected immutable terrain commit
   **`46264f824acdacca0f59f41953951595d2e9f2ef`**: `TerrainState.frame()` is public;
   spirit-world `PersistentStateManager.get(TerrainState.TYPE, TerrainState.KEY)` gives
   its real saved state. Do not `getOrCreate` or construct a second terrain frame.
   Missing state/frame: skip publication and retry after terrain initialization.
   Terrain service has no public frame accessor; parent may instead bridge a public
   `Optional<ProjectionFrame> projectionFrame(MinecraftServer)` from its real controller.
   This is a **requested parent bridge, not an implemented/placeholder API**.
3. Call **`boolean bootstrap = SpiritProjectionService.activate(player, terrainFrame)`**.
   On true: discard that player's old visibility snapshot and send the **full currently
   selected set**, even if its IDs are unchanged. On false: normal delta calculation.
   Handle unsupported-client/state-error diagnostics without advancing snapshots or
   repeated tick log spam. New clients intentionally render nothing before bootstrap.
4. Replace visibility's direct `ServerPlayNetworking.send(...new SpiritDeltaPayload(...))`
   (original file line 55) / legacy batch sends with
   **`SpiritProjectionService.send(player, added, removed)`**. Advance the visibility
   snapshot **only after this returns**. Existing `Added.of(id, vector)`, `Added(id,bits)`
   and `SpiritDeltaPayload(add,remove)` remain source compatible, but legacy/direct
   unauthenticated placement is rejected client-side, not claimed save-stable.
5. For glyphs intended to match landmark collision, first publish **immutable base source
   embeddings**, not transient activity/ring-motion substitutes. Terrain freezes placement
   from landmark base embeddings. Same base vector/shared frame gives the same anchor.
   No terrain geometry, collision, dynamic projection re-keying or teleport was changed.

## Actual contracts and bounds

Per-player overworld **real PersistentState** key:
`mysticism.spirit_projection.v1.<uuid>`. Schema 1 saves full landmark profile, current
vector fingerprint, coordinate-semantics version, epoch/seed, absolute realm origin,
orthonormal axes, scale and up to **4096 first-ID positions**. Complete frame must match
terrain on subsequent activation. Changed frame/profile fails closed: explicit migration
required. Existing unreadable save file is not silently replaced. No position eviction
or reprojection on removal/re-entry/save reload; capacity exhaustion raises a diagnostic.
Only new IDs do bounded projection; no model IO/inference/world/catalog scan. Vanilla
owns disk saves. Per-player catalog is bounded; total storage grows with distinct players.

Terrain's actual frame: epoch 1, world seed, absolute `(0,128,0)`, first observer's
orthonormalized axes, **96 blocks/semantic unit**. Saved glyph frame copies it, not old
local player/camera placement at scale 30. Fog/loading constants stay **64 / >=80 blocks**.

Channels: `spirit/frame_v1`, `spirit/visible_delta_v3`. Bootstrap includes full pinned
frame, random per-play-connection nonce, player UUID, `mysticism:spirit` identity,
monotonic entry/respawn generation and saved frame epoch. Deltas repeat session identity,
strict sequence and explicit absolute positions. Authentication means the active trusted
Minecraft play connection, **not an added cryptographic signature**. Ordered reliable
transport is required; a sequence gap clears cache and requires fresh bootstrap.

Queued work compares actual handler/world/player identities. Bootstrap generation
watermark survives dimension/respawn clears; only connection replacement resets it.
No initial CCA or predictor state is read for placement. Removals release vector and
position entries; dimension/respawn/connection transitions clear active cache. Active
client cache and deltas are **<=128 glyphs**, encoded packets bounded below 1 MiB.

Scope: owned net/client-net/cache/frame, minimum renderer consumption, matching tests/docs
only. No initializer/visibility/terrain/shader/config/build/profile/dependency edits.
Original feature worktree retained; no dev/main edits or new dependencies; no agents.

## Actual validation

* Scoped Java21 `javac -proc:none --release 21`: all owned net files, client receiver,
  cache/frame and renderer consumption compiled against real existing cached mapped
  Yarn/Fabric dependencies. Outputs: `/tmp/mysticism-glyph-scoped.krfjsBKi`.
* `MYSTICISM_MINECRAFT_CLASSPATH="/tmp/mysticism-volumetrics-classes:<cached jar CP>" bash
  src/test/java/io/github/mysticism/client/spiritworld/run-render-tests.sh` passed:
  **99 static contracts**, **1870 CPU render-math checks**, **34 production projection
  checks**, **3 GLSL fragment validations**. Output retained at
  `/tmp/mysticism-render-tests.iOGLmFCh`.
* Projection checks execute **actual compressed PersistentStateManager disk save/reload**,
  saved ID retention after changed input, epoch/profile rejection, 4096 catalog cap,
  frame/delta wire roundtrip and exact byte budget, legacy/pre-bootstrap rejection,
  removal cleanup, re-entry/respawn/connection guards, replay rejection and aggregate
  128-glyph cache cap. No live client/server or model was booted.
* Recompiled matching `EmbeddingPersistenceTest` against owned codec: **93 assertions
  passed**. Old exact transport reproduction initially failed after intentional format
  change; updated v3 header/placement tags reproduce **2,107,472 bytes**, correctly
  rejected by frame encoder. Expected corrupt-save warnings/errors remain.
* Scoped script initially pulled CCA injected APIs through an unused import; removed
  that import and reran successfully. New test initially used WrapperLookup rather than
  DynamicRegistryManager for RegistryByteBuf; corrected and passed.
* No parent-owned Gradle build run; no dependency installs, output redirection or long
  build. Cached project classes/artifacts read-only. `git diff --check` passed.

## Remaining limitations / independent review

Parent initializer/visibility/terrain-frame bridge is **not wired in this leaf**. Without
it glyphs fail closed. Full merged Gradle and live lifecycle/network/GPU/Fabulous smoke
not run here. Original translucent-layer depth/driver limitations remain unchanged.
No terrain/loading alignment claim for item catalog IDs or transient non-base landmark
vectors. No automatic migration of changed projection/profile semantics, no custom crash
transaction beyond vanilla PersistentState save behavior. Saturation/fog/shader unchanged.
Manual smoke: save/reload exact ID positions; camera/FOV stability; disconnect/reconnect;
dimension exit/re-entry; death/respawn before CCA sync; delayed old frame/delta; wall
occlusion; cache growth while cycling visibility; catalog-cap diagnostic.
