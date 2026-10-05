# Building Mysticism

## Toolchain

- Minecraft **1.21.1**, Yarn **1.21.1+build.1**, Java **21**.
- Gradle **8.13** and released Fabric Loom **1.10.5** (not a snapshot).
- Use an already installed JDK/Gradle. This checkout does not install software or
  download embedding model weights. Gradle may resolve ordinary build dependencies.
- Common code is in `src/main`; client-only code is in `src/client`. Keep client
  classes out of common initializers so dedicated servers can load the mod.

On this development machine, use the existing distribution directly:

```sh
GRADLE=/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle
"$GRADLE" --version
"$GRADLE" --no-daemon --console=plain buildSelfTest
"$GRADLE" --no-daemon --console=plain compileJava compileClientJava processResources processClientResources
"$GRADLE" --no-daemon --console=plain build
```

The tracked wrapper targets the same Gradle 8.13 distribution. Use `./gradlew`
only when that distribution is already cached, or downloading Gradle has been
separately authorized. `--offline` does **not** prevent a wrapper download. To
regenerate wrapper tooling using installed Gradle without fetching a distribution:

```sh
"$GRADLE" --no-daemon --console=plain wrapper --gradle-version 8.13 --distribution-type bin --no-validate-url \
  --gradle-distribution-sha256-sum 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
```

Long-running commands in the assigned development environment must run in a
visible tmux pane. Example (use the printed pane ID for send/capture):

```sh
tmux new-window -n mysticism-build -P -F '#{pane_id}' 'bash --noprofile --norc'
# In that pane, from this checkout:
"$GRADLE" --no-daemon --console=plain build
rc=$?
printf 'BUILD_EXIT=%s\n' "$rc"
tmux wait-for -S mysticism-build-done
# Elsewhere: tmux wait-for mysticism-build-done
# Then: tmux capture-pane -p -t <pane-id> -S -3000
```

## Tests: no additional framework dependencies

`buildSelfTest` compiles only `scripts/selftest/*.java` against the JDK. It runs
positive and negative packaging fixtures plus runner discovery/failure fixtures,
and is independent of Minecraft source compilation. It checks missing resources/licenses/icons, unexpanded metadata,
model-runtime leakage (including nested jars), Java versions and unmapped Minecraft
class references. It can also run without Gradle or network access:

```sh
mkdir -p build/build-selftest
javac --release 21 -d build/build-selftest scripts/selftest/*.java
java -ea -cp build/build-selftest io.github.mysticism.build.PackagingSelfTest
```

**Feature-agent integration contract:** place Java checks in `src/test/java`, with
class names ending in `Test` (including `SelfTest`) and a
`public static void main(String[] args)` method. `selfTest` discovers top-level
classes from the compiled test output, executes them in sorted fully qualified
name order, and propagates exceptions/`AssertionError` as a failed Gradle task.
Assertions are explicitly enabled and checked. Do not add JUnit dependencies.
Test compilation/runtime includes common and client outputs plus their existing
runtime dependencies. `check`, `test` and `build` run `selfTest` and
`buildSelfTest`; the framework-based Gradle `Test` action is disabled. An empty
feature-test set is explicitly reported, not presented as tested functionality.
Tests must not call live embedding services, download weights, or bootstrap a game.

## Production artifact

`build` runs Loom's `remapJar` and `verifyProductionJar`. The distributable is:

```text
build/libs/mysticism-1.0-SNAPSHOT.jar
```

The name follows `archives_base_name` and `mod_version` in `gradle.properties`.
Do **not** distribute the `-dev.jar` (Yarn/named) or `-sources.jar`. Common and
client outputs belong to the same Loom mod and are remapped together. CCA, Satin
are included using Fabric Jar-in-Jar metadata, not a conflicting Shadow jar.
Dependency service descriptors remain intact inside their own jars. The unused
JVector declaration was removed: all current searches use `SimpleKnnIndex`, and
no common/client source imports JVector. This also avoids shipping its optional
Java 22 native payload and otherwise-unbundled transitive runtime libraries.
Fabric API and Fabric Loader are installed separately by the modpack/runtime.

The artifact verifier checks entrypoint and mixin resources, localization at
`assets/mysticism/lang/en_us.json`, expanded version, MIT license packaging,
Java 21 bytecode and intermediary Minecraft class references. No icon is declared
until an actual icon is supplied. Finder metadata and DJL/PyTorch/tokenizer
runtimes must not enter the production artifact. Artifact checks are static;
they do not substitute for a dedicated-server/client launch, GUI-scale/reload
checks, or an embedding-service integration test.

## Branch integration status

This build intentionally has no DJL dependencies or local/native model runtime.
The original source still imports DJL in `EmbeddingService`, `EmbeddingCommand`,
`KnnIndex`, `SimpleKnnIndex` and `ItemEmbeddingIndexState`. Until the separately
owned JDK HTTP embedding migration removes those imports, full compilation is
expected to fail. Do not restore local-model dependencies to disguise that source
integration requirement. Any additional compilation errors must be fixed by the
agent owning the affected source, then rerun `build` and `verifyProductionJar`.

### Checks actually performed on the build-portability branch

- Cached Gradle and the generated wrapper both report **8.13**, running Java **21**.
  Wrapper generation used the installed binary; no Gradle distribution was fetched.
- `buildSelfTest` passed: a valid archive, 26 stable intermediary-name cases,
  19 invalid-artifact rejection cases and runner discovery/order/assertion/failure
  fixtures. This branch currently has zero feature-test classes.
- `processResources`, `processClientResources`, `processIncludeJars` and
  `remapSourcesJar` passed. Expanded Fabric metadata and relocated language
  resource were also checked directly.
- The normal `build` fails with 19 unresolved DJL import/type errors in the five
  source files listed above; client compilation is consequently blocked.
- A **temporary, uncommitted compile-only diagnostic** supplied the original DJL
  API/model-zoo/tokenizer jars, with no engines/natives/model loading. Both
  `compileJava` and `compileClientJava` then passed, and `remapJar` produced the
  expected artifact with common/client classes, license, localization and three
  nested mod jars. `verifyProductionJar` correctly **failed** on its remaining
  `ai/djl/util/Pair` bytecode reference. That diagnostic jar is **not distributable**.
- Loom logged a nonfatal CCA source-remapping warning about missing
  `javax.annotation.Nullable`; the diagnostic client compile logged missing
  API Guardian annotation enum warnings. No runtime client/server launch or
  model-service call was performed.

Do not use the temporary diagnostic to claim a successful production build.
The parent must merge the source migration and rerun normal checks.
