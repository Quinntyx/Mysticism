# Runtime fog-range unit

## Current client contract

The fog horizon and terrain-mesh culling now use the **configured client render
option**, refreshed once per world frame (including shader-fallback frames).
They must never follow the extent/readiness of the currently published source
patch: doing so would conceal missing source acquisition with opaque fog.

| Render option | Clear foreground | Opaque horizon |
| --- | ---: | ---: |
| 8 chunks | 64 blocks | 128 blocks |
| 12 chunks | 96 blocks | 192 blocks |
| 16 chunks | 128 blocks | 256 blocks |
| 32 chunks | 256 blocks | 512 blocks |

Options are guarded to Minecraft's 2..32-chunk range. The first half-radius is
clear; the remaining half uses a quadratic extinction ramp. Its cubic integral
is evaluated analytically per segment. Normalized endpoints avoid cancellation
when LOW quality divides large distances into nonintegral steps. Every quality
retains the same horizon, approximately 0.001 transmission or less (allowing
float rounding). At three-quarters radius, even the densest unoccupied medium
retains over 27% scene transmission instead of already hiding the landscape.

World-space periodic density and bounded published-occupancy modulation still
provide volumetric variation/scattering; this is not replacement linear screen
fog. Nearest scene-depth unprojection limits rays to opaque geometry. Clear sky
marches to the horizon. Foreground rays skip medium integration entirely. Sample
budgets remain 24/40/64, independent of render distance. The shader-failure linear
fallback uses the same clear/opaque radii. No new textures/FBOs, payloads, model
runtime, dependencies, or source generation on the render thread are introduced.

Terrain mesh culling no longer rejects published meshes beyond 64 blocks. The
existing frustum/bounds culling, mesh geometry/depth, server opacity, and collision
publication remain unchanged. This presentation radius is **not** a collision or
terrain-readiness guarantee. Source ownership, octree growth, independent
per-player deep/shallow projection, and native v2 embedding policy are untouched.

## Actual verification

From this checkout, using the existing Gradle 8.13 distribution and Java 21:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --max-workers=2 --continue build
```

Final canonical run: **exit 0**, `BUILD SUCCESSFUL`, 11 seconds. It runs the
unmodified discovery/packaging wiring, with no task exclusions, temporary source
substitutions, diagnostic classpath overrides, or additional installs:

- `runtimeSelfTest` discovered and passed **5 main-based classes**:
  - `SpiritFogRangeSelfTest`: **18,276** CPU/resource-contract checks, including
    all render options and quality tiers, partial/horizon float integrals,
    extinction bounds, energy conservation, foreground/sky, negative/world-border
    coordinates, live-option wiring, culling/fallback, and descriptor defaults.
  - `AsyncEmbeddingCallbackIsolationTest`: **28** checks.
  - `AsyncEmbeddingEngineSafetyTest`: **184** checks.
  - `EmbeddingPersistenceTest`: **90** assertions.
  - `EmbeddingPipelineTest`: **135** assertions.
- `buildSelfTest` passed decoded metadata, 14 descriptor rejection regressions,
  valid packaging plus 19 rejection fixtures, and runner discovery/order/assertion/
  failure/missing-main/empty-suite fixtures.
- `verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar` after
  normal Loom remapping. The framework `test` action is intentionally disabled by
  existing project wiring; `runtimeSelfTest` is the actual test runner.
- Installed `glslangValidator -S frag` separately passed all four current GLSL150
  fragments: fog, Kuwahara, saturation, transition. This is **not GPU execution**.
- `git diff --check` passed.

Long build was run through the required tmux-jobs helper from original pane
`%1840`, session `$11`, retained job window `@1210`, pane `%1851`, final run token
`3d10d9cfa50c451680aa7c3b6d3c3b9e`. First build genuinely failed the new float test
(`0.0010000145994126797` versus `0.001`); normalized shader endpoints fixed it,
rather than weakening the tolerance. The subsequent two builds passed.

The supplementary historical `python3 scripts/check-spirit-render.py` is **not
fully passing**. Its fog/descriptor assertions were updated, but it then stops at
`bounded stable glyph enumeration`: the current semantic-ring glyph renderer no
longer has the historical `MAX_GLYPHS`/`ids.sort` implementation. Other historical
glyph assertions may likewise be stale. Do not cite this script or the archived
`src/test/.../run-render-tests.sh` as passing; the discovered current fog suite
above is the canonical regression. Glyph behavior is outside this unit's scope.

## Risks / remaining integration and smoke work

No Minecraft client/server, GPU shader execution, visual-quality/performance
measurement, resize/reload/Fabulous smoke, or real embedding inference was run.
Expected corrupt-save warnings in persistence fixtures are not runtime failures.
Existing Loom/API annotation and Gradle-deprecation warnings remain.

The terrain service still limits publication around the observer (including its
existing 128-block mesh selection) and has 64-block hidden-window staging rules.
This patch intentionally does **not** invent distant terrain or shrink fog to hide
that independent source-locality defect. Integrate the separately owned source
coverage work, then visually test landmark terrain over the requested render
distance, including source-window mutation/transition visibility. The occupancy
atlas is still a bounded local 128-block cube: outside it, the continuous analytic
world-space medium supplies volumetrics; no distant occupancy is fabricated.

Manual smoke (NOT performed): compare 8/12/16/32-chunk options while stationary;
verify foreground remains clear, distant published meshes are not culled at 64,
option changes affect fallback and volumetrics together, and sky fog reaches the
same horizon. Check distant source-window transitions, Fast/Fancy/Fabulous, LOW/
MEDIUM/HIGH frame times, F3+T/resize, and forced invalid-shader fallback. Do not
claim the full eleven-runtime-problem request resolved by this client-only unit.
