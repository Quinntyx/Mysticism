# Corrected spirit-world V1

This replaces the obsolete shared projection frame / placed-block prototype. Each observer has an independent 768-dimensional position and evolving basis. Native source octrees describe contiguous 3D regions with exclusive ownership; source chunks are only IO units. Terrain is rendered and collided through the same per-observer affine geometry.

## Debug workflow

1. Provide an Ollama-compatible embedding backend at `http://127.0.0.1:11434/` with `nomic-embed-text-v2-moe:latest`. Installation/model download is the user's responsibility; neither was performed by this workflow. Java system properties `mysticism.embedding.endpoint`, `mysticism.embedding.model` and `mysticism.embedding.revision` configure the endpoint/pinned profile. `/myst embed status` reports readiness.
2. In the ordinary world, `/latent target here` (or `/spirit capture`) captures a shallow landmark+block destination. Wait for its asynchronous confirmation. `/latent target item minecraft:amethyst_shard` chooses a deep concept target. A capture is a snapshot, not a live tracking link.
3. `/spirit enter` prepares the current source view. Shallow source entities are visible/synced but cannot be interacted with. Ordinary jumps stay shallow.
4. Double-jump/flight toggle or `/spirit deep` starts deep flight. Moving left, right or forward gradually rotates the basis to pursue the saved target. No survival hidden-dimension rotation controls are provided.
5. `/spirit` and `/latent show` inspect mode/state. Reach a valid source region and acquire shallow support to walk with its stable source grid. A stale/blocked destination can leave you deep near the landmark; no guaranteed safe arrival or return is promised.
6. `/spirit leave` materializes at the current valid shallow source location, not automatically at the original entry point. Survival entry/exit items and research progression are deferred.

## Interactions

Deep player contact uses mutually reachable per-observer projections and terrain occlusion. Punch/right-click aligns only the recipient's basis over about one second; movement diverges it again. The target is not rewritten. There is no deep spirit combat. Native mobs are excluded; source ghosts are a separate presentation layer.

Drops are real vanilla ItemEntity instances, not disposable icon mocks. They travel toward their item's concept-space location and retain vanilla pickup/mod recovery hooks. Expect ordinary drops to be lost without recovery assistance. Observer-range cleanup and a maximum 6000-tick lifetime apply.

## Embeddings and landmark history

All semantic objects use fresh Nomic v2 native 768-dimensional normalized descriptors; old generated profile data is reset rather than archived/migrated. Ordinary worlds, inventory and vanilla player statistics are not disposable embedding caches. V2's official model card documents `search_document: ` for passages, so every indexed semantic descriptor uses that same supported prefix; clustering is performed on that consistent space rather than mixing retrieval-query vectors or inventing an undocumented V1 task prefix.

Importance gives drift inertia, expands contiguous ownership and influences representative selection within the semantic radius. Extremely close vectors overcome importance in an approximately 5%-radius inner band. Importance decays over multiple Minecraft years. Discovery reads generated terrain, including cold regions, without generating unexplored chunks.

## Validation and bounds

Only compilation and light smoke checks are intended. The numerical collider smoke checked a floor, an affine slope and obstructed/clear rays. A headless client launch was attempted with Xvfb/software GL, but offline asset acquisition failed before Minecraft started: that is NOT a passing GPU/gameplay test.

Source/model availability and finite geometry/material/page/session budgets still matter. Cold source captures approximate some context/lighting; animated block entities and fluid presentation are limited. Border smoothing is a bounded shared affine displacement plus deterministic palette dithering, not an arbitrary watertight nonlinear terrain reconstruction. Complete huge-cave streaming, multiplayer performance and Fast/Fancy/Fabulous visuals need real gameplay validation. No legacy test suite or model migration is evidence for these behaviors.
