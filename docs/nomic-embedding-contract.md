# Fresh native Nomic v2 embeddings

## Normal runtime: in-process CPU inference

IntelliJ's Java 21 Minecraft launch needs **no Ollama process or external server**.
`EmbeddingHelper` selects `EmbeddedEmbeddingService` by default. JNI, llama.cpp,
and their Java dependencies are nested in the production mod jar and are on the
Gradle development classpath. There is no DJL/PyTorch runtime, installer, dummy
vector fallback, v1 migration, or dimensional truncation.

The immutable approved model/tokenizer is the **exact GGUF from the v2-MoE
manifest**, not a different conversion or a moving Hugging Face/Ollama tag:

- Model identity: `nomic-embed-text-v2-moe:latest`
- Manifest SHA256: `ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965`
- GGUF SHA256: `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`
- GGUF length: **957,680,480 bytes**
- Source: `https://registry.ollama.ai/v2/library/nomic-embed-text-v2-moe/blobs/sha256:a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`

Provider construction does no asset IO/native loading. On the embedding worker,
preparation first validates the desktop/JVM (including Linux x86_64 kernel
CPU/OS ISA flags before any JNI execution) and loads the bundled CPU JNI ABI,
then verifies a configured/cached file, or downloads the pinned blob on first use.
Unsupported platforms, broken native dependencies and extraction permissions fail
before a gigabyte of weights can be downloaded. The download is not inference over HTTP and needs no running service.
Both length and SHA256 are checked before native loading. Publication is atomic,
concurrent JVMs share a file lock, incomplete downloads are removed, corrupt
existing files are **not** replaced, and cancellation/deadlines are enforced.
Readiness includes an actual model forward pass; an executor/file flag is not
readiness. Startup does not join preparation. Restart to retry failed readiness.

Resolution order:

1. Explicit `mysticism.embedding.modelPath` (exact GGUF; never downloaded/overwritten).
2. Existing `~/.ollama/models/blobs/sha256-<GGUF SHA256>` (weights only; no Ollama process).
3. `~/.cache/mysticism/models/sha256-<GGUF SHA256>.gguf` (download if missing, unless disabled).

First use therefore requires internet access **or** a preprovisioned exact file,
about 958 MB disk space, and sufficient native memory. Build/test never downloads
model weights. Native runtime selection/CPU support is platform-dependent; no
fallback model is used on unsupported platforms. The pinned CPU library is
SHA256/length-verified and loaded from a private per-JVM directory underneath
`~/.cache/mysticism/native` by default, avoiding common noexec system-temp mounts.
Upstream JNI initialization/cleanup is confined to an empty private scratch
subdirectory, even during recursive JNI_OnLoad initialization. An installed
`jllama` in `java.library.path` cannot replace the bundled runtime. See
[desktop native support and diagnostics](embedded-desktop-runtime.md).

## JVM properties (set before mod classes load)

| Property | Default | Purpose |
|---|---|---|
| `mysticism.embedding.provider` | `embedded` | `ollama` explicitly opts into legacy HTTP deployment; an endpoint alone cannot select it |
| `mysticism.embedding.modelPath` | resolution above | Offline/preprovisioned exact GGUF |
| `mysticism.embedding.download` | `true` | Allow first-use pinned blob download; ignored for an explicit modelPath |
| `mysticism.embedding.nativeThreads` | up to 4, leaving one CPU | 1..32 native CPU threads; inference on one context is serialized |
| `mysticism.embedding.nativeDirectory` | `~/.cache/mysticism/native` | Writable local execution-enabled root for private CPU JNI extraction; never a library override |
| `mysticism.embedding.workers` | `2` | 1..32 async workers |
| `mysticism.embedding.deadlineMillis` | `30000` | 1..120000 total queue + inference deadline |
| `mysticism.embedding.preparationDeadlineMillis` | `900000` embedded / `30000` HTTP | 1..900000 preparation budget, separate from request deadlines |
| `mysticism.embedding.model` | v2 identity above | Compatibility identity, **not** an arbitrary local model selector |
| `mysticism.embedding.revision` | manifest above | Full immutable manifest digest |

An offline IntelliJ VM-options example:

```text
-Dmysticism.embedding.modelPath=/absolute/path/to/the-pinned-v2.gguf -Dmysticism.embedding.download=false
```

No options or server process are needed when automatic provisioning is allowed.
Client/server model and revision must agree, because they determine the vector
fingerprint. Clients do not execute inference for authoritative server state.

For an intentionally remote deployment use `-Dmysticism.embedding.provider=ollama`
and optionally `-Dmysticism.embedding.endpoint=http://127.0.0.1:11434/`.
That provider verifies the manifest via `/api/tags`, submits `/api/embed` with
`truncate:false`, verifies the response identity, and requires all 768 native
coordinates. It is not an automatic fallback when local preparation fails.

## Semantic/lifecycle contract

`EmbeddingSpace` remains schema 2, descriptor version 2, **768 native dimensions**.
The exact GGUF tokenizer counts `search_document: ` plus descriptor and special
tokens. More than 512 tokens fails explicitly before inference. Descriptors must
be nonblank and at most 8192 Java characters; this is a separate input bound, not
a token estimator. The provider uses mean pooling and L2 normalization, retains
coordinate 767, and rejects missing/nonfinite/zero/wrong-sized outputs. Model and
tokenizer identity, pooling, prefix, dimension, normalization, and descriptor
version participate in the existing fingerprint. Equal dimensions do not imply
compatible semantics. No v1 migration or first-256 reduction is performed.

`Vec384f` and related classes retain legacy Java names only. Returned vectors,
cache results and compositions are independently owned. `EmbeddingHelper` keeps
its existing asynchronous API: `initializeServer`, `readiness`, `getEmbedding`,
`composeDescriptors`, `isReady`, `getInitializationStatus`, `shutdown`, `profile`.
Requests before readiness fail, and failures stay explicit. The bounded async
engine preserves callback isolation, copied caching, deduplication, and request
deadlines. JNI calls and context release cannot race; shutdown cancels pending
work without waiting on a native call and releases any eventual context on its
owner worker. Independent player projection, source-coordinate octree ownership,
collision/rendering and persistence policy are not changed by this provider.

## Reproducible checks

```sh
./gradlew --no-daemon --console=plain build
```

The canonical build discovers **all** current main-based runtime suites and runs
packaging/verifier suites. Embedded tests cover default selection, lifecycle,
serialized inference/context release, late-load cancellation, actual token-limit
contract, full-coordinate retention, pinned provenance, local HTTP/file integrity,
atomic cache reuse and bundled JNI ABI loading. Fixture vectors exist only inside
tests; these checks are not a real model forward pass or a Minecraft launch.

Real inference is a separate explicit task, not a silently skipped test:

```sh
# Existing exact GGUF (offline):
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingModelPath=/absolute/path/to/exact-v2.gguf
# Explicitly download the pinned weights into this checkout's ignored build directory:
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=true
```

The probe verifies the SHA256/size, runs actual JNI tokenizer/forward passes,
checks repeated inference, nonidentical descriptor outputs, all 768 coordinates,
normalization, async readiness, cache ownership and real token-overflow rejection.
Its endpoint is intentionally unreachable. It reports observed cosine values,
not Minecraft-corpus quality, gameplay verification, or cross-platform results.

## Retained preprocessing and profile details

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
