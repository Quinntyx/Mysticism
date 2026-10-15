# Retained mesh walking acquisition review repair

Branch: `feat-runtime-sol-merge-0041`, continuing merge candidate `a53b907` without replacing either parent.

## Reviewed defect and repair

Alignment preserves the actual inverse-mapped feet, rather than snapping to a source block. For the unit floor tilted 12 degrees, those feet have source Y approximately 1.0624. Mesh grounding correctly accepts that support after alignment, but the old source-floor proof sampled `floor(feet.y - .05) = 1`, i.e. known air. Acquisition therefore never reached walking even after successful alignment.

The cached source proof now resolves the full `MeshCollision.supportFootprint`, including known air. It queries the cached tiles' **actual collision primitives** through the same `MeshCollision.ground` sweep/rise/drop/normal logic as projected mesh support, in source coordinates with identity axes. It does not replace shapes with full cubes or infer collision from block identity. Unknown footprint samples remain unready. Body obstruction, ownership/revision, source-basis/semantic residual, and final whole-scene body/continuous-transition guards remain required before publishing the shallow session. Feet, semantic position, physical position and captured destination are not snapped or changed by this repair.

The final source/mesh acquisition proof and navigation mapping/state commit are factored into entity-free production methods used by the real controller and regression. Mapping mismatches still refuse walking.

## Regression and validation

`WalkAcquisitionProofTest` drives the real placement/pivot/blend/continuous collision helpers, source acquisition proof, and navigation walking-state commit for +/-5, +/-12, +/-20 and +/-24 degrees at three physical positions, including near world limits. It checks exact feet retention, request-state completion, prediction epoch change and captured-target preservation. Additional cases cover edge-of-footprint support, unresolved adjacent samples, partial collision shapes, a half slab, source body obstruction, projected penetration, grounding-range boundary agreement, and missing/changed source mapping. **2,169 checks** passed.

Canonical Gradle 8.13 `--no-daemon --console=plain build --rerun-tasks` passed on the completed source: **11 discovered runtime suites**, build/packaging/runner fixtures, remapping and production-jar verification. An earlier build overlapped edits introducing the test seam and failed test compilation against the earlier common output; the completed-source rerun recompiles all tasks and passes. Nonfatal existing API Guardian annotation, mixin-target and Gradle deprecation warnings remain. Embedding persistence negative fixtures intentionally log rejected malformed vectors; no model inference is performed by those tests.

This is deterministic entity-free validation, **not** a live Minecraft/server launch, multiplayer/network timing test, GPU/render test, or model-inference verification. Full server/session ownership and asynchronous source-IO adapters are not instantiated by the new regression. Teleport collision/readiness and landmark availability implementations are preserved and their existing discovered suites still pass. Embedding implementation/profile is unchanged; no migration, truncation, dummy vectors, dependencies or external-service requirements were introduced.

## Follow-up: neighboring collision producers

A subsequent static review found that a fence/wall at source Y=0 can have a real collision top at Y=1.5. Feet at Y=1.5 produce a swept footprint starting at Y=1.375, so enumerating only block origins inside that footprint omits the cached supporting shape.

The acquisition proof now separates direct-sample knowledge from collision-producer lookup. Direct body/support samples must still be resolved, including air. A bounded neighboring-origin query inverts the same [-2,3] local-shape envelope enforced by `TerrainMeshFrame.Cell`, then filters candidates by their actual world-space collision boxes overlapping the probe. It neither scans the entire source cache nor fills missing tiles with blocks. Missing supporting producers provide no evidence of support; unrelated missing neighbors do not falsely veto an otherwise known footprint. The same producer lookup protects cached-source body clearance. Projected body clearance now broad-phases each actual affine shape, not the containing unit cell, so over-height/overhanging shapes cannot hide penetration outside that cell.

The added acquisition-to-walking cases use a 1.5-high thin fence-like shape with its origin below the probe, two allowed negative-origin overhangs and a positive-origin overhang. Each runs from an aligned deep frame through the production source/mesh proof and walking-state commit, both exactly on the top and with the preserved 0.0624 source-feet gap. They check request completion, prediction epoch, exact feet/owner and captured-target retention. Missing producers, unresolved direct samples and penetration (including independently obstructed source geometry with a clear projected frame) must refuse acquisition.

Red/green validation: the new tall-shape regression first failed on the unmodified production implementation at the source-acquisition assertion, while the displayed mesh ground query passed. After the repair, canonical Gradle 8.13 `--no-daemon --console=plain build --rerun-tasks` passed all 11 discovered runtime suites and packaging verification; final candidate verification is reported with the exact commit. Existing teleport/landmark suites remain discovered and pass. No dependencies or embedding code were changed. This remains entity-free validation, not live gameplay, asynchronous server/session integration, GPU rendering or actual model inference.
