# Approved rebuild: render implementation handoff

Authority: `docs/spirit-world-approved-design.md` (read completely). Implements dynamic
observer projection, NOT old frozen/save-stable absolute glyph anchors or real-block overlays.
Branch `feat-spirit-volumetrics`. No root initializer, protocol/nav/terrain core, translation,
build/config edits. No dependencies, agents, Gradle or new test batteries.

## Parent registrations / actual integration APIs

* Call **`io.github.mysticism.client.spiritworld.SpiritWorldClient.init(): void`** once.
  Idempotently registers actual `SpiritTerrainClient.init()` FIRST, catalogue glyphs,
  projected peers/ghosts/drops and sky, then shader snapshot LAST. **Use this BEFORE any
  separate ShaderManager.init(): replacing the old separate shader/glyph/sky calls is
  necessary for callback order**, not merely idempotent duplicate registration.
* Keep network owner's `SpiritNetworkingClient.init()` and existing `GuidebookClient.init()`.
  Guide init now registers the dedicated Fabric core shader; no separate root guide hook.
* Parent input mixins consume **`SpiritWorldClient.tryTouch(MinecraftClient,double): boolean`**.
  Deep only, max4, drawn/interpolated peer bounds, current canonical affine LOCAL collision
  ray, fails closed on missing mesh/degenerate intersecting surface, sends real
  `SpiritTouchPayload(UUID)`. Parent owns attack/use/native-scene suppression mixins.
  Renderer calls **EntityRenderDispatcher directly**, never WorldRenderer.renderEntity;
  no competing native-scene/input mixin. Render-only models never enter ClientWorld.
* Parent register these THREE client mixin full classes (adjust existing package-relative
  JSON entries; no second mixin config created here):
  `io.github.mysticism.client.spiritworld.mixin.SpiritLayerAccess`,
  `io.github.mysticism.client.spiritworld.mixin.SpiritLayerPhasesAccess`,
  `io.github.mysticism.client.spiritworld.mixin.SpiritTextureAccess`.
  Existing `SpiritBackgroundRendererMixin` remains REQUIRED to disable steady-state
  analytic fog, and is not modified here. Missing fog hook refuses double-fog chain.
* **Terrain owner action:** use
  `SpiritRenderLayers.texturedMain(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE): RenderLayer`
  for terrain's baked-model VBO layer. This is explicitly MAIN + translucent + ALL_MASK
  depth writes. Plain `RenderLayer.getEntityTranslucent(...)` can route to Fabulous
  ITEM_ENTITY_TARGET even after manual main.bind; it is NOT our MAIN guarantee.
* `ShaderManager.afterMeshDepth(): void`: terrain calls after its own draw/flush in
  BEFORE_ENTITIES. Diagnostic marker; final depth copy is AFTER_TRANSLUCENT, before
  Fabulous composite/clear, then postprocess at END, before hand/HUD. Glyphs draw
  AFTER_ENTITIES; semantic models AFTER_TRANSLUCENT (registered before snapshot).
* `ShaderManager.setMediumSamples(float[]): void`:4096 values0..1, centered at camera at
  publication. **Preferred overload `setMediumSamples(float[],Vec3d center): void`**:
  terrain currently rasterizes about player eye, so pass that exact eye center, not
  implicit camera center (third person differs). Grid step8, range[-64,+64), x-fastest
  `x+16*(y+16*z)`. CPU clone/validation; one reusable64² RGBA nearest atlas, updates only
  publication revision, no world/model scans. Terrain supplies actual bounded occupancy.

## Canonical cache / observer contract

Renderer reads network-owned `SpiritSceneClient.snapshot(): Optional<SpiritScenePayload>`
and `SpiritNetworkingClient.glyphs(): List<SpiritGlyphSelection.Glyph>` directly; no second
receiver or invented scene/mesh protocol. Reads actual CCA `SPIRIT_NAVIGATION`
(active/deep/sourceDimension/sourcePosition), `LATENT_POS`, `LATENT_BASIS` each frame/tick.
Rejects zero/default or nonorthonormal observer vectors before projecting; model space
validated with real `EmbeddingSpace.requireCurrent` (DIM/profile ownership stays core).

