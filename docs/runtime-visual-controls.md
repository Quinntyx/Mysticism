# Runtime visual controls (client session)

Scope: postprocess controls and truthful visual state on
`feat-runtime-visual-visual-controls`; target integration branch is `dev`.
No navigation, source ownership, custom terrain geometry/collision, embedding,
initializer, dependency or build configuration changes.

## User controls

Registered from the existing `ShaderManager.init()` client hook. No server
permission, external server, new entrypoint or configuration file is required.
Commands are client-local and tab-completable:

```text
/spiritvisual                         # requested settings + actual last-frame evidence
/spiritvisual status
/spiritvisual help
/spiritvisual on|off                   # master postprocess gate, preserves individual switches
/spiritvisual fog on|off
/spiritvisual kuwahara on|off
/spiritvisual saturation on|off
/spiritvisual quality low|medium|high
/spiritvisual retry                    # explicit recovery; F3+T/re-entry also retry
```

Defaults preserve all three existing effects, medium quality. The existing
`-Dmysticism.spirit.quality=low|medium|high` startup override remains honored;
runtime quality changes apply on the next world frame. Settings survive world
changes/reload/reconnect within this client process, not restart. The atlas
controls page points to the commands. Its separate reduced-motion toggle only
freezes decorative shimmer phase, not world fog or filtering (retained wording
correction).

Fog-off leaves native fog distance/color unchanged, bypasses integration/depth
reconstruction in the GLSL fog pass, and skips occupancy upload. Kuwahara-off
returns the incoming color/alpha without kernel sampling. Saturation-off uploads
1 (full color); when enabled it is the existing 2.5-second *entry* fade, not
permanent desaturation. Switching it back on does not restart elapsed entry time.
Quality changes the actual fog-step/kernel-radius uniforms. Master-off/all-effects-off
skips the entire chain, source-image holding/capture, and releases its framebuffers,
managed effect and GPU occupancy atlas. It does **not** hide custom meshes, remove
collision, change projection/flight, or bypass other scene/avatar fades or sky.

## Evidence and failure behavior

`Requested` is not `Actual`. A settings change first reports pending (or disabled).
Each world frame invalidates prior success. Applied state is recorded only after
Satin renders and saved depth restores successfully; it lists the passes, actual
saturation factor and whether custom mesh depth was observed. Waiting distinguishes
an actually held source snapshot from no valid snapshot. Inactive/disconnect
clears stale evidence. The compact HUD links to the detailed status command.
Shader failures report fallback/retry, not volumetric success; failure phase
survives subsequent frames. A prepared frame without the required scene-depth
snapshot stops the chain and selects fallback on subsequent fog callbacks rather
than indefinitely suppressing native fog while claiming pending. An END failure
cannot retroactively re-fog already drawn terrain: its status explicitly selects
fallback for the next fog callback. Recovery is explicit rather than a per-frame
error loop.

Fog mixin observation is required only with fog enabled; Kuwahara/saturation can
run without it. Missing hook reports unchanged vanilla fog rather than claiming
linear fallback. Shader failure with the hook present selects the existing
linear fog fallback while fog is requested; fog-off never changes native fog,
even when another pass fails. Cached occupancy sampler IDs are cleared to zero
when disabled/unavailable so off/on/reload cannot bind a deleted atlas.

## Regression and build evidence

`src/runtimeTest/java/io/github/mysticism/client/spiritworld/SpiritVisualControlsSelfTest.java`
is discovered by the **existing** `runtimeSelfTest` main-based runner. It covers
all 16 master/effect combinations at all three qualities, preparation/depth/failure/
hook combinations, native-fog policy, live saturation amounts and preference
roundtrips; lifecycle success/failure/wait/reload/disconnect state; real Brigadier
command handler execution with injected non-GL actions; shipped uniform declarations,
identity bypass order, chain preservation and production wiring guards. Shader
resource/source checks are not GPU execution. Existing four embedding suites also
remain discovered; historical excluded shared-frame prototype suites are unchanged.

Canonical command (installed Gradle 8.13 / Java 21, no exclusions or diagnostic
classpath; run via tmux-jobs in the original session/pane):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain --continue build runtimeSelfTest buildSelfTest verifyProductionJar
```

Final run succeeded (exit 0, 13s): five discovered runtime suites (3,652 visual
checks plus 28/184/90/135 existing embedding checks), metadata/descriptor/runner
fixtures, 19 packaging rejection cases, client compilation, remapped production
artifact and `verifyProductionJar`. Common compilation was up-to-date from the
first successful canonical build. Retained handle: original session `$11`, origin
`%1713`, window `@1151`, pane `%1718`, run `ea6545fdec35485a8cd29aa63d6d158b`.
Nonfatal existing API Guardian/mixin/Gradle deprecation warnings and intentional
malformed-embedding fixture warnings do not imply gameplay or inference testing.

## Remaining manual checks (not performed)

Boot Minecraft/IntelliJ; run `/spiritvisual status` outside and during spirit entry,
waiting and exit. Toggle each effect and quality, then master-off/on during entry
and steady spirit; confirm unchanged terrain and physical collision, fog-off
native color/distance, filter-off incoming color, and saturation-off full color.
Repeat resize, F3+T, reconnect and Fabulous; inspect GPU errors and induced failure
recovery. These visual/GPU/mixin/gameplay checks and real model inference were
**not performed**. This branch retains the baseline embedding backend; restoring
in-process native v2 inference is separately scoped and is not claimed here.
