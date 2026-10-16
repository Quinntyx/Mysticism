# Embedded resources for IntelliJ / Loom launches

## Normal in-process launch

The default provider is a lazy resource bridge to the built-in JNI Nomic v2
adapter. Loom places `net.ladenthin:llama:5.1.0` and its JSON dependencies on
IntelliJ's runtime classpath; production packages the same dependencies as nested
jars. No Ollama process, separately launched HTTP service, or separately supplied
adapter is required. Initialization, file hashing, resource extraction and real
readiness inference run on `Mysticism-EmbedWorker`, not a game/render tick.

The approved profile remains schema **2**, **all 768 native coordinates**. The
built-in adapter pins the weights and tokenizer in one GGUF:

- Manifest: `ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965`
- GGUF SHA256: `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`
- Exact size: **957680480 bytes**.

Missing/unapproved weights cannot produce readiness or embeddings. There is no
v1 migration, truncation, generated-vector fallback, or automatic HTTP fallback.
**Weights are not checked in. Successful compilation, JNI loading and launch
configuration checks are not proof of real inference.**

## IntelliJ model path (no separate service)

For already provisioned approved weights, put this local Gradle property in your
project/user Gradle properties before reimporting:

```properties
mysticismEmbeddingModelPath=/absolute/path/to/approved-nomic-v2.gguf
mysticismEmbeddingDownload=false
```

A project-relative **Gradle** path is resolved against the checkout and emitted
as an absolute JVM argument. Client, dedicated server and inherited data generation
receive it once; spaces are correctly quoted by Loom. Reimport Gradle in IntelliJ
or generate configurations with:

```sh
./gradlew --no-daemon --console=plain ideaSyncTask verifyEmbeddedLaunchConfiguration
```

Loom preserves existing IntelliJ launch files. When changing properties, check
existing Minecraft run VM options manually; this unit does not overwrite user IDE
configuration. A manual launch uses `-Dmysticism.embedding.modelPath=/absolute/path`.
Java system-property paths **must already be absolute**: Minecraft's `run/` cwd
must never determine which model is used.

Without a model path or bundle, the worker checks the exact pinned blob under
`~/.ollama/models/blobs/sha256-<GGUF SHA256>` (just a file, no service), then
`~/.cache/mysticism/models/sha256-<GGUF SHA256>.gguf`. If absent, readiness fails with the checked paths and approved digest. Asset selection
is read-only: no downloads, model substitutions, or automatic service fallback.
A corrupt existing file is rejected, not replaced. An explicit missing path never
falls through to another cache. Offline operation requires provisioned weights.

Optional Gradle properties forwarded to the client/server JVMs:

| Gradle property | Java system property |
| --- | --- |
| `mysticismEmbeddingBundle` | `mysticism.embedding.bundle` |
| `mysticismEmbeddingModelPath` | `mysticism.embedding.modelPath` |
| `mysticismEmbeddingResourceCache` | `mysticism.embedding.resourceCache` |
| `mysticismEmbeddingDownload` | `mysticism.embedding.download` |
| `mysticismEmbeddingPreparationDeadlineMillis` | `mysticism.embedding.preparationDeadlineMillis` |
| `mysticismEmbeddingNativeThreads` | `mysticism.embedding.nativeThreads` |

Embedded preparation has a separate default 900000 ms deadline (maximum 900000);
ordinary requests retain the 30000 ms deadline. Hashing/initializing a large model
must not inherit a short inference deadline. Timeout/shutdown cancels preparation
and prevents a late native context from reviving readiness. JNI inference is
serialized per provider; native disposal waits for active inference to finish.

## Optional local resource bundle

An explicit `mysticism.embedding.bundle` directory takes precedence over classpath
manifests and the ordinary model path. Otherwise a manifest at
`META-INF/mysticism/embedding/bundle.properties` is discovered on the mod classpath.
Both IDE exploded resources and packaged jars work. Multiple discovered manifests
or an invalid configured/discovered bundle fail closed, never falling back to
another model or download.

A model-only bundle uses the built-in JNI adapter; no SPI implementation is needed:

```properties
bundleSchema=1
profileFingerprint=<EmbeddingProfile.current().fingerprint()>
asset.model=model.gguf
sha256.model=a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
```

The built-in adapter additionally enforces the approved model hash, exact length
and immutable profile. A self-declared transport hash is **not** model approval.
GGUF contains the tokenizer; separate tokenizer assets are not required here.
Bundle use never triggers network provisioning.

