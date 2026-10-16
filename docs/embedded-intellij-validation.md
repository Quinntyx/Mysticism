# Retained IntelliJ embedding unit: validation boundary

## Current result after authorized model provisioning

The previous missing-weights blocker is resolved. The exact approved GGUF now
exists at `/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf`.
This retained checkout performed its **own successful real forward passes** (not
just relying on the merge checkout's reported result). The model resolver verified
all 957680480 bytes against the pinned SHA256. No model was downloaded, changed,
truncated, remapped or checked into Git. Earlier commits `3377eba` and `40ab8ca`
remain ancestors; no merge parents were requested for this unit.

Retained tmux connection/session `$11`, original reopened-process origin `%3572`,
window `@2177`:

| Command/check | Actual result | Pane / run |
| --- | --- | --- |
| `./gradlew --no-daemon --console=plain build` | PASS, 24s; **13 discovered runtime classes** including the new launcher regression, packaging/runner/descriptor fixtures, actual production jar and Loom/IntelliJ serialization/classpath checks | `%3584` / `900a3785f64340a2a682d185a51dc40b` |
| `./gradlew --no-daemon --console=plain -PembeddingModelPath=/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf embeddedInferenceProbe` | PASS, 11s; actual tokenizer, repeated/direct and concurrent/async inference, full 768 coordinates, normalization, tail, overflow rejection, cache isolation; no HTTP | `%3578` / `fbbd714db33540caa630aaec2a6b7588` |
| `./gradlew --no-daemon --console=plain -PmysticismEmbeddingDownload=false -PmysticismEmbeddingNativeThreads=2 embeddedIntellijInferenceProbe` | PASS, 14s; default cache discovery with **no modelPath or provider override**, fresh child JVM and unrelated cwd with spaces, real readiness/inference across two helper lifecycles | `%3579` / `c181d96f3f6346d6a928b55bb7bd41d6` |
| `./gradlew --no-daemon --console=plain '-PmysticismEmbeddingModelPath=build/embedding approved path/model with spaces.gguf' -PmysticismEmbeddingDownload=false -PmysticismEmbeddingNativeThreads=2 embeddedIntellijInferenceProbe` | PASS, 11s; actual Loom path forwarding with spaces, same real child/lifecycle checks | `%3581` / `89c96669f70847fc93babd0375b6ef32` |

The spaced explicit model fixture is an ignored symlink under this checkout's
`build/` to the approved cache file, not a substitute/copy or resource-bundle
confinement bypass. JNI extraction is inside the probe's own cwd. JNI build is
`b10682-5ea1b124e`; default cache and explicit-path runs both returned full finite,
normalized native 768-coordinate outputs, nonzero tails and distinct oak/lava
vectors. Two simultaneous descriptor requests are serviced through the real
serialized JNI adapter; each helper generation shut down and initialized again.
The direct probe observed oak/birch cosine 0.86688715 and oak/lava 0.8003141;
these examples are **not a semantic-corpus quality claim**.

The new opt-in `embeddedIntellijInferenceProbe` consumes the actual Loom client
embedding JVM options and uses the same main runtime plus test entrypoint on its
classpath. It verifies default embedded selection, disables downloads, and sets an
unusable HTTP endpoint. It is deliberately not attached to `build/check`.
`EmbeddedIntellijInferenceProbeTest` tests its argv/classpath/offline/default-selection
guards with no model dependency and is discovered by the canonical runner.
The first development run failed because the new probe had not created its native
extraction directory; the probe setup was fixed and both real launch modes then
passed. This was a probe setup failure, not a replacement model or muted failure.

**Boundary/risks:** no IntelliJ GUI or Minecraft game was launched. The actual Loom
XML serializer/classpath is checked, but existing ignored IntelliJ files remain
user-owned and may still need their stale bundle VM option removed or updated as
documented below. Linux x86_64 CPU inference is verified, not other platforms or
production-load CPU/RAM behavior. llama.cpp logs missing newline/mask vocabulary
metadata and unsupported GPU/speculative-context warnings for the pinned GGUF;
forward inference passed without modifying the asset. Model-quality benchmarking,
resource-cache eviction, and gameplay are outside this unit. The historical
missing-model failures below are retained for provenance, **not current blockers**.

## Candidate behavior

The original resource-bundle commit (`3377ebac7fb83f8fe4cf37a18e35acfeab09429f`)
is retained. The built-in JNI adapter, pinned-model resolver, concurrency/output
validation and explicit integration probe are integrated from the immutable
embedded-default/lifecycle/concurrency/async/output reference implementations.
The IntelliJ unit keeps its directory/classpath/jar bundle path and optional SPI;
a model-only bundle now uses the real built-in adapter instead of requiring a
separately provisioned adapter. Absence of a bundle selects the pinned local
model path, never an implicit HTTP service. Invalid discovered/configured bundles
still fail closed.

Loom resolves Gradle model/cache/bundle paths against the project and forwards
absolute paths to client/server launches. Inherited data generation receives each
option once. Preparation has its own bounded deadline; shutdown cancels a delegate
already loading, while its worker retains classloader ownership until unwinding.
The JNI runtime and all non-Minecraft dependencies are both on the IDE classpath
and nested in production. Competing SLF4J/Logback dependencies are excluded.
No projection, terrain, octree, render or collision implementation was changed.

## Historical pre-provisioning checks

Final canonical command:

```sh
./gradlew --no-daemon --console=plain build
```

**PASS**, exit 0, `BUILD SUCCESSFUL in 25s`, repeated after the authorized process
restart. Retained tmux session `$11`, window `@1580`, pane `%2595`, run
`987d3c83cdb34429a0b184631eca2994`, authorized replacement origin `%2592`.
The earlier pass took 39s (historical pane `%2352`, run
`4f7b8b496f8242f6bc6d5fabb8dce4ac`, retired origin `%2318`). The original pane
was intentionally retired; it is no longer a commit/push prerequisite.
All **12** discovered runtime classes ran with assertions, including:

- async callback isolation, request timeout, safety and cancellation;
- embedded provider lifecycle/concurrency fixtures and native output validation;
- five built-in-adapter child launches from an unrelated Minecraft cwd, using
  absolute paths with spaces, absent/corrupt weights, relative-path rejection and
  model-only/invalid-bundle checks;
- directory, exploded-classpath and jar-resource child launches, plus close
  during native-like preparation (without substituting a fake production model);
- resource hash, corruption repair, path/symlink confinement, local-only URL and
  ambiguity failures;
- pinned-model transport/integrity fixtures using loopback, not a live registry;
- persistence/network/profile and pipeline regressions;
- **real Linux x86_64 JNI loader/ABI**, `b10682-5ea1b124e`, **without model inference**.

Packaging/discovery/descriptor fixtures passed; the actual remapped
`build/libs/mysticism-1.0-SNAPSHOT.jar` passed nested native/dependency, forbidden
weights/logging-runtime, bytecode, metadata and resource checks. Actual Loom
run arguments and IntelliJ XML serialization passed. Assertions/tests are not
skipped because Gradle's unused JUnit action says `SKIPPED`.

Additional PASS (exit 0):

```sh
./gradlew --no-daemon --console=plain \
  '-PmysticismEmbeddingModelPath=build/model with spaces.gguf' \
  '-PmysticismEmbeddingResourceCache=build/resource cache' \
  -PmysticismEmbeddingDownload=false \
  -PmysticismEmbeddingPreparationDeadlineMillis=600000 \
  -PmysticismEmbeddingNativeThreads=2 \
  ideaSyncTask verifyEmbeddedLaunchConfiguration
```

Historical pane `%2353`, run `9869bb3d65dd4236b32e413a9b43df2c` (retired with the
original process). This exercised the actual
Loom serializer/Gradle configuration with spaces; these are not executable weights.
Loom intentionally leaves existing ignored `.idea/runConfigurations/*.xml`
untouched. This checkout has older generated VM options referencing
`build/provisioned bundle` (and a duplicate inherited data-generation option).
They were not overwritten: an existing IntelliJ configuration must be manually
updated to the documented new absolute model options. Fresh serialized settings
are verified; **no IntelliJ GUI launch was performed**.

Gradle 9 deprecation warnings remain. Persistence tests intentionally log rejected
malformed NBT; their assertions pass. The final run has no multiple-SLF4J-provider
warning (the earlier JNI transitive Logback issue was fixed and guarded).

## Historical missing-weights blocker (resolved above)

Ran this separate opt-in probe without allowing weight provisioning:

```sh
./gradlew --no-daemon --console=plain embeddedInferenceProbe
```

**BLOCKED / exit 1**: `java.io.FileNotFoundException: Pinned Nomic v2 GGUF missing`
at `build/embedding-inference-probe/nomic-v2.gguf`. Historical pane `%2354`, run
`714fc61a32ac415dba5a5e7c4eac323c` (retired with the original process).
The same probe was repeated after the authorized restart: **exit 1**, identical
missing-GGUF failure, retained pane `%2599`, run
`288770cd7f7a487a98847d72a87c75f1`, origin `%2592`.
The pinned file is also absent from the standard
Ollama blob and Mysticism cache locations. No weights were downloaded or inferred.
Required GGUF: SHA256
`a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`,
exactly **957680480 bytes**.

At the time of those historical checks the missing-adapter blocker was addressed
in source, classpath, packaging and JNI loading, but real inference remained
unverified. Provisioning the exact authorized model and passing the real probes
above now resolves that blocker; compilation and fixtures alone never did.
Gameplay and broad semantic quality still have not been verified.

Other risks: the 15-minute preparation bound and production-scale native CPU/RAM
needs have not been benchmarked; macOS/Windows/ARM libraries have only static
packaging checks; external SPI runtime behavior depends on its adapter;
resource-cache eviction is not implemented. Preserve the native schema-2/full-768 contract,
with no v1 migration, truncation, dummy fallback or external-server prerequisite.
