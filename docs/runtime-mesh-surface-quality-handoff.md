# Retained runtime mesh surface-quality scope

Branch: `feat-runtime-mesh-mesh-surface-quality`, based on retained `bfbce90c4752e8f6ee821769eb9d51a43453a13b`. The previous session timed out during inspection and left no edits; its commits are retained. This is only the mesh-boundaries/bounds/orientation unit, not a claim to fix all eleven runtime problems.

## Behavior repaired

- `MeshStitcher` no longer fits a separate nonlinear vertical border field to every affine leaf. Its per-cell distance strength, endpoint fit and displacement cap gave adjacent leaves different shared faces, particularly across octree LOD and the five-meter boundary. Source-window projection still supplies the deep distortion; shallow/source alignment and source-coordinate ownership are unchanged. Actual gaps between independently projected regions and real cliffs are not filled with invented floors.
- Exact coincident opaque full cubes select a whole producing cell, prioritizing opacity then local support with stable owner/key ties. A zero-opacity duplicate cannot swallow visible support. Empty and partial shapes no longer cause unrelated baked models to disappear. Incompatible full-cube materials no longer dither under another owner's retained key: coincident cubes have one deterministic skin/producer.
- Terrain VBO culling bounds now come from actual emitted float vertices, including extended baked models and fallback collision primitives. Unit-model affine bounds remain available for projection consumers. Flat quads get a small conservative culling margin. No GPU-object budget changes.
- The render adapter normalizes inverse-transpose normals before signed-byte vertex packing; shear and reflection retain the correct outward surface orientation. Existing double-sided terrain rendering is retained. Collapsed axes fail frame validation before normal inversion/SAT.
- Landing/body and transition broadphases include non-unit collision extents. Fully hidden cells do not obstruct the body-clear check, matching the collision index's existing behavior. Model and physics still use the same affine columns and local source shapes.

## Discovered regression coverage

Tests belong in the current `src/runtimeTest/java` source set, not the excluded historical prototype tests in `src/test/java`.

- `MeshSurfaceQualityTest`: coarse/fine projected face continuity, untouched shallow geometry, real overlaps preserved, whole-cell/deterministic duplicate ownership, hidden duplicate support, empty/partial models, reflected/sheared corner bounds, collapsed axes, non-unit landing/frame obstruction and actual swept top contact.
- `TerrainVertexConsumerTest`: production adapter forwarding/fade, actual extended/flat vertex bounds at large coordinates, inverse-transpose unit/outward normals before packing, invalid vertex/normal rejection.
- These are standalone main-based checks. No game bootstrap, OpenGL context, live embeddings, weights or source-world IO is used.

## Validation

Canonical cached Gradle 8.13 with Java 21:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

Both canonical builds passed compilation of common/client/runtime checks, all six discovered runtime suites, dependency-free packaging/runner suites, remapping and `verifyProductionJar`. Final `--rerun-tasks build`: exit 0, 14 actionable tasks executed, `BUILD SUCCESSFUL in 15s`. Discovery executed mesh surface quality (753 checks), vertex adapter (13), callback isolation (28), async embedding safety (184), embedding persistence/network (90), and embedding pipeline (135). Packaging/runner suites included the 14 descriptor-only and 19 invalid-artifact rejection cases. Framework `test` was skipped by the existing build; actual runtime coverage ran through `runtimeSelfTest`.

Final run retained in original tmux session `$11`, owner `%1841`, job pane `%1863`, run `c544db92cbaf455ca664989921511042`, exit 0. Initial run reused that pane (token `a05ae013c3394b82a2d34b21413072c2`, exit 0). Nonfatal existing Loom/CCA source-remapping, missing API Guardian annotation and Gradle deprecation warnings occurred; malformed-embedding warnings/stack traces are expected negative fixtures, not model-inference results.

## Limits and integration risks

No gameplay/client/server launch, screenshot or GPU verification was performed. Bounds/normal tests exercise the actual vertex adapter but do not upload or draw a VBO. No model inference was attempted. Embedding configuration, native v2 dimensions and persistence were not modified.

Removing the old border field/material dithering deliberately preserves crack-free affine source leaves rather than smoothing independently projected regions. It does not promise watertight seams between genuinely different source projections. Cross-region overlap now selects one stable full-cube producer; partial models are preserved even if they overlap. The existing source streaming/cell budgets and render horizon remain unchanged and must be addressed in their other owned units.
