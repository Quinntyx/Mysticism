# Retained embedded-default unit: validation record

Scope: restore real in-process native v2 inference as the default provider.
Branch: `feat-runtime-embeddings-embedded-default` (target integration: dev, never main).
Existing baseline/history and retained edits were preserved; no other worktrees changed.

## Implemented behavior

- Default local CPU/JNI provider; external Ollama requires explicit `provider=ollama`.
- Lazy worker-only pinned GGUF/tokenizer provisioning and actual readiness inference.
- Full native 768-coordinate mean-pooled, document-prefixed, normalized output.
- Actual embedding tokenizer counts BOS/EOS; overflow is rejected, never truncated.
- SHA256/length validation, cross-JVM publication locking, atomic cache publication,
  cancellation/cleanup, private native extraction, serialized JNI/context lifetime.
- Preparation has its own bounded deadline; failed/late preparation cannot leak or
  revive readiness. Per-request bounds/callback isolation remain intact.
- Packaged JNI/native resources and Java transitives are required by the artifact
  verifier; model weights and duplicate logging jars must not be nested.
- Player projection, octree ownership/growth, terrain collision/rendering and
  native v2 vector/profile policy are unchanged.

## Checks actually performed (2026-10-10)

`./gradlew --no-daemon --console=plain build` — **PASS**, exit 0.

Discovered current suites (all executed, assertions enabled):

1. `AsyncEmbeddingCallbackIsolationTest`: 28 checks.
2. `AsyncEmbeddingEngineSafetyTest`: 184 checks.
3. `EmbeddedEmbeddingSelfTest`: 54 fixture/lifecycle/selection/token-contract checks.
4. `EmbeddedNativeRuntimeSelfTest`: real Linux x86_64 JNI load and build/ABI call
   (`b10682-5ea1b124e`), plus exact native argument constraints; **not inference**.
5. `EmbeddingPersistenceTest`: 90 assertions (expected malformed-state warnings).
6. `EmbeddingPipelineTest`: 135 assertions.
7. `PinnedEmbeddingModelSelfTest`: 25 provenance/loopback/file-integrity checks.

Build suites: metadata decoding, 14 descriptor-only rejections, 26 packaging
rejection cases, runner discovery/order/assertions/failure/missing-main fixtures,
remapped production artifact/resource/native/transitive verification — **PASS**.

`./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=true`
— **BLOCKED/FAIL**, exit 1 after 10m14s: `SocketTimeoutException: Pinned model
provisioning deadline`. Registry transport did not deliver the 957,680,480-byte
pinned file inside the budget. The unpublished partial file was removed. **No
real model forward pass ran**; the optional probe remains available with an exact
preprovisioned file. This probe attempt preceded the final tokenizer/lazy-asset
repairs; the canonical build was rerun successfully after those repairs.

No Minecraft/IntelliJ gameplay launch or cross-platform inference verification
was performed. Initial provisioning needs network/disk/native memory or the exact
preprovisioned asset. Slow downloads can hit the current 10-minute asset budget
(the total embedded readiness budget is 15 minutes); there is no dummy model,
unverified asset acceptance, fallback service or claim of model-quality success.

The retained canonical-build pane/token is `%1671` /
`46e84f6dbce84917a80d5e5ec8c4db92`; the explicit probe pane/token is `%1684` /
`6835b735b779482298c412e49791ebca`, both in original tmux session `$11`.
