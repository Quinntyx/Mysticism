# Offline native Nomic v2 model loading

The normal Minecraft path is now **in-process CPU/JNI inference**:
`EmbeddingHelper` -> `EmbeddingProviders` -> `EmbeddedEmbeddingService` ->
`LocalNomicV2ModelAsset` -> the retained `NomicV2ModelAssets` verifier ->
`LlamaEmbeddingModel`. IntelliJ needs no Ollama process/external server. The
reviewed default-provider implementation (`ca2addcb52dc1bd3b46058045911c1e9cd8ba221`)
was integrated while retaining this branch's asset-loader commit and regressions.
No player projection, octree ownership, terrain/collision or vector-space policy
was changed by this integration.

Normal preparation is **read-only and offline**. It never downloads weights,
installs software, contacts a service, converts models, substitutes vectors,
migrates v1, or reduces native dimensions. An endpoint property alone cannot
select HTTP; that requires explicit `mysticism.embedding.provider=ollama`.
Missing/corrupt local assets fail readiness; HTTP is not a fallback.

## Supported assets and exact identity

The approved profile is schema 2, native 768, model
`nomic-embed-text-v2-moe:latest` (tagless spelling also accepted), with manifest:

```text
ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965
```

The [official registry manifest](https://registry.ollama.ai/v2/library/nomic-embed-text-v2-moe/manifests/latest)
identifies its single `application/vnd.ollama.image.model` GGUF layer as:

```text
SHA256: a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
Bytes:  957680480
```

This GGUF includes its tokenizer. The verifier pins the **entire file**, not
just its name, header, first tensor, or a cache flag. HF safetensors, ONNX,
different quantizations/conversions, v1 and arbitrary revisions are unsupported.
Another artifact needs an explicit reviewed profile/artifact decision. JNI
loading alone is not proof the runtime supports this actual MoE model.

## Runtime location selection (set before initialization)

The first explicitly configured choice is authoritative. Missing, invalid,
unreadable or corrupt explicit assets **never** fall back:

1. `-Dmysticism.embedding.modelPath=<existing-exact-gguf>`
2. `-Dmysticism.embedding.ollamaModels=<existing-models-directory>`
3. Environment `OLLAMA_MODELS=<existing-models-directory>`

For an explicit cache root, both the pinned manifest and its blob are required:

```text
<models-directory>/manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest
<models-directory>/blobs/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
```

Without explicit settings, the preparation worker checks:

1. `${user.home}/.ollama/models` manifest + blob, if the manifest exists.
2. Its existing content-addressed `blobs/sha256-<GGUF SHA256>` when the manifest
   is absent. A copied pinned blob needs no moving tag/service; full content
   digest/length verification remains mandatory.
3. `${user.home}/.cache/mysticism/models/sha256-<GGUF SHA256>.gguf` when neither
   default Ollama asset exists.

Only genuine absence permits a lower-priority **default** cache. A present
bad manifest/blob or permission failure is an error, not permission to mask it
with another file. Missing default assets report all checked paths plus exact
identity and provisioning options. No disk scan or HTTP request occurs.
`mysticism.embedding.download` is not a runtime provisioning option. The
separate explicit probe's download flag is not part of Minecraft initialization.

Cache manifests have bounded reads and exact-byte SHA256 revision checks;
schema, model-layer count/identity and length are validated. The manifest's
machine-specific `from` field is ignored. Only these selected assets are read;
unneeded config blobs and service endpoints are not consulted. Relative paths
resolve against the JVM working directory. Symlinks resolve to a real file
whose whole content must still match. Blank/invalid settings fail explicitly;
permission errors preserve their actual IO cause instead of being called missing.

IntelliJ Java 21 VM option for an offline, already-provisioned exact file:

```text
-Dmysticism.embedding.modelPath=/absolute/path/to/the-approved-v2.gguf
```

No VM options are necessary if a supported default cache already contains the
approved assets. The mod packages JNI/Java dependencies, **not model weights**.

## Asset/native ownership and readiness

Constructor/provider selection does no disk IO, path parsing or JNI loading.
Configuration, metadata checks, streaming SHA256, native allocation and readiness
inference run on the embedding preparation worker, not a game/render tick.

`NomicV2ModelAssets.load(Location, ModelLoader, BooleanSupplier)` verifies the
selected manifest/model, invokes the synchronous native loader, then reopens
and verifies the same selected assets again before transferring ownership. A
returned context is closed if the file is replaced/retagged or cancellation
occurs; cleanup exceptions are suppressed behind the primary failure. A failing
native constructor must free its partial allocations. Temporary verified file
channels close on every path. Shutdown/timeout cancellation is checked between
hash buffers, before allocation, during native progress and after allocation.
No returned late context can become ready.

The retained `loadConfigured(loader)` / `load(Location, loader)` and
`openConfigured()` / `open(Location)` APIs remain available. The low-level
`configuredLocation()` retains the explicit-or-default-Ollama selection API;
`LocalNomicV2ModelAsset` adds the worker-only default content-addressed/app-cache
search used by the provider. `OpenedModel` exposes only real path, identity,
length and read-only positional access; the backend must not retain that
short-lived handle or defer native loading beyond the callback.

The provider's readiness probe executes actual inference after verified native
allocation. Finding a file, loading JNI or creating an executor is not readiness.
The GGUF tokenizer includes the document prefix and special tokens; >512 tokens
fails without truncation. Mean pooling, all 768 coordinates, nonzero finite
output and L2 normalization remain mandatory. No fallback model/vector exists.

Keep provisioned assets immutable. Before/after checks catch observed changes,
not hostile writers capable of racing both passes. Two whole-file hashes cost
about 1.9 GB of read IO per initialization; they do not run per descriptor.
Native memory, supported CPU/platform and real MoE compatibility still need
verification with the actual weights.

## Regression coverage and historical verification

The following records this imported parent's earlier checks and missing-asset
failure, not current combined-candidate status. The authorized exact GGUF is now
provisioned; see [merge-0032 validation](runtime-sol-merge-0032-native-validation.md)
for candidate-specific real-model and regression evidence.

The canonical check is:

```sh
./gradlew --no-daemon --console=plain build
```

It runs **all 9 discovered current main-based runtime suites**, assertions enabled,
plus packaging/descriptor/runner-discovery tests and remapped production-jar
verification. `NomicV2ModelAssetsTest` retains **113** offline file-format/ownership
checks. `LocalNomicV2ModelAssetTest` adds **47** checks covering supported cache
selection, authoritative explicit roots, complete pre/post-allocation hashes,
replacement cleanup, cancellation before/after allocation, no inference on
failed assets, and the actual default provider's failed async readiness with
missing approved weights and zero calls to a loopback service endpoint.
Fixtures are synthetic files/closeable resources/test-only vectors, **not model
weights or real forward passes**. `EmbeddedNativeRuntimeSelfTest` really loads
Linux x86_64 JNI and calls build/ABI `b10682-5ea1b124e`, but does not load a model.

An opt-in real-weight probe is available independently of build/tests:

```sh
# Normal read-only local/cache search; explicitly fails if weights are absent:
./gradlew --no-daemon --console=plain embeddedInferenceProbe
# An already-provisioned exact GGUF:
./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingModelPath=/absolute/path/to/exact-v2.gguf
```

`-PembeddingProvision=true` separately authorizes this probe (not runtime) to
fetch the pinned blob into the checkout's ignored build directory. It was **not
used in this unit**. The [imported default-provider validation record](runtime-embedded-validation.md)
is historical reference evidence, not this candidate's checks.

Final canonical build (2026-10-15): **PASS**, exit 0, 1m19s. All nine suites
above actually ran (not merely compiled); `buildSelfTest` and
`verifyProductionJar` passed. Retained original-session `$11` pane `%3123`, run
`e6a62b3982624717969630f513ca5bbf`. The prior interrupted rerun's missing pane
is not relied on as evidence. An earlier captured canonical build also passed
in 3m36s before the final probe-command refinements.

`./gradlew --no-daemon --console=plain embeddedInferenceProbe`: **BLOCKED/FAIL**,
exit 1, 6s. The actual runtime local/cache resolver reported
`Missing approved native v2 weights/tokenizer`, listing the default manifest,
blob and application-cache paths under `/home/zlare`, the expected digest/size,
and explicit provisioning options. Retained pane `%3124`, run
`e2e15923b67a4c529658a1aab01c14b7` in original session `$11`. A fresh filesystem
check also confirmed all three supported default assets absent; `OLLAMA_MODELS`
is unset. No model weights were downloaded, no external service was contacted,
and **no real model forward pass ran**.

The earlier native-backend dependency blocker is removed in source/package,
and the real Linux JNI build/ABI check passed. The actual MoE model's native
loading/inference, IntelliJ launch and gameplay remain **unverified**, blocked
by the absent approved local weights. Static packaging and fixture successes
are not proof of native model compatibility or inference quality.
