# Retained mesh-ground-support review repair

Scope: `feat-runtime-mesh-mesh-ground-support`, retaining parent
`e15e529ca42158753c2b7fb3f716f5934afb43c2` and its affine SAT support tests.
No projection, source ownership/growth, navigation, renderer, embedding or build
configuration changes are part of this repair.

## Behavior

* Step-up requires support from the actual observer-local mesh or an up-facing
  contact recorded by the downward swept/sliding movement. A vanilla
  `isOnGround()` claim cannot independently enable climbing a low obstacle.
* Sneak ledge clipping requires actual mesh support. Airborne sneaking leaves
  requested movement unchanged even when vanilla claims the player is grounded.
* Sliding records the contact normal, rather than treating any clipped downward
  displacement as landing. A leaning wall can deflect movement upward without
  becoming ground support.
* The player adapters and entity-free regressions call the same movement cores.
  `MotionState` snapshots the player inputs, including the intentionally
  non-authoritative vanilla ground claim, so conflicting flags are testable.
  `Slide` retains real swept landing evidence. Neither creates a block-shaped
  stand-in or fabricates a floor.

## Regression coverage and checks

`src/runtimeTest/java/io/github/mysticism/dimension/spiritworld/terrain/MeshGroundSupportTest.java`
now has **81 checks** (the retained 28 affine support checks plus 53 movement
checks). Both reported gates are tested with an explicit TRUE vanilla flag and
NEGATIVE mesh support. Positive controls cover real support with a FALSE flag,
a genuine swept landing from beyond the support probe, supported sneak ledges,
flight/non-sneak/external-movement bypasses, and absent frames.

The normal `./gradlew --no-daemon --console=plain build` passed, including the
five discovered current main-based runtime classes, packaging/runner fixtures,
client/common compilation, remapping, and production-jar verification. The
existing historical suites excluded by `scripts/selftest.gradle` were not
silently presented as current runtime tests. No test-discovery configuration
was changed.

Three independent temporary mutations were each rejected by `runtimeSelfTest`:

1. Restoring `vanillaOnGround || supported || landed` failed the unsupported
   obstacle step regression.
2. Restoring the vanilla sneak bypass failed airborne SELF movement (expected
   X=0.8, got X=0.0).
3. Replacing recorded landing with `wanted.y < 0 && result.y > wanted.y + 1e-5`
   failed the leaning-wall regression.

All mutations were restored before the final commit and exact-head build.
Long commands use the supplied tmux-jobs helper in the original session/pane;
retained pane/run handles and exact candidate HEAD are provided in the final
build report.

## Unverified and unchanged

No live Minecraft client/server, latency/multiplayer gameplay, GPU rendering or
real model inference was performed. The snapshot/slide-result records add small
per-movement allocations; gameplay/performance measurements remain outstanding.
Native v2 embedding requirements and the separate in-process embedding repair
are neither altered nor claimed implemented by this grounding-only unit.
