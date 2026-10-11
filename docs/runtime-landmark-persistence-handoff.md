# Retained runtime unit: discovered landmark persistence

Scope: keep genuinely discovered native source-v2 landmarks available across save/load;
no changes to movement, per-player projection, terrain meshes/shaders, or model runtime.
No initializer/build configuration changes are required.

## Reproduced defect and fix

A different native embedding profile invalidates the generated catalogue, but its immutable
geometry files remain. Actual rediscovery of the same source seed starts at revision zero,
reuses the deterministic octree page ID, and previously failed with
`immutable page version conflict: mysticism.landmark.geometry.<id>.0` if materials changed.
The new disk regression reproduced this failure before the production fix.

`LandmarkStore` now persists a fresh UUID geometry storage epoch on each actual profile/source
generation reset. Geometry keys carry `.e-<32 hex digits>`. Source IDs, anchors, page IDs,
revision/CAS rules and native vector codecs are unchanged. Old immutable files remain intact.
Same-profile restarts retain metadata/range admission, exact known air/solid ownership,
frontiers, material masks, growth identity, claims and activity. Startup never hydrates geometry.
Existing **native source-v2** manifests without the optional epoch remain usable without
rewriting; no v1 catalogue is read, migrated, padded or truncated. Missing source-generation
provenance still forces actual new discovery.

Bounded geometry readers, hydration and topology validation accept both native storage formats
and verify page identity/revision. Duplicate page identity cannot be disguised by another epoch.
Profile resets cancel in-flight geometry reads; read freshness compares the captured immutable
record reference as well as its identity. Activity split verification uses the store's new
`geometryReferences(SourceGeometry)` API instead of constructing bare keys.

## Verification

Canonical command: `./gradlew --no-daemon --console=plain build`.
The configured runner discovers all current `src/runtimeTest` regressions plus the four
existing embedding suites. Historical shared-frame/prototype suites remain excluded by the
pre-existing build configuration; they are not claimed as run.

New discovered main-based suites:

- `DiscoveredLandmarkPersistenceTest`: production source topology, real compressed
  `PersistentStateManager` save/reopen, atomic incomplete publication, native vector bit/tail
  preservation, source/semantic admission, exact known/unknown ownership, growth, claims,
  native profile resets/cycling, immutable old-file preservation, restart before rediscovery,
  existing bare-key native saves, missing generation marker, malformed namespace/duplicate
  reference rejection and corrupt out-of-range page identity rejection.
- `FreshProfileActivityPersistenceTest`: real namespaced core/activity split commit, influence
  conservation, native vector tail, no cold geometry hydration, compressed activity/core
  restart and persisted lineage/reference identity.

Fixtures use deterministic observations/vectors and live production topology/codecs/store
APIs, not model inference. New disk fixtures are retained beneath this checkout's `build/`.
The canonical build also runs all existing embedding regressions, `buildSelfTest` packaging/
runner fixtures and `verifyProductionJar` on `build/libs/mysticism-1.0-SNAPSHOT.jar`.

## Limits and risks

No Minecraft client/server gameplay launch or model inference was performed. This scope does
not implement the other reported runtime units or change the embedding-provider prerequisite.
Old/staged generations intentionally consume disk until explicit compaction is implemented.
Vanilla PersistentState multi-file saves are not crash-atomic; missing/corrupt referenced pages
remain loud errors, never fabricated empty terrain. Downgrading to an older binary that does
not recognize epoch keys is unsupported. Integrators constructing new geometry storage keys
must use the store API rather than assuming revision-only filenames.
