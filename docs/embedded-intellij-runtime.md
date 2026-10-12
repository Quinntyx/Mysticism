# Embedded resources for IntelliJ / Loom launches

## Delivered wiring and remaining blocker

The launch/resource unit defaults `EmbeddingHelper` to an **in-process** provider.
It never launches or falls back to Ollama, HTTP, a model download, v1 weights,
Matryoshka truncation or generated vectors. Model hashing, extraction, runtime
classloading and readiness execute on `Mysticism-EmbedWorker`, not the Minecraft
startup/tick thread. The current schema stays **2, native 768 dimensions**.

**This checkout does not contain a native v2 inference implementation or model
weights.** It supplies a verified-resource resolver and an `EmbeddedRuntimeFactory`
SPI, not a working replacement inference engine. An administrator must provision
an approved native v2 runtime adapter and its model/tokenizer/native dependencies.
A missing bundle or adapter fails readiness explicitly; it does not pretend that
resource discovery is model readiness. A successful Gradle build does not remove
that deployment blocker. Cached v1/v1.5 models must not be used to satisfy it.

## IntelliJ provisioning (no separate service)

An already provisioned directory contains `bundle.properties` and its assets.
Add this **local Gradle project property** before reimporting the project:

```properties
mysticismEmbeddingBundle=/absolute/path/to/provisioned-v2-bundle
```

A project-relative value is also allowed here: Gradle resolves it against the
checkout and emits an **absolute**, correctly quoted JVM argument. Minecraft's
`run/` working directory is never used to guess model locations. Both the client
(integrated server) and dedicated-server configurations receive the property.
Data generation inherits it from the client without duplicating it.

Reimport Gradle in IntelliJ, or generate Loom 1.10.5's run configurations with:

```sh
./gradlew --no-daemon --console=plain ideaSyncTask verifyEmbeddedLaunchConfiguration
```

Loom preserves existing IntelliJ launch files. When changing the bundle location,
check/edit the existing Minecraft run configuration's VM options to contain one
`-Dmysticism.embedding.bundle=/absolute/path` argument (quote the whole argument
if the path contains spaces). This unit does not overwrite user IDE configuration.
For a manually configured launch, set that same Java system property directly;
**Java system-property paths must already be absolute**.

Alternatively put a manifest at
`META-INF/mysticism/embedding/bundle.properties` on the mod classpath, with assets
beside it. This works with IDE exploded resources and a packaged jar. It does not
require a checkout or service running beside Minecraft. Multiple discovered
manifests fail as ambiguous rather than silently selecting different models.
Explicit `mysticism.embedding.bundle` takes precedence over classpath discovery.

## Bundle and adapter contract

A schematic manifest (replace placeholders with verified SHA256 values):

```properties
bundleSchema=1
profileFingerprint=<EmbeddingProfile.current().fingerprint()>
asset.model=model.gguf
sha256.model=<64 lowercase hex characters for these exact model bytes>
asset.tokenizer=tokenizer.json
sha256.tokenizer=<64 lowercase hex characters>
asset.runtime=runtime-adapter.jar
sha256.runtime=<64 lowercase hex characters>
runtimeJars=runtime
```

The transport does not assume GGUF, ONNX, or a particular native library; the
adapter determines the actual supported model format. The example is not a
provisioned model. Asset roles are identifiers, `model` is mandatory, and all
assets must declare hashes. Asset names are relative, confined, simple paths;
absolute paths, URL schemes, traversal and directory symlink escapes are rejected.
Directories return verified original absolute paths. File/jar resources extract
verified bytes atomically into a content-addressed native-file cache, repairing
corrupt entries. `mysticism.embedding.resourceCache` may select an absolute cache
directory; the default is under Java's temporary directory. Cold extraction and
hashing of real weights can exceed the default 30-second readiness deadline;
`mysticism.embedding.deadlineMillis` may be raised within its existing
1..120000 ms bound. Extracted files remain for reuse; no automatic eviction exists.

The adapter implements:

```java
public interface EmbeddedRuntimeFactory {
    EmbeddingProvider open(EmbeddedRuntimeResources.Bundle bundle) throws Exception;
}
```

Register exactly one implementation in
`META-INF/services/io.github.mysticism.embedding.EmbeddedRuntimeFactory` in an
adapter jar. List adapter/dependency jars as roles in `runtimeJars` (comma-separated,
ordered), or place them on the game classpath. The bundle loader keeps Mysticism's
SPI/vector types parent-owned and exposes runtime resources through the thread
context classloader during factory initialization, open, profile validation,
readiness, inference and close. `bundle.require(role)` supplies absolute verified
paths. Adapters must use those paths explicitly, including separate native and
external tensor/tokenizer files; do not assume extracted assets keep their
original sibling filenames. Native dependencies must already be provisioned for
the host platform, never installed/downloaded by the adapter.

`profile()` must equal `EmbeddingProfile.current()` exactly. `checkReady()` must
perform **real native v2 inference** and verify revision, tokenizer, task prefix,
mean pooling, full native 768 output and finite/nonzero values. Byte hashes and
profile declarations alone are not proof of correct inference. `getEmbedding`
accepts unprefixed canonical descriptors and must use the current
`search_document: ` semantics. Runtime exceptions remain exceptional futures;
there is no HTTP fallback. Startup failure releases the runtime/classloader.
Shutdown does not unload a delegate while inference is actively using it.

The optional legacy HTTP backend is explicit:
`-Dmysticism.embedding.provider=http`. Its endpoint/model settings are described
in `nomic-embedding-contract.md`. It is not required for embedded launch and is
never selected automatically after embedded failure.

## Regression checks and honest verification boundary

Canonical build, no test exclusions or diagnostic dependencies:

```sh
./gradlew --no-daemon --console=plain build
```

`runtimeSelfTest` discovers `EmbeddedRuntimeResourcesTest` and
`EmbeddedIntellijLaunchTest` alongside the existing embedding suites. Resource
fixtures test disk/exploded/jar integrity, cache repair, ambiguity, confinement,
remote-URL rejection and native v2 profile rejection. Child JVM launches use a
real SPI jar, runtime-only context-classloader resource, and unrelated Minecraft
working directory for all three origins. The diagnostic adapter **always fails
readiness intentionally and never produces vectors**; tests verify cleanup and
unavailable state, not model inference. `verifyEmbeddedLaunchConfiguration`
checks actual Loom settings and serialized IntelliJ XML including path quoting
and inherited data-generation arguments. Test path-with-spaces configuration with:

```sh
./gradlew --no-daemon --console=plain \
  '-PmysticismEmbeddingBundle=build/provisioned bundle' verifyEmbeddedLaunchConfiguration
```

That last path is a configuration fixture, not usable model weights. No live
Minecraft/IntelliJ GUI launch or native-model inference is claimed by these tests.
