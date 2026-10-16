# Retained source discovery / native v2 integration

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

Revalidated after the user provisioned the exact approved model asset. The prior
missing-asset blocker is resolved; no substitute model or dimensions were used.
Both original parent tips above remain ancestors of the candidate.

Java 21, Gradle wrapper 8.13, original tmux session `$11`, origin `%3575`.
No software installed, weights downloaded by this work, other checkouts edited
or dev/main refs changed.

```sh
./gradlew --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0, 30 seconds; all 15 actionable tasks executed. Common/client and
runtime compilation, remapping, `buildSelfTest`, `runtimeSelfTest`, and
`verifyProductionJar` ran. Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Final retained pane `%3589`, run `b8058db2899b47239db5f4a5cb4fa472`.
An earlier full rerun also passed in 33 seconds (pane `%3580`, run
`501ebb2d9b78467bb1553bc23b288cc1`). Both ran all 17 suites; the final
rerun compiled the exact expanded probe source verified below.

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

## Real native v2 inference: prior blocker resolved

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false
```

**PASS**, exit 0, 16 seconds. Retained pane `%3583`, run
`dd6cd5a97d3446499012e992e91647f3`. The default read-only cache selection loaded
`/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf`:
**957,680,480 bytes**, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
This is real CPU forward-pass evidence with JNI `b10682-5ea1b124e`, not synthetic
inference or a network endpoint. The probe does not provision weights and sets
an unusable legacy endpoint to demonstrate the default embedded path ignores it.

The same expanded probe also **PASSED**, exit 0, 15 seconds, with the explicit
local-path override:

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false \
  -PembeddingModelPath=/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf
```

Retained pane `%3595`, run `67440db10c284fe58f02985baae6c58e`; exact same
approved model, dimensions, shared lifecycle and coordinate checks, no download.

The expanded opt-in probe covers:

- Canonical block/biome/dimension descriptors and multilingual cave text. Every
  one of 768 native MEAN output coordinates agrees with an independent L2
  reference; repeat inference after different-length texts remains stable.
- Real tokenizer overflow detection and engine rejection (no truncation),
  distinct nonzero normalized fresh-v2 outputs, cache mutation isolation and
  successful inference-backed asynchronous readiness.
- Actual shared `EmbeddingHelper.initializeServer` and default provider/cache
  resolution, real readiness releasing discovery queue work, physical-read
  independence and no fabricated source navigation anchor.
- Production cave-landmark weighted composition against an independent
  double-precision reference for **all 768 coordinates**, repeated composition
  isolation and shared shutdown rejecting fresh discovery/cached inference.
  Shutdown is not an induced native fault; terminal model faults remain covered
  by the default build's failure fixtures.

Observed cosine oak/birch `0.86688715`, oak/lava `0.8003141` is diagnostic only,
not evidence of corpus-level semantic quality. Native warnings about absent
newline/mask tokens, CPU-only GPU availability and unsupported speculative
context remain; the exact approved tokenizer nevertheless passed these real
text, overflow, repeatability and output-shape checks. No conversion or model
substitution was performed to silence those warnings.

Normal runtime accepts `-Dmysticism.embedding.modelPath=/absolute/path/to/exact-v2.gguf`
or documented existing cache locations; no external inference server is needed.
Missing/corrupt/explicit-override failure regressions still run independently of
whether this machine has cached weights. Ordinary `build` remains offline with
respect to model assets; the real-model probe is explicitly opt-in, not a
silently skipped default suite.

No Minecraft/IntelliJ launch, world/chunk gameplay, render-distance performance,
corpus semantic-quality/latency acceptance or cross-platform inference was
verified. Real queue/navigation-policy checks do not simulate world ownership
or validate the eleven-problem gameplay request in full. The assigned combined
source/embedding unit has no remaining observed model-asset blocker. Other
handoff documents record historical parent checks, not additional executions
on this candidate.
