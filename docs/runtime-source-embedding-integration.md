# Retained source discovery / native v2 integration (historical reference)

Imported parent-unit record. Its missing-asset blocker predates the authorized
exact cache asset; current combined retained-unit checks and evidence are in
[merge-0032 native validation](runtime-sol-merge-0032-native-validation.md).

Branch: `feat-runtime-sol-merge-0063`, targeting **dev**, never main.

Resolves the interrupted merge of `5a4b061a38e1505dc9b3626afe8b6894a15dda8a`
(vertical discovery/local assets) and `53cdb4844d82e7944a5058bb225c5e4291bc75c8`
(pooling/live readiness). Both histories and existing edits are retained. This
is the assigned source/embedding unit, not a claim to fix all eleven gameplay
problems in this checkout.

## Integrated behavior

- Vertical discovery retains all represented absolute 32-block height bands,
  examining every Y in sixteen sampled columns with resumable shared tick budgets
  and overflow/backpressure retention. Source ownership/growth and independent
  per-player projection are unchanged. See [vertical discovery](vertical-discovery-handoff.md)
  for the sampling limits (not an exhaustive cave census).
- The default embedded CPU/JNI provider uses verified existing local/cache native
  v2 GGUF/tokenizer assets. Explicit configuration is authoritative; corruption
  cannot fall back to another asset or service. Model identity is checked before
  and after native allocation, including cancellation/replacement cleanup. Normal
  runtime never provisions weights or connects to an inference endpoint. See
  [asset contract](embedded-model-assets.md) for supported paths and properties.
- The local verified-asset loader and the readiness/pooling provider share one
  native lifetime. Native MEAN sentence output is normalized stably across all
  768 dimensions, never pooled twice, truncated, migrated from v1 or replaced by
  dummy runtime vectors. Landmark composition uses compensated double sums.
- Actual readiness inference and terminal model errors control new discovery
  admission and pending futures/cache invalidation. Persisted item vectors cannot
  bypass that gate or replace biome/structural descriptors. Existing published
  current-profile anchors and independent physical ownership reads remain usable.
  Navigation distinguishes missing anchors from live model initialization/loss.
- The opt-in inference probe uses the same local/cache selection by default;
  explicit download remains probe-only. The integrated missing-asset regression
  verifies the selected path, configuration guidance and no-network diagnostic
  rather than the superseded provisioning error.
- Custom terrain render/collision algorithms are not changed by this integration.

## Checks actually performed on this integration

Java 21, Gradle wrapper 8.13, original tmux session `$11`, origin `%3184`.
No software installed, weights downloaded, other checkouts edited or dev/main
refs changed.

```sh
./gradlew --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0, 30 seconds; all 15 actionable tasks executed. Common/client and
runtime compilation, remapping, `buildSelfTest`, `runtimeSelfTest`, and
`verifyProductionJar` ran. Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Retained pane `%3187`, run `09a124714f604a5990f0db0a3e624196`.

All **17 discovered** main-based suites executed with assertions enabled:

| Suite | Evidence/checks |
| --- | ---: |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddedDiscoveryReadinessSelfTest | 956 |
| EmbeddedEmbeddingSelfTest | 54 |
| EmbeddedNativeOutputTest | 3147 |
| EmbeddedNativeRuntimeSelfTest | Real JNI ABI `b10682-5ea1b124e`, not inference |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessSelfTest | 53 |
| LandmarkEmbeddingPoolingTest | 808 |
| LocalNomicV2ModelAssetTest | 47 |
| NativeEmbeddingOutputTest | 107 |
| NomicV2ModelAssetsTest | 113 |
| PinnedEmbeddingModelSelfTest | 25 |
| VerticalDiscoveryTest | 8900 |
| VerticalSchedulerTest | 43324 |
| DiscoveryReadinessSelfTest | 18 |

Packaging/runner tests cover metadata decoding, 14 descriptor-only and 26
packaging rejection cases, discovery/order/assertions/failure/missing-main/empty
suite handling. Gradle's framework `test` task is intentionally disabled;
`runtimeSelfTest` is the actual runtime suite. Existing annotation/Mixin,
Jackson semver, development SLF4J and Gradle deprecation warnings remain.
Malformed-persistence traces are expected negative fixtures.

## Real blocker / unverified acceptance

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false
```

**BLOCKED/FAIL**, exit 1, six seconds: `NomicV2ModelAssets.AssetException` reports
missing approved weights/tokenizer in the default Ollama manifest/blob and
Mysticism cache locations. Retained pane `%3188`, run
`96f3ed76c2624c7f9e03f81ed5ca0e4d`. This truthful failure is not a successful
forward pass. No substitute weights or external inference server were used.

To unblock, supply the exact **957,680,480-byte** GGUF with SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`, then run
`embeddedInferenceProbe -PembeddingModelPath=/absolute/path/to/exact-v2.gguf`.
Normal runtime accepts `-Dmysticism.embedding.modelPath=/absolute/path/to/exact-v2.gguf`
or the documented existing cache locations; it does not automatically download.

No actual model forward pass, semantic quality/latency, Minecraft/IntelliJ
launch, render-distance performance or cross-platform inference was verified.
Synthetic numerical/model-failure fixtures and real JNI ABI checks do not prove
those behaviors. The code merge/build is verified; real inference and gameplay
acceptance remain blocked/unverified. Other handoff documents record historical
parent checks, not additional executions on this candidate.
