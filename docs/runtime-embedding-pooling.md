# Retained unit: native v2 pooling and normalization

Scope: `feat-runtime-embeddings-embedded-pooling`, targeting **dev**, never main.
Per-player deep/shallow projection, source-coordinate octree ownership/growth,
custom terrain rendering/collision and persistence policy are untouched.

## Preserved work and dependency integration

The retained session `01a11c85-7cd5-73f6-b358-a79aee9865d3` produced commit
`82f59ac`: native v2 masked pooling and stable output normalization. That commit
is preserved. The earlier HTTP-only integration blocker was rechecked and is
**no longer current**: reviewed embedded-default commit `ca2addc` was integrated
as `64454dd`, and reviewed embedded-output commit `9814794` as `6b6297b`. This
reuses the pinned runtime and connects its actual sentence output to the retained
adapter, instead of creating a second provider or requiring an external server.
Uncommitted composition/probe work was preserved across the interrupted session.

## Runtime math

- `LlamaEmbeddingModel` requests MEAN pooling, one sequence and a full 512-token
  context/batch on CPU. Its already-pooled sentence output enters
  `NativeEmbeddingOutput.fromTensor(values, [1,768], null)`: no second pooling,
  CLS selection, v1 layer normalization, resizing or Matryoshka truncation.
- The adapter also accepts raw `[1,tokens,768]` hidden states with an exact binary
  tokenizer attention mask. Compensated-double masked mean precedes L2. Padding
  cannot dilute or poison the result; prompt/special tokens follow the mask.
  This raw-tensor interface is not the llama.cpp sentence-output path.
- Stable L2 divides by maximum magnitude before squaring in double precision.
  Conversion to float occurs after normalization. Null/wrong-size/nonfinite/zero
  vectors, malformed/batched tensors, invalid masks and zero/cancelling means
  fail explicitly. All **768** coordinates survive, with independently owned
  output storage. The async cache applies the same normalizer.
- `DescriptorVectors.compose`, used by source landmark composition, normalizes
  each contributor using that same native normalizer. It scales finite positive
  weights by their maximum instead of adding a potentially overflowing total.
  The common mean divisor cancels in L2. Neumaier-compensated double sums survive
  cancellation of large opposite contributors without first casting to float.
  Exact zero still fails; a finite nonzero residual is not rejected by an
  arbitrary `1e-12` threshold. Zero-weight observations do not contribute.
- The GGUF's own tokenizer sees the identical `search_document: ` input used by
  inference, including special tokens. Actual overflow is rejected, not silently
  truncated. Pinned model/tokenizer identity and fresh schema-2 native profile
  remain required. No substitute model, placeholder vector or v1 migration exists.

See [deployment contract](nomic-embedding-contract.md) for the exact model asset
and server-free default. Output math does not authenticate model identity or
establish semantic quality.

## Checks actually performed in this checkout

Installed Gradle 8.13 / Java 21, canonical command, no exclusions/init overrides:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0: all 15 actionable tasks executed. Common/client/runtime-test
compilation, all **10 discovered** runtime suites, metadata/descriptor/packaging
and runner fixtures, remapped production jar and `verifyProductionJar` passed.
The Gradle framework `test` action is intentionally disabled; `check` runs the
main-based `runtimeSelfTest` runner, which actually discovered the new suite.

| Discovered suite | Actual result |
|---|---|
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedEmbeddingSelfTest | 54 fixture checks |
| EmbeddedNativeOutputTest | 3139 provider/output fixture checks |
| EmbeddedNativeRuntimeSelfTest | Real JNI load/ABI `b10682-5ea1b124e`; **not inference** |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| LandmarkEmbeddingPoolingTest | 808 numeric composition checks |
| NativeEmbeddingOutputTest | 107 synthetic tensor checks |
| PinnedEmbeddingModelSelfTest | 25 loopback/integrity checks; no weights |

Before the composition repair, the canonical build failed in the newly discovered
`LandmarkEmbeddingPoolingTest`: `Positive finite total weight required` for
individually finite weights whose sum overflowed. After repair it passes. New
coverage also checks common-weight scaling (subnormal through near-maximum),
tiny residuals, same-coordinate compensated cancellation, all 768 coordinates
against an independent randomized reference, repeatability, native tail retention,
exact-zero rejection and ownership. Text labels are canonical block/biome and
multilingual cave descriptors; these **numeric fixtures are not tokenization or
real landmark semantic inference**.

The expanded opt-in real-weight probe covers canonical block, biome, region and
multilingual cave text; independent all-coordinate L2 comparison; and repeated
inference after other texts/lengths to expose stale native state. Those assertions
are compiled, but did not execute because asset resolution failed first.

Nonfatal build warnings: 98 existing API-Guardian/mixin client warnings,
Jackson annotation version semver warning, duplicate SLF4J providers and Gradle
deprecations. Persistence corruption fixtures deliberately log rejected inputs.

## Fresh inference recheck: blocked by missing approved weights

```text
/path/to/the/same/gradle --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false
```

**Exit 1**, `FileNotFoundException: Pinned Nomic v2 GGUF missing` at
`build/embedding-inference-probe/nomic-v2.gguf`. Rechecked the exact Mysticism
cache and Ollama blob paths: absent. The inspected Hugging Face cache contains
v1.5/nomic-bert, not the approved v2 asset. No model/software installation,
configuration changes, substitute assets or external inference service were used.
Network/authentication recovery alone cannot supply a missing preprovisioned file.

Required GGUF: **957,680,480 bytes**, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
To verify when available, pass `-PembeddingModelPath=/absolute/path/to/exact.gguf`
to the probe, or deliberately authorize its pinned download. Normal runtime
supports first-use pinned provisioning; this validation did not enable it.
**No actual model forward pass, semantic-quality, Minecraft gameplay or IntelliJ
launch verification is claimed.** Cross-platform native execution is unverified.

## Retained command handles

All long commands use `tmux-jobs`, original session `$11`, origin `%2309`.

- Red canonical regression: pane `%2328`, run `dda68f89bcba47f88c343d6fc8d69a36`, exit 1 (historical output captured before pane loss).
- Recovered forced canonical build: pane `%3125`, run `364c4582f91d4b99bb5a272a17a04c0e`, exit 0.
- Recovered no-download inference probe: pane `%3131`, run `1998aba45b034ec0bba0213c2b3c67a9`, exit 1 (missing exact v2 weights).

Earlier interrupted panes disappeared; their completion was not invented. Current
handles retain the rechecked results above. See final report for candidate HEAD.