Asset roles are identifiers, `model` is mandatory, and all assets declare hashes.
Asset paths are relative and confined: absolute paths, URL schemes, traversal and
symlink escapes are rejected. Directories return verified absolute original files.
File/jar assets extract atomically into a content-addressed native-file cache,
repairing corrupt extracted entries. `mysticism.embedding.resourceCache` selects
an absolute cache directory; the default is under Java's temporary directory.
Extracted files remain for reuse; automatic eviction is not implemented.

The retained optional `EmbeddedRuntimeFactory` SPI supports explicitly provisioned
alternative adapters. Register exactly one implementation in
`META-INF/services/io.github.mysticism.embedding.EmbeddedRuntimeFactory`; list
adapter/dependency jar roles in `runtimeJars` (ordered, comma-separated), or place
them on the game classpath. Mysticism's SPI/vector types remain parent-owned.
TCCL exposes runtime resources during factory creation/open, profile validation,
readiness, inference and close. `bundle.require(role)` returns verified absolute
paths; extracted assets need not preserve their original sibling names.

An adapter must report exactly `EmbeddingProfile.current()`, perform real v2
readiness inference, use `search_document: ` task semantics and mean pooling,
and return full finite, nonzero native 768 vectors. Profile declarations/file
hashes alone do not prove inference. Native dependencies must already be available
for the host platform. Startup failure closes the provider/classloader exactly
once; shutdown does not unload resources underneath active inference.

The legacy HTTP provider remains explicit (`-Dmysticism.embedding.provider=http`),
not a prerequisite or embedded fallback. See [nomic-embedding-contract.md](nomic-embedding-contract.md).

## Verification boundary

Canonical command, including every discovered runtime and packaging suite:

```sh
./gradlew --no-daemon --console=plain build
```

`runtimeSelfTest` discovers resource integrity/confinement tests, directory,
exploded-resource and jar child launches from an unrelated cwd, built-in-adapter
launch failures for missing/corrupt/relative/invalid bundle paths, pinned asset
transport fixtures, provider/output/concurrency/lifecycle fixtures, and real JNI
ABI loading without weights. Fixtures never substitute for production inference.
`verifyEmbeddedLaunchConfiguration` exercises actual Loom run settings and its
IntelliJ XML serializer, plus runtime classpath presence. Check quoting with:

```sh
./gradlew --no-daemon --console=plain \
  '-PmysticismEmbeddingModelPath=build/model with spaces.gguf' \
  '-PmysticismEmbeddingResourceCache=build/resource cache' \
  -PmysticismEmbeddingDownload=false verifyEmbeddedLaunchConfiguration
```

Those are configuration fixtures, not usable models. For real approved weights,
run the **separate opt-in** probe (ordinary builds never fetch weights):

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe \
  -PembeddingModelPath=/absolute/path/to/approved-nomic-v2.gguf
```

The probe verifies the digest, tokenizer, native 768 inference, tail coordinates,
normalization, repeated/concurrent requests and overflow rejection with an unusable
HTTP endpoint. A missing asset must fail honestly.

To exercise the **actual Loom client embedding options** from an unrelated Minecraft
cwd with spaces, use the additional opt-in launch probe:

```sh
# Default local cache discovery: no modelPath or provider override.
./gradlew --no-daemon --console=plain \
  -PmysticismEmbeddingDownload=false embeddedIntellijInferenceProbe

# Explicit model path, resolved exactly as the client run configuration resolves it.
./gradlew --no-daemon --console=plain \
  -PmysticismEmbeddingModelPath=/absolute/path/to/approved-nomic-v2.gguf \
  -PmysticismEmbeddingDownload=false embeddedIntellijInferenceProbe
```

This launches a fresh child JVM on the runtime test classpath using the client run's
embedding JVM arguments (and the same main runtime/dependencies), rebases all classpath
entries before changing cwd, and exercises `EmbeddingHelper` readiness, full native
inference and shutdown/reinitialization twice. It checks full finite 768-coordinate
vectors, nonzero tail, distinct descriptors and cache-copy isolation. It never forces
the provider property: default embedded selection is part of the assertion. Downloads
are forcibly disabled and the HTTP endpoint is deliberately unusable. Bundle/model
validation is the real production code, not a fixture. An invalid bundle fails closed.
The only extra setup is an existing native extraction directory within the probe cwd.
The ordinary build discovers `EmbeddedIntellijInferenceProbeTest` for argv, absolute
classpath, default-provider and offline guards **without loading any model**.

These real probes passed on Linux x86_64 with the authorized exact cache asset; see
[embedded-intellij-validation.md](embedded-intellij-validation.md) for run evidence.
Neither these tests nor a successful probe constitute an IntelliJ GUI or gameplay launch.