`ClientSpiritCache.syncObserver(MinecraftClient):void` mirrors actual lifecycle and canonical
selection, removes obsolete vector entries on updates, clears on world/player/handler change.
Compatibility `accept(...)`, `updateObserver(Vec384f,Basis384f)`,
`updateNavigation(boolean,boolean,String,Vec3d)`, `replaceGlyphs(List<Glyph>)` remain; they
are NOT new networking initialization and are not the authoritative v1 scene cache.
`scene()` delegates to the real scene cache. No old placement/frame data is used.

`SpiritGlyphFrame.project(Vec384f):Vec3d` uses **current** viewer(q,B), interpolated player
head, scale96 blocks/semantic unit. `SpiritGlyphSelection.position(...)` consumes the real
server cluster/member slots for semantic head-centered rings, current basis/position and
stable angular IDs. Positions ease toward current projection; no camera/world-frozen anchor.
Selected glyphs <=128, retiring+selected visuals<=256; fade~1/3s, smoothing12/s, frustum and
64-block gates. No catalogue scan, embedding inference or model IO on tick.

## Implemented scene rendering

* Peers: vanilla player models/skins from actual tab profile where available, semantic
  per-observer positions, squared normalized basis alignment scales .05..1 (aligned=1),
  depth-writing MAIN model, fog once through the world chain. Projected contact uses its
  currently drawn feet/height/scale, not a server carrier XYZ entity hit test.
* Shallow ghosts: registered actual source model, source tracker entries/pose/equipment/
  profile and body/head yaw; vanilla skin supplier for source PLAYER. No fake marker.
  Feet map through closest compatible canonical source cell affine transform within8
  source blocks (bounded2048 scalar probes/ghost); air/flying fallback uses source-local
  translation. Model upright pose is NOT full affine deformation of the entire animal.
  No ticking simulated AI, entity insertion, combat, inventory or stat mutation.
* Drops: real vanilla ItemEntity descriptions projected from semantic q, full canonical
  bounded ItemStack type/count/components/model; never replace server entity with particle
  or delete/re-create it. Atlas and builtin models preserve texture/vertex interpretation;
  glint uses its original compatible format. Unsupported model failures log + visible
  item status and skip until session/reload, leaving recoverable native entity intact.
* Model texture layers explicit MAIN, alpha, depth writes; see-through vanilla text becomes
  depth-tested normal text so player labels do not intentionally appear through terrain.
  Nonentity/untextured special layers retain their own format/behavior. Modded special
  renderer AUX/see-through phases outside recognized formats are NOT fully guaranteed.
* Render models/selection/cache clear on world/player/entry changes, disconnect, reload,
  stop. Semantic model pool<=128 including retirees; failed type set<=64, failed item
  set<=128; texture-layer cache<=128 (overflow uses vanilla MAIN cutout, losing smooth
  alpha). Allocators and retained framebuffers/textures released; snapshots are bounded.

## Volumetric / painterly / fades

Real nonlinear scene depth reconstruction + world-coordinate raymarch. Integrates local
participating extinction/in-scattering, with real terrain-published8-block occupancy atlas
added to continuous spatial density. Not a terminal exp(distance) fog. Near bubble5,
opaque64, loading policy minimum80 remains server/terrain responsibility. Extinction floor
`-log(.001)/(64-5)` guarantees theoretical T(64)<=.001 after the clear bubble; 24/40/64 rays.
This is unshadowed procedural single-scattering, not physically complete lighting.

The existing optimized bounded9/25/49 coherent Kuwahara runs after fog, then independent
2.5-second monotonic saturation. New final transition pass mixes the saved original world
color with the processed scene using independent1.5-second smoothstep `effectStrength()`.
Fade-in waits for an accepted canonical mesh (empty deep frame is valid), not just CCA active.
Source native fog is retained during the partial scene transition; steady spirit disables
analytic fog and fog is integrated only once. Hardware failure yields HUD+logs and usable
linear fallback (40..64), not an invisible shader catch.

