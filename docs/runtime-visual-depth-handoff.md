# Retained runtime unit: current spirit terrain depth/medium

Scope: `feat-runtime-visual-visual-depth`, based on retained `bfbce90`. The old
session failed at the provider before writing changes; the initial checkout was
clean. This unit does not claim to implement the other ten runtime units.

## Behavior changed

- Keep custom VBOs on MAIN with explicit LEQUAL testing and color/depth writes.
  The existing AFTER_TRANSLUCENT depth snapshot remains the fog ray endpoint;
  density hints are **not** a replacement for surface depth or collision.
- Refresh density in the terrain world-render callback, from the actual camera
  and accepted immutable observer frame, after GPU upload succeeds. A new frame
  refreshes immediately; movement >=4 blocks refreshes even without a packet.
  Smaller motion uses the published world-space origin plus camera offset.
- Upload/bind occupancy immediately before the END postpass, alongside the current
  scene-depth sampler. START no longer pairs new depth with a previous render's
  occupancy. Clear also unbinds the old texture, and failed/empty uploads discard
  hints for geometry that is no longer on the GPU. The mesh-depth status flag now
  requires a successful batch draw.
- Include visible non-collidable baked cells, not only their collision boxes.
  `SpiritMediumField` conservatively rasterizes affine visual bounds into the
  existing 16^3, 8-block-voxel field. Highest opacity wins, independent of cell
  ordering. Row bitmasks bound the work to <=2048*16*16 row visits plus <=4096
  writes, without the old 16384-write cutoff that lost late visible cells.

No observer q/basis, deep/shallow decisions, transport, source octree ownership,
server collision, native-v2 embedding profile, persistence or provider changed.

## Checks actually run

Canonical command, using the preinstalled Gradle 8.13 distribution:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

Run through `tmux-jobs`, original session `$11`, origin pane `%1798`, retained
build pane `%1810`. Baseline: exit 0, four embedding test mains discovered.
After the patch: exit 0, **six** runtime test mains discovered and executed:

- `SpiritMediumFieldTest`: **24601** checks executing production field/cache code:
  packet-independent camera motion, frame/opacity replacement, empty/clear,
  independent observer caches, invalid cameras, detached samples, noncollidable
  visuals, skewed/negative/large translated bounds, clipping, exact voxel edges,
  overlap maxima and maximum-size/order-independent meshes. Independent slow
  voxel-intersection oracle verifies every voxel in several fixtures.
- `SpiritTerrainDepthContractTest`: **23 static wiring checks**, not live GL:
  render/upload/depth/capture/postpass order, same-frame sampler binding,
  absence/clear handling, MAIN layer state, fog endpoint and atlas agreement.
- Existing embedding callback isolation **28**, engine safety **184**,
  persistence/network **90**, pipeline **135** checks/assertions passed.

`buildSelfTest` packaging/descriptor/discovery/failure fixtures and
`verifyProductionJar` passed; remapped artifact:
`build/libs/mysticism-1.0-SNAPSHOT.jar`. `git diff --check` passed.
Expected malformed embedding-save fixtures log exceptions; they passed. Existing
API Guardian annotation/deprecation warnings remain. No exclusions, diagnostic
classpath overrides, software installs, model downloads or live service calls.

## Risks / remaining manual verification

No Minecraft launch, gameplay, real GL driver/framebuffer/mixin execution,
Fast/Fancy/Fabulous smoke, resize/reload/disconnect GPU exercise, FPS measurement
or model inference was performed. Density is deliberately a conservative coarse
AABB hint: it can fill empty parts of skewed cells. Sub-4-block cached-camera
motion can leave a narrow optional-density gap at the far volume edge; the
procedural extinction floor and exact depth endpoint still apply there. Verify
near/custom-surface occlusion and continuous traversal/teleports in a real client.
This unit neither restores nor verifies in-process embedding inference; that is
separately assigned work. No known compilation/test/packaging blocker remains.
