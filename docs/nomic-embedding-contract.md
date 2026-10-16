# Fresh native Nomic v2 embeddings (schema 2)

## Default runtime

`EmbeddingHelper` now selects in-process CPU/JNI inference by default. Java 21
IntelliJ Minecraft launch requires the exact already-provisioned local model,
not an Ollama process or external server. No dummy vectors, v1 migration,
Matryoshka reduction or truncation are permitted. The GGUF contains the tokenizer.

Artifact identity, offline selection order, ownership and verification are in
[embedded-model-assets.md](embedded-model-assets.md). The model is
`nomic-embed-text-v2-moe:latest`, with immutable manifest revision
`ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965`.
The one approved GGUF is 957680480 bytes, SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.

The mod nests `net.ladenthin:llama:5.1.0` with its CPU/JNI resources plus required
Jackson/jspecify dependencies. Minecraft supplies logging; another SLF4J runtime
is not nested. Native extraction uses a private directory, not the shared system
temporary root. No DJL/PyTorch engine or model weights enter the artifact.

## Configuration

Set properties before initialization:

| Property | Default / contract |
| --- | --- |
| `mysticism.embedding.provider` | `embedded`; only explicit `ollama` selects legacy HTTP |
| `mysticism.embedding.modelPath` | exact local GGUF; otherwise documented cache selection |
| `mysticism.embedding.ollamaModels` | optional explicit existing cache root |
| `mysticism.embedding.nativeThreads` | at most 4, leaving one CPU when possible; 1..32 |
| `mysticism.embedding.workers` | 2; 1..32 async workers |
| `mysticism.embedding.deadlineMillis` | 30000; 1..120000 total queue/inference budget |
| `mysticism.embedding.preparationDeadlineMillis` | embedded 900000 / HTTP 30000; 1..900000 |
| `mysticism.embedding.cacheFile` | `cache/mysticism/embeddings-v2.bin`; blank disables persistence |
| `mysticism.embedding.model`, `.revision` | immutable approved model/profile above |

For an explicit offline IntelliJ path:

```text
-Dmysticism.embedding.modelPath=/absolute/path/to/the-approved-v2.gguf
```

No options are needed when a supported default cache already contains that file.
An endpoint alone cannot select HTTP. An intentionally remote deployment may use
`-Dmysticism.embedding.provider=ollama` with `.endpoint` (default
`http://127.0.0.1:11434/`). That legacy provider verifies manifest identity,
requests `truncate:false` and all native coordinates. It is **not** a fallback
when local preparation fails. Missing model/runtime leaves embeddings unavailable
with an actionable cause; it never marks random/cache-only output as ready.

## Profile, vectors and composition

`EmbeddingSpace` is schema 2, descriptor version 2, **768 native dimensions**.
The complete profile includes model, manifest revision, dimension and semantics:
manifest tokenizer, `search_document: ` prefix, mean pooling, full native output
and L2 normalization. Equal dimensions are not compatibility. The SHA256
fingerprint remains derived from this complete contract.

Canonical descriptors have stable registry IDs, human words and sorted bounded
tags. Coordinates, weights and player ownership do not enter descriptor text.
The tokenizer counts prefix and special tokens; input above 512 tokens fails
without truncation. The independent descriptor bound is 8192 Java characters.
Model output must have exactly 768 finite, nonzero coordinates; stable full-vector
normalization preserves the native tail. Extreme finite tensors are not rejected
just because a naive squared-length would overflow/underflow.

`DescriptorVectors.compose` normalizes each positive-weight input, uses scaled
finite weights and compensated double accumulation, then normalizes the complete
mean. Exact cancellation/zero total fails. Huge weights and near-cancellation do
not silently discard valid native components. Caller-owned vectors are copied.

`composeDescriptors` snapshots descriptors and **one engine generation**, then
composes sequentially on its bounded background executor even for all-cache hits.
No descriptor flood or old-generation/new-generation mixture. Cancellation stops
further private composition scheduling, not another subscriber's shared inference.

## Lifecycle and callbacks

- `initializeServer()` returns without joining preparation. Readiness requires
  verified allocation plus a real forward pass, then validated cache restoration.
- `readiness()` returns an independent subscription; cancelling it cannot cancel
  global preparation. `state()` reports initializing, ready, failed or stopped.
- `getEmbedding()` coalesces pending descriptors, uses cloned bounded LRU hits,
  and finishes requests on overload, error, deadline and shutdown.
- Definitive native model failure revokes readiness, invalidates cache/queued
  work and tears down the context. This also applies to failures arriving after
  a request timed out. Mere request deadlines do not permanently fail a healthy
  model. Helper status reads this live state, not an old completed startup probe.
- Accepted subscribers reserve one of 32 process-wide delivery workers through
  synchronous callback execution. Inference, deadline threads and engine monitors
  do not execute accepted pending callbacks. Standard `CompletableFuture` rules
  still apply to **late** attachments, cached terminal futures and cancellation:
  game/render consumers must specify their owning executor with `thenAcceptAsync`
  / `thenRunAsync` / the readiness-feedback helper. No bounded system can accept
  unlimited permanently blocking foreign callbacks; saturation fails explicitly.
- Shutdown clears pending state, rejects new work, starts safe asynchronous
  native teardown, and persists the bounded derived cache outside lifecycle
  monitors. Cache save IO remains synchronous at orderly shutdown, not per tick.

Current-profile disk-cache rules (including stale v1/foreign rejection) are in
[runtime-embedding-cache.md](runtime-embedding-cache.md). World/index/source
geometry ownership, per-player deep/shallow projection and collision/render
geometry are not changed by this native-provider integration.

## Verification

Canonical `build` runs all discovered runtime and packaging suites without
fetching model weights. Main-based fixture tests explicitly distinguish numeric/
asset/lifecycle mocks from real inference. `embeddedInferenceProbe` is a separate
explicit task, not part of ordinary build/check; with the provisioned default
asset, run installed Gradle 8.13 / Java 21:

```text
gradle --no-daemon --console=plain embeddedInferenceProbe
```

Historical merge-0035 evidence used no provisioning/download flags and passed
real inference, native token overflow rejection, default-helper readiness, cache
save/restart preserving all 768 coordinates, and weighted completion on an
explicit client executor with the HTTP endpoint deliberately unusable.
That parent evidence is not verification of merge-0068. Exact historical commands/handles and limitations: [runtime-merge-0035.md](runtime-merge-0035.md).
A native probe is not a Minecraft/GL/gameplay test or an embedding-quality benchmark.