`SpiritEntryScene` holds one actual normal-world color framebuffer captured at END before
hand/HUD. Same connection/player UUID/source dimension and age<=5s required for use. First
entry frame/transition blends from that real source scene rather than showing abruptly
empty carrier terrain. During initial CCA/mesh delivery it holds that real image up to5s
and shows waiting status; it does not construct unauthenticated geometry. Snapshot is a
short static image, not a replayable source world:
camera movement during fade can reveal mismatch; expiration during a very delayed fade
can switch its original-color source. Source sky/cloud/weather continuity after
fade is not implemented by this transport and existing spirit sky is still synthetic.
First join directly into spirit, delayed>5s or reload during transition lacks the source
image and falls back to live mesh/color. One source-color copy per normal-world frame costs
bandwidth; actual GPU timings not measured. Resize adjusts capture; disconnect/reload/stop
close it. World exit retains outgoing postprocess strength and fades over returned world.

## Guidebook GPU shader

`GuidebookShimmer.java` now draws ONE POSITION quad via custom core GPU shader, not CPU
fill loops. Resources:
`src/client/resources/assets/mysticism/shaders/core/guidebook_shimmer.{json,vsh,fsh}`.
Binding `mysticism:guidebook_shimmer`; uniforms `ModelViewMat`, `ProjMat`, `GuiSize`(vec2),
`ShimmerTime`(float), `ReducedMotion`(float). Purple blocky hash/pixel shimmer is continuous
periodic GPU math; reduced motion fixes phase0. No textures/proprietary assets/dependencies.
Fabric core registration follows resource reload, vanilla owns ShaderProgram close;
reload invalidates handle and retries; stop clears it. Explicit missing/uniform/GL errors
log and show unobtrusive status with static gradient fallback. Restores GUI render state.
Tree pages/icons/panning/pagination/item renderer/layout and keyboard paths are unchanged.

Parent merge the separate prose replacements below into en_us; no translations/shared
resource overwritten in leaf. Keep /spirit enter/leave/deep/capture v1 debug syntax only;
remove frozen-frame and prepared-safe-landing claims, not replace them with asserted GPU proof.

## Checks and still-required integration/runtime checks

PASS: `bash docs/smoke-rebuild-render.sh` in visible tmux **%466**, exit0: installed
`glslangValidator` syntax on guide vertex/fragment, changed fog and transition fragments.
PASS: `git diff --check`; inspected actual cached Yarn API and Fabric/Satin event sources/
transitive access widener (RenderLayer.of is widened by existing Fabric, no build change).
NOT RUN: Java/Gradle compile or combined smoke (parent serializes after merges); no tests
claimed from prior baseline output in pane. No DISPLAY/WAYLAND/live shader link/GPU tests.

Verified source order in consolidated init: terrain BEFORE_ENTITIES, glyph AFTER_ENTITIES,
semantic models AFTER_TRANSLUCENT registered BEFORE shader AFTER_TRANSLUCENT snapshot,
shader END. Parent must replace old earlier ShaderManager.init calls; they cannot be undone
by later idempotent registration. Terrain must use the explicit MAIN layer noted above.

Required manual: Fast/Fancy/Fabulous opaque walls+glyphs+avatars+drops+labels, custom model
cases, depth around sky/translucent/weather edges, first/third person, resize/fullscreen,
F3+T/bad shader pack/driver failures/recovery, slow/direct spirit entry/no image, camera motion
through entry fade, exit, saturation timing, changed distant selections, respawn/reconnect
before CCA sync, semantic touch occlusion and source ghosts/tracker/model appearance. No
CPU GLSL/math or successful compile alone proves these visual/physics/GPU behaviors.

## Appearance review correction

