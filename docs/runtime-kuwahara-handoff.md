# Runtime Kuwahara pass repair

Scope: `feat-runtime-visual-kuwahara-pass`, retained base
`bfbce90c4752e8f6ee821769eb9d51a43453a13b`. Only the painterly pass,
its uniforms/settings, tests and this handoff change. Navigation, per-player
projection, source-coordinate ownership, collision, embeddings and fog integration
are unchanged. Existing commits were retained; checkout was clean on resumption.
The previous retained session timed out during investigation, before implementation.

## Behavior

The existing Satin chain and world callbacks were already wired, including the
registered `SpiritBackgroundRendererMixin`. The failure reproduced in the shader:
foreground-only depth rejection leaves some quadrants with just their center tap.
Those quadrants have zero variance and previously overpowered neighborhoods with
real samples, so textured sloped terrain was effectively unfiltered.

* Reject single-sample quadrants and weight supported quadrants by squared coverage
  as well as inverse variance. If every quadrant is isolated, preserve the original
  pixel. Preserve existing foreground-only depth gather and sky/silhouette guards;
  do not admit background glyphs onto foreground terrain to make filtering stronger.
* Space taps by bounded framebuffer-height-derived stride (1 at 540p, 2 at 1080p,
  3 at 1440p, 4 at 2160p). Quality still selects radius 1/2/3, with at most 49 unique
  taps; no extra render targets, per-frame allocations or random tap jitter.
* Set `SampleStride` in the live program descriptor and every prepared world frame.
* Default filtering is enabled. `-Dmysticism.spirit.kuwahara=false` or
  `ShaderManager.setKuwaharaEnabled(false)` sets radius zero for exact pass-through
  without disabling fog, saturation or entry/exit transition. Quality controls
  remain `-Dmysticism.spirit.quality=low|medium|high`.

## Reproducible verification

Use the already-installed Gradle 8.13 distribution documented in `BUILDING.md`:

```sh
GRADLE=/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
xvfb-run -a "$GRADLE" --no-daemon --console=plain --rerun-tasks build kuwaharaGpuTest
```

Run long commands using the environment's `tmux-jobs` helper and retain its pane/run
handle. No software installation or extra test dependency is needed. With an
existing display, `xvfb-run` is unnecessary. The GPU task fails, rather than silently
skipping, if no suitable OpenGL/display context exists. It is explicitly separate
from the headless canonical `build`.

The authoritative test set is `scripts/selftest.gradle`: `runtimeSelfTest` discovers
`src/runtimeTest/java` plus the existing embedding tests. Historical discarded
shared-frame/block suites under other `src/test` paths (including their old render
scripts) are not the current CI test set. No discovery exclusions were added here.

Final forced full run, 2026-10-10, **exit 0 / BUILD SUCCESSFUL**, 15 tasks executed:
original session `$11`, origin `%1696`, retained pane `%1730`, run
`1335f5fe882b400a858911e3e846d5f4`.

* `runtimeSelfTest`: **five discovered mains**, including new `KuwaharaPassTest`
  (65 resource/settings checks), callback isolation (28), engine safety (184),
  persistence/network (90) and pipeline (135). Discovery is printed by the runner.
* `buildSelfTest`: metadata, descriptor, packaging positive/negative fixtures and
  runner discovery/order/assertion/failure tests passed.
* Both common/client compilation, resource processing, Loom remap and
  `verifyProductionJar` passed. Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
  A separate ZIP comparison confirmed all 14 shader resources are byte-identical
  to source and neither Kuwahara regression class is packaged in the mod.
* `kuwaharaGpuTest`: **60,392 checks each** for RGBA32F and actual game-format RGBA8
  targets on Mesa 26.2.3 llvmpipe, OpenGL 4.6. Compiles and links the shipped GLSL
  against Minecraft's actual blit vertex, applies descriptor defaults, binds real
  textures/FBOs, executes draws and reads output. Covers all quality radii, flat
  and sloped texture filtering, isolated silhouettes, sky/terrain edges,
  foreground-wall/background-glyph protection, border finiteness, alpha, disabled
  pass-through, stride 1..4 and the actual four-pass post graph through final output.
  Final red-channel impulse: **0.9 -> 0.1888889** (float) / **0.19215688** (RGBA8).
  Disabled filtering and transition strength zero preserve the original.
* Negative control: the same probe with the retained base's actual `kuwahara.fsh`
  and descriptor, overlaid only in ignored owned `build/` resources, **fails** at
  `single-sample quadrant cannot dominate sloped terrain at radius 1` (exit 1).
  Retained pane `%1725`, run `f5654572465a471682a4c87385ac01da`.

Existing missing API Guardian annotation/deprecation warnings and intentional
corrupt-persistence fixture diagnostics remain; they did not fail the build.

## Limits / manual acceptance still needed

This is real offscreen shader execution, **not a Minecraft gameplay launch**.
Fabric/Satin callback execution in a live session, physical-GPU performance,
Fast/Fancy/Fabulous, F3+T, resize/GUI scale, and entry/exit behavior were not exercised.
No model inference was performed. No embedding profile/model/runtime changes or
external service prerequisites were introduced.

Manual acceptance: enter a ready spirit scene, wait for the existing fade, compare
textured/sloped terrain with filtering on/off at the same fog/saturation settings;
repeat all three quality levels, wall occlusion, GUI scale, framebuffer resize,
F3+T and exit/re-entry. Single-pixel silhouettes should intentionally remain sharp.
