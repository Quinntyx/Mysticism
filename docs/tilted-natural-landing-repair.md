# Retained navigation review repair

Branch: `feat-runtime-merge-0006`; eventual target **dev**, never main.

The retained checkout began clean at `7e8d3b2` (the completed navigation merge),
not in the earlier conflicted state. Its previous session history and both immutable
merge parents were inspected and retained. This repair addresses the scoped P2
review: natural landing required a `.99` observer-grid normal BEFORE requesting
source discovery/alignment. A real source-flat floor rotated 10 degrees has normal
y about `.985`; a resting airborne player could never start the alignment needed
to satisfy that gate.

## Behavior

- Sustained actual `MeshCollision.ground` contact (normal y > `.3`) can now start the
  existing bounded natural support approach; real airtime and ten consecutive rest
  ticks are still required. Upward movement, loss of contact, pending acquisition,
  captured landing and touch blending retain their guards.
- This admission grants ONLY intent. Final `SpiritWorldTerrain` acquisition still
  requires normal y >= `.99`, exact source body/floor ownership, current proof, grid
  agreement, clearance and continuous accepted mesh publication. No substitute
  floor, teleport, ownership rebinding or premature shallow-mode change was added.
- The production contact/cadence logic is exposed through a package-visible seam
  so the regression exercises real navigation sessions rather than a copied policy.
- Existing per-player entry/mode readiness, teleport reconciliation, stepping,
  anti-spam, cross-owner discovery and independent projection behavior is retained.
  No embedding/model/profile, octree, renderer or shader changes were made.

## Actual checks

All commands used installed Java 21 and cached Gradle 8.13, via the tmux-jobs skill
with explicit argv and bounded monitoring. Original connection:
`/tmp/tmux-1000/default,30794,11`; origin `%2361`, session `$11`; retained build pane
`%2387`, window `@1489`.

1. Added the regression with the original `.99` admission still in place.
   Run `761c42cf85264ea3b242483d0f4ddaee` exited **1**, failing
   `NaturalLandingTriggerTest`: a resting airborne player did not initiate
   discovery/alignment from the measured 10-degree support.
2. After repair, ran the canonical complete build with all currently configured
   runtime and packaging suites, forcing task execution:

   ```sh
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
   /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
     --no-daemon --console=plain --rerun-tasks \
     build buildSelfTest runtimeSelfTest verifyProductionJar
   ```

   Run `121ead277df34cad8af623bc6c0fd029`: exit **0**, **BUILD SUCCESSFUL**, all
   **14 tasks executed**. Runtime discovery visibly ran **16 main-based suites**,
   including the new `NaturalLandingTriggerTest` (**44** checks), updated real mesh
   contact (**10**) and walk discovery/acquisition (**125**) suites. Existing mesh
   stepping (**76**), per-player entry (**28**), teleport arrival (**87**), retry,
   feedback, reporting, walk-target and four embedding safety/persistence/pipeline
   suites also passed. Runner discovery/order/assertion/failure fixtures and
   packaging/descriptor fixtures passed. Common/client compilation, `remapJar`
   and production jar verification passed:
   `build/libs/mysticism-1.0-SNAPSHOT.jar`.
3. `git diff --check` passed. Both recorded merge parents remain ancestors.

The new trigger regression uses real affine mesh collision at 10 degrees and
actual independent navigation sessions. It covers full rest cadence, standing
flight/takeoff, upward/lost/faded contact, per-player isolation, pending request
suppression, blending and captured landing. The acquisition regression admits
measured tilted contact to real discovery, refuses shallow acquisition while its
normal is below `.99`, then acquires the same discovered owner on an accepted
aligned frame without rebinding the producer. Server I/O and the replacement
aligned frame are deterministic fixtures; they do NOT simulate full server
alignment/frame generation.

## Risks / unverified

No live Minecraft/Fabric launch, multiplayer movement, injected mixin boot, GPU
rendering, IntelliJ launch or real model inference was performed. Conservative
alignment clearance/extraction scheduling can still leave a player deep; actual
10-degree landing and delayed packets need gameplay validation. The existing
bounded-acquisition expiry/cooldown semantics are unchanged. Compiler API Guardian
and mixin-target warnings, Gradle deprecations and expected malformed-persistence
fixture logs remain nonfatal. This unit does not claim to resolve all eleven
umbrella issues or restore/verify the separately owned in-process embedding backend.