Complete appearance snapshots now clear every usable equipment slot and reset ALL tracker
IDs to copied declared `DataTracker.Entry.initialValue` defaults before applying new values.
Constructor-mutated defaults (e.g. a bat constructor marking it roosting) are not used.
Defaults are captured once per private model and copied on reset. Same immutable snapshot
is applied once, not reapplied every draw. Only private render models are modified.
**Parent register two additional client mixins:**
`io.github.mysticism.client.spiritworld.mixin.SpiritTrackerEntriesAccess` and
`io.github.mysticism.client.spiritworld.mixin.SpiritTrackerInitialValueAccess`.
They expose the existing tracker entries/declared defaults; no config edited here.
Peers now consume `peer.appearance()` for tracker/equipment/pose/body/head yaw and authenticated
profile (tab profile when present, signed supplied profile otherwise; no fabricated fallback).
Profile changes replace only the private model and its defaults, preserving projected position,
interpolation/alpha and basis-dependent scale. Source players receive the same profile handling.
No public rendering API changes. No tests/build/live checks run for this precise follow-up;
parent retains its merged event-order adjustment and owns compile/runtime validation.

## Fabric resource-lifecycle compile correction

Replaced nonexistent Satin `InvalidateRenderStateCallback` with actual Fabric
`SimpleSynchronousResourceReloadListener` through public
`SpiritRenderReload.register(Identifier,Runnable)`. Client resource apply runs cleanup
synchronously on the render thread (asserted), not delayed to an unrelated later tick.
Item/entity allocators reset; postprocess framebuffers/occupancy GPU texture release,
source image clears and failure resets. CPU occupancy remains available for GPU rebuild.
No parent initializer/config hook needed; existing idempotent init methods register it.

Terrain owner/parent must replace its same obsolete callback with
`SpiritRenderReload.register(Identifier.of("mysticism","spirit_terrain"),
()->{closeBuffers();dirty=true;});` (inside SpiritTerrainClient).
Retain its accepted frame/collision, rebuild VBOs on next draw; do NOT call `clear()` for
resource invalidation. Terrain source not edited here. Parent's SCALE cast left unstaged.
No tests/builds run for this requested callback-only correction.

## Parent lang replacements (requested, not applied to shared resources)

* `guidebook.mysticism.crossing.enter.body`: "V1 debug entry: /spirit enter starts shallow
  spirit navigation from the current source scene. Nearby source geometry stays locally
  recognizable while the distant semantic field changes with your position and basis.
  /spirit deep deliberately detaches from a shallow source. /spirit capture captures the
  current region for a debug experiment. Watch server responses; no safe-landing promise."
* `guidebook.mysticism.crossing.return`: "Use /spirit leave to ask the server to exit at
  the current compatible shallow source surface. It is not a teleport back to a frozen
  entry anchor. Concept-only deep space has no material source exit; use the available
  debug navigation to reach a real source. Keep backups and test this experimental build."
* `guidebook.mysticism.attunement.observe.body`: "Position, target attunement and personal
  activity are distinct. Viewer-relative terrain, glyphs and peer avatars change with
  the current semantic position and orthonormal basis; there is no shared frozen frame.
  Aligned peers appear normal-sized; basis mismatch shrinks them. Deep visible projected
  touch, not ordinary carrier-space combat, requests server-authoritative interaction."
* `guidebook.mysticism.attunement.practice.body`: "Use /latent show for debug state and
  /spirit for mode/source status. /spirit enter is shallow; /spirit deep is concept-only.
  Support and activity steer semantic navigation. These are diagnostic tools, not a
  survival progression UI or a guarantee that model initialization succeeded."
* `guidebook.mysticism.echoes.body`: "Source-semantic landmarks feed a per-observer mesh
  and matching collision field, not chunks of physical overlay blocks. Selection/fades
  and the near source bubble belong to the terrain service. Fog at64 blocks is a visual
  horizon, not evidence of collision or safe navigation."
* `guidebook.mysticism.growing.body`: "Source blocks, player stats and inventory remain
  normal game state. Activity/persona and landmark observations influence the shared
  semantic space. The guide only documents it; local study marks do not unlock research."
* `guidebook.mysticism.crossing.study`: preserve all existing study/prerequisite prose,
  change final sentence to "Reduced motion fixes the GPU shimmer phase for this client
  session; unsupported rendering uses a static purple fallback."
* Preserve vanilla compass recipe/click navigation; no portal key or frozen-return prose.
