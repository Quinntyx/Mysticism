# Retained postprocess-resource / fog-range integration

Branch: `feat-runtime-sol-merge-0026`, targeting development only.

Integrated retained parents without resetting existing work:

- `c17590bbc6fba2934e81d08587d9f91747b8cbcc`: framebuffer and sampler lifecycle.
- `96f4a659f676c441aa0d5f17f34d5d0d7e07355d`: useful render-option-based fog range.

## Resolution and regression coverage

The conflicting world-frame START handler now refreshes the configured fog range
**and** invalidates the production `SpiritPostprocessFrame` before any entry,
fallback or missing-world early exit. It does not restore the retired boolean
readiness flags. Frame preparation, same-surface scene-depth capture and one-shot
END consumption remain authoritative. Startup, actual framebuffer pixel resize,
main-target replacement, managed reload, and sampler rebinding retain the resource
parent's behavior. Both volumetrics and fallback/mesh culling retain the distance
parent's range; sparse source terrain cannot shrink that range to conceal missing
acquisition.

Added merge regressions exercise reload with reused GL names and resized-surface
recovery while changing 8/12/16/32/8-chunk presentation ranges. Source contracts
also require invalidation before early returns, production capture/consume wiring,
and lifecycle-gated vanilla-fog suppression. These are CPU/source/resource tests,
not a live driver or Fabric callback-order test.

## Actual checks

Canonical installed Gradle 8.13, Java 21 command (no exclusions or classpath
substitutions):

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --max-workers=2 --continue build
```

Passed, exit 0, including compilation, normal remapping, `runtimeSelfTest`,
`buildSelfTest` and `verifyProductionJar`. Discovery executed all **7** current
main-based runtime classes:

- `SpiritFogRangeSelfTest`: 18,280 checks.
- `SpiritPostprocessFrameTest`: 115 checks.
- `SpiritPostprocessResourcesTest`: 394 checks, including 6 invalid-resource cases.
- `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
- `AsyncEmbeddingEngineSafetyTest`: 184 checks.
- `EmbeddingPersistenceTest`: 90 assertions.
- `EmbeddingPipelineTest`: 135 assertions.

Packaging checks include decoded metadata, 14 descriptor-only rejection cases,
19 invalid-archive cases, and runner discovery/order/assertion/failure fixtures.
The framework `test` task is intentionally disabled by existing wiring;
`runtimeSelfTest` is the discovered current suite. Production artifact:
`build/libs/mysticism-1.0-SNAPSHOT.jar`.

`bash docs/smoke-rebuild-render.sh` passed existing offline GLSL syntax validation,
including all four fog/Kuwahara/saturation/transition fragments. `git diff --check`
and staged whitespace checks passed. The supplementary historical
`python3 scripts/check-spirit-render.py` fails at `bounded stable glyph enumeration`:
its old `MAX_GLYPHS`/`ids.sort` assumptions do not match the current semantic-ring
glyph renderer. This was already recorded by the fog parent; it is not counted as
a passing check and unrelated glyph behavior was not rewritten for this merge.

Build and shader jobs used tmux-jobs from original pane `%2217`, session `$11`,
retaining panes `%2226` and `%2229`. Initial successful build token:
`0c64f55cede34d0b937fe7ff98204b0c`; final repeat build token:
`349dfe3a93df4d08adb6340eda72f8e4` (exit 0, 17 seconds, all 7 suites rerun);
shader token: `e551dafa274f4040b27e96b7a04d2690`.

## Limits / follow-up

No Minecraft startup/gameplay, GPU execution, real framebuffer resize/F3+T,
Fast/Fancy/Fabulous smoke, performance measurement or model inference was run.
Existing API annotation/mixin/deprecation/Loom warnings remain; expected corrupt
persistence-fixture warnings are not failed assertions. This scope does not claim
to fix all eleven reported problems or restore in-process embeddings.

The source terrain service still has independent publication/staging limits,
including its 128-block selection and 64-block hidden-window rules. Wider client
fog exposes that missing-locality issue rather than hiding it; source-coverage
integration is still required for actual distant terrain. The occupancy atlas
remains local, with analytic world-space medium outside it. Independent per-player
deep/shallow projection, source-coordinate octree ownership/growth, collision
publication, and fresh native v2 embedding requirements were not altered.
