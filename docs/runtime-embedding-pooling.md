# Retained unit: native v2 embedded output

Scope: `feat-runtime-embeddings-embedded-output`, for integration into **dev**, not
main. Only embedding inference/output and its necessary embedded-runtime/build
prerequisite changed. Per-player projection, source-coordinate octrees, terrain
rendering and collision are untouched.

## Existing work preserved and reused

The retained session `01a11c85-79a9-7371-aff7-1ab013faf731` failed at the provider
while inspecting an HTTP-only baseline (`bfbce90`); it left no edits or commits.
Rather than recreate the pinned runtime, this branch preserves the baseline and
cherry-picks the existing embedded prerequisite
`bf1a6714d8d4d2dd407bdf8beb941d94432f25d5` as `da2a675`, and existing output math
`82f59acf87f35d65820e33f7901854a7ba795862` as `c1a603d`. The sole conflict was the
contract documentation: retain the current embedded-default contract, not the
historical HTTP-only/256-dimensional instructions.

## Runtime behavior

- `EmbeddingHelper` defaults to the pinned in-process CPU/JNI provider. No HTTP
  inference or external server is needed; Ollama remains an explicit opt-in.
- The approved v2 GGUF includes weights **and tokenizer**. Its manifest, length
  and SHA256 are verified before native loading; no v1 asset/remapping or fallback
  vectors are accepted. See [deployment contract](nomic-embedding-contract.md).
- The identical `search_document: ` input reaches tokenization and inference.
  Count actual token IDs including special tokens, not a character heuristic.
  Require 1..512 tokens, rejecting overflow before executing JNI: never truncate.
  Reject malformed token-response IDs and unpaired UTF-16, rather than silently
  replacing text during native encoding.
- `LlamaEmbeddingModel` configures MEAN pooling, a full 512-token context/batch,
  one sequence and CPU execution. Its single **already pooled** native sentence
  output is passed to `NativeEmbeddingOutput.fromTensor([1,768], null)`. It is not
  pooled again, reinterpreted as CLS, layer-normalized using v1 math or resized.
- The adapter also supports raw `[1,tokens,768]` states with an exact binary
  attention mask: compensated-double masked mean, then L2. Padding cannot dilute
  or poison the output; invalid shapes/masks, nonfinite attended states, zero and
  cancelling means fail. This raw-tensor API is not the llama.cpp path.
- Normalize with maximum-magnitude scaling in double precision before converting
  to float. Retain all **768** coordinates, including the native tail. Reject
  null, reduced/legacy/wrong-size, nonfinite and zero sentence outputs.
- The async engine applies the same stable normalization as a provider guard and
  caches only successful, current-profile, independently owned vectors. No
  malformed output becomes a cached value, successful readiness or placeholder.
  The declared schema/fingerprint/semantic-space contract remains unchanged.

The normalizer is output math, not proof of model identity or semantic quality.
That proof requires pinned asset verification and an actual model forward pass.

## Checks actually performed in this worktree

Installed Gradle 8.13 / Java 21, canonical command (no exclusions/init overrides):

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build --rerun-tasks
```

**PASS**, exit 0: 15 actionable tasks executed. Common/client/test compilation,
all **9 discovered** main-based runtime suites, metadata/descriptor/packaging and
runner fixtures, remapped production jar and `verifyProductionJar` passed.

| Discovered suite | Actual result |
|---|---|
| AsyncEmbeddingCallbackIsolationTest | 28 checks |
| AsyncEmbeddingEngineSafetyTest | 184 checks |
| EmbeddedEmbeddingSelfTest | 54 fixture/lifecycle checks |
| EmbeddedNativeOutputTest | 3139 provider/output fixture checks |
| EmbeddedNativeRuntimeSelfTest | Real Linux x86_64 JNI load/ABI `b10682-5ea1b124e`; **not inference** |
| EmbeddingPersistenceTest | 90 assertions |
| EmbeddingPipelineTest | 135 assertions |
| NativeEmbeddingOutputTest | 107 synthetic tensor checks |
| PinnedEmbeddingModelSelfTest | 25 loopback/integrity checks; no weights |

The new provider-output test checks every coordinate against an independent
reference at subnormal, small, ordinary and near-maximum scales; task/tokenizer
input equality, actual-limit validation, malformed Unicode/token responses,
cache rejection/recovery, ownership and native landmark composition. Before the
repair the canonical build **failed** in this discovered test because a
zero-token result was allowed to execute inference. After repair it passes.
Fixture outputs exist only in tests, never as production fallback vectors.

Nonfatal warnings: 98 existing API-Guardian/mixin client warnings, Gradle
 deprecations, Jackson annotation version semver warning, duplicate SLF4J providers
 on the test classpath. Persistence corrupt-input fixtures deliberately log
 rejected-state exceptions. None is proof of Minecraft gameplay validation.

## Real inference verification: blocked

```text
/path/to/the/same/gradle --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false
```

Actual result: **exit 1**, `FileNotFoundException: Pinned Nomic v2 GGUF missing`
at `build/embedding-inference-probe/nomic-v2.gguf`. No approved v2 weights were
found in the inspected caches/workspace. No software or model weights were
installed/downloaded; cached v1 assets were not used. Thus **no actual model
forward pass or semantic-quality/gameplay/IntelliJ-launch verification ran**.
The opt-in probe is retained for an exact preprovisioned file, and checks repeated
inference, full native vectors, distinct descriptors, mutable cache isolation,
real tokenizer overflow rejection and default local selection without an HTTP
service. Run with `-PembeddingModelPath=/absolute/path/to/exact.gguf`.

The required GGUF is **957,680,480 bytes**, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
First normal launch must have the exact cached/provisioned file or allow its
pinned download; readiness can fail on network/disk/native-memory/deadline limits.
No external inference server is a substitute or prerequisite. Cross-platform
native execution and actual model output remain unverified here.

## Retained command handles

Original tmux session `$11`, origin `%1951`; all long commands use `tmux-jobs`.

- Red regression build: pane `%1960`, run `8500cb6c4fa94875ad71b03fcfc623f2`, exit 1.
- Green forced canonical build: pane `%1960`, run `23c1065d83a045d1a4517041bbbfd869`, exit 0.
- No-download inference probe: pane `%2000`, run `aeae3a58a3ee4401a86ff245ec4805e3`, exit 1 (missing weights).

See the final report for exact candidate HEAD and the final committed-tree build
handle. The [prerequisite validation record](runtime-embedded-validation.md) is
historical evidence from its original branch, not additional checks performed on
this candidate.
