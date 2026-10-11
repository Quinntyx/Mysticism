# Retained unit: native v2 output pooling

## Implemented

`embedding/NativeEmbeddingOutput.java` is a runtime-library-independent output
adapter for the [Nomic v2-MoE model card's Transformers example](https://huggingface.co/nomic-ai/nomic-embed-text-v2-moe#transformers):
attention-mask mean of raw token states, then L2 normalization. All **768** native
coordinates survive. No CLS selection, per-token normalization, v1 layer norm,
Matryoshka truncation, legacy-vector migration or substitute embeddings.

* `fromTensor(float[] values, long[] shape, long[] attentionMask)` consumes a
  row-major single-sample tensor. Hidden states must be `[1,tokens,768]`, with
  `1..512` tokens and the tokenizer's exact, binary, same-length mask. Include
  prompt and special tokens where the tokenizer mask attends them. Padding is
  skipped; it cannot dilute or poison a result. Zero attended tokens, nonfinite
  attended values, cancelling means and malformed shapes fail explicitly.
* An **already pooled** `[1,768]` sentence output uses a **null** mask. Do not
  pass a token mask or pool twice. Do not treat `[tokens,768]` as a sentence batch.
  Select the verified named output of the actual model, not arbitrary output 0.
* Mean accumulation is compensated double precision; normalization scales by
  maximum magnitude before summing squares. Float conversion happens **after**
  normalization. This preserves finite tiny outputs and avoids norm overflow.
  The adapter owns no shared mutable state and does not mutate inputs.
* `normalize(double[])` / `normalize(float[])` require exactly 768 finite
  coordinates and a nonzero result. Returned `Vec384f` has the current profile
  and independent storage. These methods do not authenticate model identity;
  that remains the provider's readiness/profile responsibility.

The active HTTP decoder now normalizes JSON doubles before a float conversion.
Previously a nonzero response such as `3e-300,4e-300` became a zero embedding.
The async engine uses the same normalizer before cloning results into its cache.
The existing `EmbeddingSpace` schema, fingerprint semantics and native dimension
contract are unchanged: this implements the already-declared mean/L2 math.
No ownership, source octree, projection, rendering or collision code changed.

## Integration boundary / real blocker

This unit does **not** restore embedded inference by itself. In this retained
checkout `EmbeddingHelper` still constructs the HTTP `EmbeddingService`, and
`build.gradle` contains no local inference engine. A local provider must load
and validate the approved fresh v2 model/tokenizer, add `search_document: ` once,
reject actual token overflow without silent truncation, and pass its named
hidden-state output plus tokenizer attention mask to `fromTensor` (or its verified
sentence output with null mask). It must preserve readiness, profile verification,
queue/deadline and resource lifecycle contracts. The helper's normalization
cannot fix a wrongly selected output or incorrectly pooled local provider.

No local v2 weights were found in the inspected Hugging Face cache; cached Nomic
v1.5 and nomic-bert are **not** acceptable substitutes and were not used. No
software/weights were installed or downloaded. Server-free IntelliJ launch and
real landmark model quality remain unverified integration work, not passed
checks. The existing HTTP deployment is not proposed as a replacement for that
requirement.

## Checks actually run

Canonical installed Gradle 8.13, Java 21, unchanged build configuration:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build --rerun-tasks
```

The baseline build passed with **four** discovered runtime suites. The new build
passed with **five**, including `NativeEmbeddingOutputTest` (**107 checks**, with
four independent full-coordinate reference checks). The final rerun completed
all **14 actionable tasks**, exit 0. The other suites cover callback isolation,
engine safety, persistence/
network and the HTTP/vector pipeline. Packaging/metadata/descriptor/runner
fixtures, common/client compilation, remapped production jar and
`verifyProductionJar` passed. Expected corrupt-persistence fixtures log errors;
they are deliberately rejected inputs, not a failed task. The forced client
compile reported 98 existing missing-API-Guardian enum warnings and deprecated
API warnings; Gradle also reported deprecated features. None failed the build.

Tests use deterministic **synthetic** tensor fixtures (including production
block/biome/region text labels and a multilingual cave label); they verify
padding invariance and output math at different lengths, **not** actual
tokenization or semantic inference. They exercise prompt/special-token
participation, mean-before-L2, native tail retention, malformed/batched output
rejection, 512-token bounds, finite extreme scales, cancellation, ownership,
cache integration and weighted landmark composition. No game was launched.

Long build commands use `tmux-jobs` in the original session `$11`, origin `%1876`;
canonical slot `%1881` is retained, final build run token
`0ddfc629171e4e5f93abe16b387f5e88`. See the final report for exact candidate HEAD.
