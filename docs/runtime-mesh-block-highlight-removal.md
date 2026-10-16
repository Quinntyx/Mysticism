# Spirit mesh picking / outline handoff

Scope: `feat-runtime-mesh-block-highlight-removal`, based on retained commit
`bfbce90`. The retained session ended during investigation; the starting checkout
was clean. No previous commits, world data, or other branches were discarded.

## Behavior

- `SpiritPickingMixin` bypasses vanilla `GameRenderer.updateCrosshairTarget` only
  in `mysticism:spirit`. Every update clears `targetedEntity` and writes a non-null
  MISS hit along the view ray. Suppression does not wait for navigation, scene,
  or terrain packets, so entry gaps cannot pick invisible prototype blocks.
  Shallow ghosts and invisible carrier entities are not vanilla targets either.
- `SpiritNativeSceneMixin` additionally cancels `drawBlockOutline` in that
  dimension, defending against stale or third-party block hits. Generic shape
  outlines are not suppressed. Vanilla picking/outline behavior outside spirit
  is unchanged.
- The normal crosshair stays visible. Four cyan corners indicate a locally
  reachable, mesh-unoccluded projected deep peer. This is touch feedback, not a
  combat target or a guarantee of server acceptance. No render-only entity is
  put in `crosshairTarget`/`targetedEntity`; looking sends no packet. Existing
  punch/right-click hand swings and authenticated server touch validation stay
  intact. Held-item air use and native dropped-item pickup/magnet behavior are
  not replaced or disabled.
- HUD feedback and clicks share `SpiritSemanticEntityRenderer.touchTarget` and
  `SpiritTouchPicking.pick`: nearest present deep peer, sufficient visibility,
  normal four-block maximum reach, and inverse-affine LOCAL mesh shape
  occlusion. Stale world/player visuals and failed model types are excluded.
  Local shape protrusions beyond the unit baked model are included in broad
  phase; conservative world AABBs do not become solid cubes.
- Projection/basis ownership, source octrees, terrain render/collision geometry,
  packet formats, server validation, embedding profiles and inference providers
  are unchanged. This branch is only the picking/highlight part of problem (8),
  not a claim to fix the other ten runtime problems.

## Regression discovery and validation

Use the **current** `scripts/selftest.gradle` contract, not the historical test
source locations described in older build documentation. New main-based checks
are in `src/runtimeTest/java`, with `*SelfTest` names and public static `main`.
`check`/`build` run `runtimeSelfTest`, discovering these two plus the four retained
embedding tests. No JUnit or dependency changes were added.

- `SpiritTouchPickingSelfTest`: 25 production-geometry checks (nearest peer,
  four-block cap, shorter/invalid rays, inside-peer origin, exact mesh walls,
  gaps, transparent/non-solid cells, skew vs conservative AABB, mirrored cells,
  protruding local collision, degenerate fail-closed behavior, and actual MISS
  type/endpoint).
- `SpiritPickingContractSelfTest`: packaged client mixin registration, required
  injection phases/cancellation, actual mapped Minecraft target descriptors,
  both stale hit fields being replaced, shared read-only HUD/click query, and
  no fabricated vanilla entity-hit feedback. These are offline bytecode/resource
  contracts, **not** a live Mixin transformation or game launch.
- Canonical `./gradlew --no-daemon --console=plain build` passes with six discovered
  runtime main classes, build/packaging fixtures, and `verifyProductionJar` on
  `build/libs/mysticism-1.0-SNAPSHOT.jar`. Expected embedding persistence rejection
  fixtures log warnings/exceptions but pass their assertions. Existing compiler
  API Guardian/accessor warnings and Gradle deprecations remain nonfatal.

No Minecraft client/server gameplay, GPU/UI-scale feedback, actual Mixin launch,
multiplayer mutual touch, or model inference was exercised. Integration still
needs a client check of entry/exit, shallow/deep views, cyan feedback on unobstructed
peers vs skewed terrain occlusion, held-item use, and native pickup/magnets. Server
mutual reach/alignment remains authoritative; the hint reports local candidacy.
