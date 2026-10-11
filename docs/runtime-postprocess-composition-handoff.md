# Runtime postprocess composition

Scope: Kuwahara + volumetric fog only, on `feat-runtime-visual-postprocess-composition`.
This supersedes the fog-first ordering/constant-extinction description in
`spirit-volumetrics-integration.md`; its GPU and translucent-depth limitations still apply.
The retained checkout was clean at `bfbce90` and its earlier session had timed out
before implementing this scope. No commits or user edits were discarded.

## Behavior

The full-resolution graph is now **original world color -> Kuwahara -> volumetric
fog -> saturation -> source/processed transition -> main**. Painterly moments see
actual terrain contrast, not already-flattened fog. Both painterly and fog sample
one original world-depth snapshot, never a scratch target's cleared depth. Fog is
composited once; final transition blends the complete effect, not separate effects.
The three scratch targets, bounded kernel/ray counts, original-depth silhouette
guard, occupancy medium, and scene-depth restoration before hand/HUD are retained.

A prepared transformed branch disables vanilla analytic fog even during the
entry/exit crossfade. The saved source scene already includes its original fog;
retaining analytic fog in the transformed branch was applying two fog layers.
Unprepared/failed frames retain the analytic fallback. Fog-hook observation resets
on each world frame, so an earlier successful hook cannot authorize a later frame.

Fog preserves the five-block clear bubble and ramps radial extinction smoothly
rather than immediately washing out nearby terrain. With
`t = clamp((distance - 5) / 59, 0, 1)`, its radial optical-length integral is
`59 * t^3 * (2 - t)` (the integral of `2*smoothstep(0,1,t)`). Exact segment differences
avoid quality-dependent radial integration error. The density floor still guarantees
transmission <= 0.001 at the existing 64-block opaque horizon; the existing >=80
loading minimum is unchanged. The CPU reference retains >99% transmission at eight
blocks and >60% at twenty even with the maximum fully occupied density fixture.
World-space density/occupancy and lighting are still sampled at bounded ray midpoints.
This is not terrain streaming, projection, collision, navigation, or embedding work.

## Actual checks

Java 21, cached Gradle 8.13, normal build (no exclusions, init scripts or source substitutes):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --max-workers=2 build
```

**PASS**: all five discovered main-based runtime suites, `buildSelfTest`, remapping,
and `verifyProductionJar` for `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Discovery explicitly printed the new
`io.github.mysticism.client.spiritworld.SpiritPostProcessCompositionTest`:
**5851 checks**, covering packaged graph/descriptor linkage, broken-graph rejection,
production fog ownership policy, original-depth ordering, quality/horizon bounds,
energy conservation, both effects on terrain fixtures, and background-depth rejection.
The other discovered suites passed: callback isolation 28, async safety 184,
embedding persistence/network 90, pipeline 135. Corrupt-save warnings are intentional.
The framework `test` task is intentionally disabled; `runtimeSelfTest` executes the suites.
Historical `src/test` shared-frame/block renderer suites are not this build's discovery
roots; the new regression is in `src/runtimeTest/java` per `scripts/selftest.gradle`.

The first build reached the new regression but failed its overly strict monotonicity
assertion below the shader's 0.001 early-out cutoff. The assertion now checks monotonic
transmission above that already-opaque cutoff; no shader early-out was hidden or removed.
The subsequent complete build passed. Existing annotation/deprecation warnings remain.
Installed `glslangValidator -S frag` also passed all **four** GLSL150 fragments:
Kuwahara, fog, saturation, transition. `git diff --check` passed. No software installed.

Retained tmux handles (original session `$11`, origin `%1796`):
- Successful canonical build: pane `%1822`, run `06bad5a4480a41ce93d0641d773f75be`, exit 0.
- Four shader validations: pane `%1826`, run `b4c37899b8a94b67a9c446289b3512b1`, exit 0.
- Initial assertion failure: pane `%1822`, prior run `0f31c2240404436e8f8298510fc9b558`, exit 1.
  That pane was subsequently reused; its scrollback is not an archival full log.

## Not verified / remaining risks

No live Minecraft client, gameplay, GPU shader execution, model inference, or visual
performance verification was performed. CPU fixtures and static GLSL validation are
not GPU execution. Fast/Fancy/Fabulous, resize, F3+T, entry/exit reversal, missing-hook
fallback, and wall/glyph silhouettes still need live smoke. Translucent layers/clouds/
weather and other renderer mods retain their existing depth-snapshot limitations.
The conservative foreground-only Kuwahara gather can reduce taps on slopes.
Changing fog's radial distribution preserves the opacity/loading contract but requires
visual review of its appearance on actual generated terrain.

Suggested smoke: compare nearby textured terrain at 8/20/32 blocks and sky/terrain at
64; confirm painterly detail before the horizon and no second fog layer during entry
and exit. Put glyphs behind a wall, strafe/change FOV, resize/F3+T in each graphics mode,
and verify hand/HUD remain untouched. None of these smoke steps is claimed as executed.
