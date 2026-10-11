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
preparation verifies a configured/cached file, or downloads the pinned blob on
first use. The download is not inference over HTTP and needs no running service.
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
fallback model is used on unsupported platforms. JNI extraction uses a private
temporary directory rather than cleaning a shared system temp root.

## JVM properties (set before mod classes load)

| Property | Default | Purpose |
|---|---|---|
| `mysticism.embedding.provider` | `embedded` | `ollama` explicitly opts into legacy HTTP deployment; an endpoint alone cannot select it |
| `mysticism.embedding.modelPath` | resolution above | Offline/preprovisioned exact GGUF |
| `mysticism.embedding.download` | `true` | Allow first-use pinned blob download; ignored for an explicit modelPath |
| `mysticism.embedding.nativeThreads` | up to 4, leaving one CPU | 1..32 native CPU threads; inference on one context is serialized |
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
