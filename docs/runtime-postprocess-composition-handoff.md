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

A prepared transformed branch disables vanilla analytic **terrain** fog even during
the entry/exit crossfade; sky fog is retained on the live backdrop. The saved entry
scene already includes its original fog. Live original color (including cache-inactive
exit) instead has source fog restored **only on the original branch** by the final
transition pass, using captured vanilla terrain start/end, shape and RGBA plus original
scene depth. Retaining analytic fog in the transformed terrain branch would apply two
fog layers. Unprepared/failed frames retain the analytic fallback. Fog-hook observation
and captured parameters reset each world frame, so earlier hooks/parameters cannot
authorize a later frame.

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

## Retained-unit review repair: source fog during exit

The scoped P2 review was reproduced by code inspection: `original()` selected live
color as soon as `ClientSpiritCache.active()` became false, but that color had been
rendered with terrain fog disabled throughout the outgoing 1.5-second fade. It faded
toward unfogged terrain before vanilla fog abruptly resumed. Simply keeping vanilla
fog enabled would double-fog the transformed terrain; retaining an entry snapshot
would freeze the wrong source view and expires after five seconds.

`SpiritEntryScene.Original` now couples the chosen texture with its fog ownership.
Its production `selectOriginal` is exercised directly, including inactive-cache exit
with a fresh snapshot, long sessions/expired snapshots, observer/source mismatch,
entry reversal and the full-strength live fallback. Saved images are already fogged;
ALL live selections request source restoration. The transition restores the captured
vanilla smoothstep/color-alpha fog only on original terrain color. Sphere/cylinder
metrics use reconstructed camera-relative world positions; the cylinder includes
absolute Y, not view-space Z/ray length. Clear-depth sky is left unchanged and its
SKY fog hook is no longer bypassed. Source parameters are captured at TERRAIN
`applyFog` RETURN before bypass, not at world END (vanilla clears fog there).
The painting/volume/saturation graph, original-depth snapshot and restoration,
occupancy medium, horizons, navigation, collision, ownership and embeddings are unchanged.

### Checks performed for the review repair

Normal cached wrapper, Java 21/Gradle 8.13, no exclusions/classpath substitutes:

```sh
./gradlew --no-daemon --console=plain build
```

**PASS**, exit 0, 1m19s. Discovery printed **six** runtime classes:
- `SpiritPostProcessCompositionTest`: **5870** checks (adds transition sampler and
  captured source uniform descriptor agreement, including vec4).
- `SpiritSourceFogExitTest`: **647** checks (actual production branch selection,
  source-only fog policy/wiring, exit endpoint/threshold continuity, vanilla
  smoothstep/cylinder/sphere/RGBA, saved-image no-double-fog, untouched sky, and
  inverse depth reconstruction across yaw/pitch/FOV with JOML CPU matrices).
- Existing embedding callback isolation **28**, async safety **184**,
  persistence/network **90**, pipeline **135** passed. These are deterministic
  fixtures, NOT model inference; expected corrupt-save warnings remain.

`buildSelfTest` packaging/runner positive and rejection fixtures passed;
`remapJar` and `verifyProductionJar` passed for
`build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` passed.
Installed `glslangValidator -S frag` passed all four fragments, including the modified
transition. Cached Minecraft 1.21.1 shader resources and javap bytecode confirmed
vanilla fog.glsl metrics/alpha, SKY before TERRAIN, and clearFog before world END.
Existing annotation/mixin/deprecation warnings remain.

Retained handles in original tmux session `$11`, origin `%2216`:
- Canonical build: pane `%2247`, run `4c87ea0fc6bf48e8b37bf71d912bf0be`, exit 0.
- Four shader validations: pane `%2249`, run `e8f4bf79a4f54057ac3988cbce9a077d`, exit 0.
- Vanilla call-order inspection: pane `%2245`, run `5462c81afa584a3981b40c61b7165df9`, exit 0.
- Vanilla parameter inspection: pane `%2248`, run `7436b66183724934a976fe55582ec77e`, exit 0.

No live Minecraft client/gameplay, GPU execution/performance or model inference was
performed. Source fog reconstructed per pixel is not exactly vanilla's interpolated
per-vertex fog distance on large primitives; translucent/cloud/weather layers and
Fabulous depth retain the documented snapshot limitations. Live smoke must include
long-session exit toward source terrain between and beyond vanilla fog start/end,
rotated camera/cylinder corners, entry reversal, sky, resize/F3+T and Fast/Fancy/Fabulous.
CPU/static validation is not proof of exact visual continuity in those cases.
