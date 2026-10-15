# Retained pooling/readiness integration

Branch: `feat-runtime-sol-merge-0062`, targeting **dev**, never main.

This resolves the interrupted merge of pooling parent
`e4252cb10a8d4219416eb3e8dffa3e75c2909df9` and readiness parent
`6a2fa18525597a06c73573e5e164a9b149305a34`. Both histories and the
existing output/composition work are preserved. This is the assigned embedding
unit, not a claim that all eleven reported gameplay problems were repaired here.

## Combined runtime contract

- Normal launches use the embedded CPU/JNI provider. An endpoint property cannot
  select HTTP; Ollama requires explicit provider selection. No external inference
  server, substitute model, dummy runtime vectors, v1 migration or truncation.
- MEAN-pooled native sentence output uses the retained stable L2 adapter with all
  768 coordinates. Weighted landmark composition retains compensated-double
  accumulation and weight scaling without overflowing the total. Cached and
  composed results are independently owned.
- Tokenization and inference consume identical prefixed text. Malformed Unicode
  and valid tokenizer overflow reject only their descriptor, without truncation.
  Invalid tokenizer/native output or JNI failure revokes actual model readiness.
- Model loss empties the generation cache, drains pending callers and disposes
  the provider without waiting on the game/deadline threads. Discovery admission
  and navigation consume that live state and original cause, not an unreachable
  endpoint or a previously populated item index. Published current-profile
  anchors and independent physical source/ownership reads remain usable.
- Independent per-player projection, source-coordinate ownership/growth and
  custom terrain render/collision algorithms are unchanged.

Conflicting fixture tests now retain numerical/Unicode coverage **and** terminal
model-error assertions. The new combined regression sends canonical block and
biome labels through the real provider/async engine, compares all 768 composed
coordinates for extreme finite outputs/weights, checks cache ownership, then
feeds invalid region output and verifies generation/cache/discovery invalidation.
Those model outputs are test fixtures, not observed real semantic embeddings.

## Checks on the integrated candidate

Java 21, cached Gradle 8.13; no test exclusions, software installation or weight
download. Original tmux session `$11`, origin `%3162`.

```sh
./gradlew --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0 in 39s, all **15** actionable tasks executed. Common/client/
runtime-test compilation, packaging and runner suites, remapping and
`verifyProductionJar` passed. Production artifact:
`build/libs/mysticism-1.0-SNAPSHOT.jar`.
Retained pane `%3164`, run `7314aa624bd8484f90147b3ed9b73a54`.

All **13 discovered** main-based suites executed with assertions enabled:

| Suite | Checks/evidence |
| --- | ---: |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddedDiscoveryReadinessSelfTest | 956 |
| EmbeddedEmbeddingSelfTest | 54 |
| EmbeddedNativeOutputTest | 3147 |
| EmbeddedNativeRuntimeSelfTest | Real JNI ABI `b10682-5ea1b124e`, no inference |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessSelfTest | 53 |
| LandmarkEmbeddingPoolingTest | 808 |
| NativeEmbeddingOutputTest | 107 |
| PinnedEmbeddingModelSelfTest | 25 |
| DiscoveryReadinessSelfTest | 18 |

`buildSelfTest` passed valid packaging, 26 rejection cases, 14 descriptor-only
rejections, decoded metadata and discovery/order/assertion/failure fixtures.
The framework `test` task is intentionally disabled; these checks execute via
`runtimeSelfTest`. Existing warnings remain: 98 client annotation/Mixin warnings,
Jackson semver, multiple development SLF4J providers and Gradle deprecations.
Malformed-persistence traces are expected negative fixtures.

## Explicit real-inference blocker

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false
```

**BLOCKED**, exit 1 in 6s: `FileNotFoundException: Pinned Nomic v2 GGUF missing`
at `build/embedding-inference-probe/nomic-v2.gguf`. Retained pane `%3166`, run
`033fb4800cd04090b1fafa407978980a`. The exact Mysticism cache and Ollama blob
locations were also rechecked: absent. No model weights were downloaded or
substituted, and no external inference service was used.

Provide the exact **957,680,480-byte** GGUF, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`,
and rerun with `-PembeddingModelPath=/absolute/path/to/exact-v2.gguf`.
Normal runtime supports first-use pinned provisioning; this verification did not
enable it. See [deployment contract](nomic-embedding-contract.md).

No actual model forward pass, semantic quality/latency, Minecraft/IntelliJ launch,
gameplay or cross-platform model execution is verified. JNI load/ABI, mathematical
fixtures and source integration are not substitutes for those checks. The code
integration/build is complete; real inference/runtime acceptance remains blocked
by the missing approved asset. Parent handoff records describe their respective
historical builds, not additional executions on this candidate.
