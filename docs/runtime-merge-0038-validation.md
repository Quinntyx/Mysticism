# Retained runtime merge 0038: integration and validation

Branch: `feat-runtime-sol-merge-0038`; integration target **dev**, never main.
The existing merge and both histories were retained. Parents:

- `8fd50ec6de1d25f7ae51750418d3a173f91156f6`: truthful mode/source status,
  guarded anchor transitions, atomic displayed terrain/collision revisions.
- `897e5fb03c59193a7884e40918d1f0512eb3537d`: pinned in-process native v2
  inference/output and discovery of source terrain already loaded before entry.

## Integration choices

The `SpiritTerrainService.restore` conflict keeps both behaviors. Restore requests
exact source ownership even for a known saved owner; it does not wait for fresh
inference to revalidate saved native ownership. Entry and restore also submit the
bounded loaded-only nearby source backfill after the session is established.
Best-effort background discovery cannot make entry fail. It neither generates
chunks nor grants collision/support/exit permission from a hint.

A saved navigation readiness bit alone cannot mark terrain's anchor delivered.
`SourceAnchorDelivery.canRestoreAnchor` also requires the independently decoded,
compatible `LatentPos` coordinate. This matters when terrain's restore callback
runs before navigation's callback. Missing, malformed or reduced/legacy vectors
must leave source delivery pending; otherwise restore can preapply a metadata
source offset and prevent the later carrier-preserving anchor transition.
Explicitly initialized native ZERO remains a valid coordinate. No vector migration,
truncation, substitute vectors or source ownership inference was added.

The new discovered `SourceAnchorRestoreTest` exercises those persisted-state
combinations, callback ordering, native tail corruption, detached snapshots,
carrier-minus-q continuity on late delivery, physical deep mode and independent
observers. Existing source queues retain separate bounded ordinary/backfill lanes;
existing terrain revision and frame assembly suites remain discovered.

## Checks performed on the integrated source

Canonical command (no exclusions, alternate dependency overrides or skipped suites):

```text
./gradlew --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0, 15 actionable tasks executed. All **16** main-based runtime suites
were discovered and executed with assertions enabled:

| Suite | Result |
|---|---|
| SourceAnchorRestoreTest | 20 checks |
| TerrainRevisionCoherenceTest | 23 checks; CPU-only, not GPU/gameplay |
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedEmbeddingSelfTest | 54 fixture checks |
| EmbeddedNativeOutputTest | 3139 provider/output fixture checks |
| EmbeddedNativeRuntimeSelfTest | Real Linux x86_64 JNI/ABI load; not model inference |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| NativeEmbeddingOutputTest | 107 synthetic tensor checks |
| PinnedEmbeddingModelSelfTest | 25 provenance/transport fixtures |
| LoadedSourceBackfillTest | 78 checks |
| SourceDiscoveryHintsTest | 1208 checks |
| SpiritNavigationReadinessTest | 44 checks |
| SpiritNavigationStatusTest | 29 checks |
| TerrainFrameAssemblerTest | 140 checks |

Metadata decoding, descriptor validation (14 rejection cases), packaging (26
rejection cases), runner discovery/order/assertion/failure fixtures and the
remapped production jar verifier also passed. The distributable is
`build/libs/mysticism-1.0-SNAPSHOT.jar`; weights are **not** bundled.

Nonfatal warnings remain: 98 API-Guardian/mixin client compilation warnings,
Jackson annotation semver, duplicate SLF4J providers on the test classpath,
Gradle deprecations, and expected corrupt-state fixture warnings.

Original tmux session `$11`, origin `%2380`; forced canonical build pane `%2388`,
run `4b0d7ef6972d4cc7adfe8056deaead6c`. The earlier baseline integrated build also
passed with 15 suites (before adding this merge regression).

## Actual pinned native v2 inference: PASS

```text
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=true
```

**PASS**, exit 0, 8m21s including explicit pinned-weight provisioning. This is a
real forward-pass result, not a source/CI approval or fixture-output claim.
The local asset is `build/embedding-inference-probe/nomic-v2.gguf` (ignored),
957,680,480 bytes, verified SHA256:
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
JNI build: `b10682-5ea1b124e`, Linux x86_64 CPU, mean pooling, native 768 output.

The probe ran repeated real inference, real readiness, four different Minecraft
item/biome descriptors, full native shape/norm/finite-output checks and native-tail
retention, mutable-cache isolation and actual tokenizer overflow rejection. Native
logs report `truncated = 0`. An intentionally unusable HTTP endpoint did not affect
local execution. Observed cosines: oak/birch `0.86688715`, oak/lava `0.8003141`;
these are smoke-test observations, **not** a semantic-quality benchmark.

Retained inference pane `%2402`, run `3424130f2e964855abd36bf74f952785`, same
original session/origin. No software installer, external inference server, v1
asset, vector resizing or dummy model was used.

For an offline IntelliJ Minecraft launch, the existing exact asset can be supplied
using `-Dmysticism.embedding.modelPath=` followed by its absolute path in this
checkout. Otherwise the default embedded provider provisions its own verified
cache on first use; that still needs network/disk/native memory and can fail its
bounded preparation deadline. The model file stays outside the production jar.

## Remaining verification limits

No Minecraft client/server gameplay, actual GPU/VBO upload, multiplayer movement,
source-world chunk event execution, IntelliJ launch or cross-platform native
execution was performed. Loaded backfill is bounded to 25 seeds in a 5x5 chunk
neighborhood; it is not proof of full render-distance discovery. Native loading
logged CPU/GPU availability and missing newline/mask-token warnings for the exact
approved GGUF; outputs passed the real probe, but no corpus-quality evaluation
was run. Retained finite source/mesh budgets and fail-closed ownership/support
checks still apply. The historical embedded validation documents describe their
own earlier blocked probes, not the successful probe on this integrated unit.
