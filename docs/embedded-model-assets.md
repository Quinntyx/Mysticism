# Offline native Nomic v2 model loading

Normal runtime: `EmbeddingHelper` -> `EmbeddingProviders` ->
`EmbeddedEmbeddingService` -> `LocalNomicV2ModelAsset` -> `NomicV2ModelAssets` ->
`LlamaEmbeddingModel`. IntelliJ Java 21 needs no external embedding server.
Runtime preparation is read-only/offline: no automatic model download, software
installer, conversion, substitute model or dummy-vector fallback.

## Exact approved artifact

- Model: `nomic-embed-text-v2-moe:latest`.
- Manifest revision: `ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965`.
- GGUF SHA256: `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
- Complete file length: **957680480 bytes**. Its tokenizer is inside the GGUF.

Both complete-file length and SHA256 are verified **before and after native
allocation**. Selected cache manifests are pinned by exact-byte digest and
rechecked too. Neither a filename, JNI load nor an executor is proof of readiness:
a real native forward pass is required. Other formats, quantizations, v1,
conversions and revisions are not accepted. No migration or dimension reduction.

## Location selection (before initialization)

First explicitly configured choice is authoritative; invalid explicit settings
never fall back:

1. `-Dmysticism.embedding.modelPath=/absolute/path/to/exact-v2.gguf`.
2. `-Dmysticism.embedding.ollamaModels=/existing/models-root`.
3. Environment `OLLAMA_MODELS=/existing/models-root`.

For an explicit cache root the pinned manifest and blob are both required:

```text
<root>/manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest
<root>/blobs/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
```

Without explicit choices, preparation checks `~/.ollama/models` manifest+blob;
if the manifest is absent, it checks that cache's content-addressed blob; if both
are absent, it checks:

```text
~/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf
```

Only genuine absence permits a lower-priority default. Corruption, unreadable
files and bad manifest revisions fail with the cause; there is no disk scan or
HTTP fallback. Missing assets report checked paths and exact required identity.
No VM option is necessary when a default cache already has the approved file.
The production mod nests JNI/Java dependencies, **not model weights**.

## Ownership and latency

Provider construction does no disk IO/native loading. Metadata reads, streaming
hashes, JNI allocation and readiness inference run on the embedding preparation
worker, not a game/render tick. Preparation has its own bounded deadline.
Cancellation is checked during hashing, native progress and ownership transfer;
a late returned context is closed. Asset/file handles close on every path.

One native context is serialized. A request rechecks engine ownership after
waiting for that context, so a queue/inference deadline does not launch stale
JNI work later. Already-running native calls cannot safely be forcibly stopped;
late success cannot refill cache and definitive late model failure revokes the
generation. Model teardown is asynchronous and drains native use before freeing
its context. Expired request and failed model are intentionally different states.

Keep model assets immutable. Before/after verification catches observed changes,
not malicious writers capable of racing both passes. Initialization reads about
1.9 GB for the two hashes, plus native allocation; inference does not rehash the
model per descriptor. CPU/native-memory/platform support remains relevant.

See [the embedding contract](nomic-embedding-contract.md),
[cache behavior](runtime-embedding-cache.md) and
[this candidate's actual checks](runtime-merge-0035.md).
