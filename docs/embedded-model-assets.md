# Offline native Nomic v2 model assets

`NomicV2ModelAssets` is the model-loading boundary for an **in-process GGUF
backend**. It opens and verifies real local weights/tokenizer bytes; it neither
creates vectors nor initializes an inference runtime. No model download, HTTP
request, Ollama process, installation, conversion, v1 migration, truncation or
fallback occurs in this loader.

**Integration limit in this checkout:** `EmbeddingHelper` still initializes the
HTTP `EmbeddingService`. There is no native inference backend here. Merely
setting the options below will **not** make the current Minecraft launch run
inference offline. The backend must use this boundary, implement
`EmbeddingProvider`, and replace that initialization path. It must support
Nomic v2-MoE GGUF, the embedded tokenizer, document task prefix, mean pooling,
512-token overflow rejection, all native 768 coordinates and L2 normalization.
Its `checkReady()` must perform a real inference probe. Finding/opening an asset
is NOT readiness. No actual model inference or game launch was performed for
this unit.

## Supported assets and exact identity

The approved profile is schema 2, native 768, model
`nomic-embed-text-v2-moe:latest` (the tagless spelling is also accepted), with
manifest revision:

```text
ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965
```

The [official registry manifest](https://registry.ollama.ai/v2/library/nomic-embed-text-v2-moe/manifests/latest)
identifies its single `application/vnd.ollama.image.model` GGUF layer as:

```text
SHA256: a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
Bytes:  957680480
```

The GGUF includes its tokenizer. The loader pins the **entire file**, not just
its name, header or a tensor prefix. An explicit GGUF must match exactly the
same digest/size as the cache's blob. HF safetensors, ONNX exports, other GGUF
quantizations, v1, renamed models and arbitrary revision overrides are not
supported by this pin. Supporting another verified artifact requires an
explicit reviewed profile/artifact decision; a filename/property override is
not sufficient. No claim is made about compatibility with every GGUF runtime.

## Location selection (before mod classes load)

The first configured choice is authoritative; a missing/broken explicit choice
**never** falls back to another location:

1. `-Dmysticism.embedding.modelPath=<existing-gguf-file>`
2. `-Dmysticism.embedding.ollamaModels=<existing-models-directory>`
3. Environment variable `OLLAMA_MODELS=<existing-models-directory>`
4. `${user.home}/.ollama/models`

Cache mode reads only:

```text
<models-directory>/manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest
<models-directory>/blobs/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
```

It checks the bounded manifest's exact-byte SHA256 revision, schema/model-layer
identity and size before opening the blob. The manifest's machine-specific
`from` field is never used as a file path. Ollama need not be installed/running
on the launch machine if those already-provisioned assets have been copied
there. Neither unused config blobs nor service endpoints are consulted.
Relative paths resolve against the JVM working directory. Symlinks resolve to
the real file, whose full content must still match. Blank/invalid settings are
configuration errors, not an invitation to scan the disk or contact a server.
Permission errors retain their real IO cause instead of being called missing.

## Backend API and lifecycle

```java
// Blocking IO: run on the embedding initialization worker, NEVER a game tick.
var nativeModel = NomicV2ModelAssets.loadConfigured(verifiedAsset -> {
    // Load with your real in-process backend from verifiedAsset.path().
    // This is a contract example, not an inference implementation.
    return backend.loadNomicV2(verifiedAsset.path());
});
// The caller owns/ultimately closes nativeModel and performs real readiness.
```

`openConfigured()` / `open(Location)` instead return a closeable `OpenedModel`
with the verified real path, byte count, revision, digest and read-only
positional byte access. Close it on every path. No publicly writable channel is
exposed. `loadConfigured(loader)` / `load(Location, loader)` verify before
allocation, call the synchronous backend loader, then re-open and re-verify the
selected manifest/model before transferring backend ownership. A returned
backend is closed if post-load verification fails; cleanup errors are suppressed
behind the primary failure. A backend whose constructor throws must clean up
its own partial native allocations. The temporary `OpenedModel` is always
closed: the backend must not retain it or defer loading until after the callback.

Keep provisioned assets immutable during a session. Before/after verification
rejects observed file replacements and retagging, but is not a security boundary
against hostile concurrent filesystem writers. Two complete hash passes during
native initialization cost disk/CPU time; do not run them on ticks or in per-text
inference. The runtime owner must handle initialization deadlines/cancellation
and free native resources. Asset exceptions say what is absent, wrong, corrupt
or unsupported and do not assert readiness.

## Regression coverage and verification boundary

`NomicV2ModelAssetsTest` is discovered by the existing `runtimeSelfTest` main
runner through `src/test/java/io/github/mysticism/embedding`. It uses small
synthetic **file-format** fixtures and closeable lifecycle fixtures, not model
weights, dummy embeddings or a live service. Tests cover location precedence,
missing/invalid/partial assets, permission failures where enforced by the OS,
exact manifest and whole-file digests (including multi-buffer corruption),
malformed/path-injecting layers, native-v2 profile rejection in separate JVMs,
read-only access/closure, and backend cleanup on replacement/retagging/failure.

Canonical verification (cached Gradle 8.13, Java 21):

```sh
./gradlew --no-daemon --console=plain build runtimeSelfTest buildSelfTest verifyProductionJar
```

This command passed (exit 0, 13 seconds): all **5 discovered main-based suites**
ran, including **113 checks** in `NomicV2ModelAssetsTest`. Packaging/descriptor/
runner-discovery self-tests and verification of the remapped production jar
passed. No suite exclusions, classpath overrides or model downloads were used.
The real default-location `openConfigured()` probe separately exited 2 with
`Missing Nomic v2 cache manifest` at
`/home/zlare/.ollama/models/manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest`.
It did not fabricate assets or attempt a server connection.

The Gradle runtime runner and production-jar checks cover discovery/packaging;
they do not verify actual native loading, inference semantics, IntelliJ startup
or gameplay. Those remain blocked here by the absent native backend and approved
local weights. The existing [embedding contract](nomic-embedding-contract.md)
describes the HTTP provider and the shared profile/persistence APIs; some of
its historical build/deployment notes predate the current native768 code.
