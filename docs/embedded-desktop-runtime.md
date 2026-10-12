# Embedded CPU runtime: desktop support and launch failures

Scope: `feat-runtime-embeddings-embedded-platform`, integration target **dev**, not main.
The retained failed session had only inspected the HTTP baseline. This unit reuses
repository commit `ca2addcb52dc1bd3b46058045911c1e9cd8ba221` (pinned default
in-process Nomic v2 inference) and adds the desktop native bootstrap. Terrain,
per-player projection, octree ownership and persistence/vector schema are untouched.
Other embedding lifecycle/assets/pooling units remain separate integration work.

## Runtime, not an external service prerequisite

Minecraft/IntelliJ uses the Gradle runtime classpath, or Fabric's nested production
`llama-5.1.0.jar`, plus the nested Jackson/JSpecify dependencies. Minecraft supplies
SLF4J. No package installer, standalone executable, GPU driver, Ollama daemon,
dummy vector, v1 migration, tokenizer replacement or dimensional truncation is
introduced. The existing approved exact GGUF and tokenizer are still required;
see [the model contract](nomic-embedding-contract.md) for file provenance and
first-use/offline options. JNI readiness is a platform preflight, **not model
readiness**: the provider must still execute a real model forward pass before
publishing Ready.

The bundled desktop matrix is:

| JVM OS/architecture | Native payload | Requirements |
|---|---|---|
| Linux x86_64/amd64 | `Linux/x86_64/libjllama.so` | glibc desktop, libstdc++.so.6, libgomp.so.1 |
| Linux aarch64/arm64 | `Linux/aarch64/libjllama.so` | glibc desktop and matching C++/OpenMP runtime |
| Windows x86_64/amd64 | `Windows/x86_64/jllama.dll` | matching 64-bit JVM and Microsoft VC++ 2015–2022/OpenMP runtime |
| Windows aarch64/arm64 | `Windows/aarch64/jllama.dll` | matching 64-bit JVM and VC++/OpenMP runtime |
| macOS aarch64/arm64 | `Mac/aarch64/libjllama.dylib` | native Apple Silicon Java 21 JVM |

This describes **bundled availability**, not a cross-platform execution claim.
Intel macOS, Rosetta x86_64 JVMs, 32-bit JVMs, mobile/Android, musl/Alpine and other
architectures are not supported by this CPU backend. On Apple Silicon, select
an arm64 JDK in IntelliJ's Minecraft run configuration. Merely running on an
ARM host does not make an x86_64 JVM native. Linux's dynamic linker is the final
authority for libc/CPU compatibility; no brittle shell-command/platform probe is
used, and its load failure is retained verbatim as the exception cause.

The inspected Linux x86_64 ELF requires glibc symbols through GLIBC_2.17 and C++
symbols through GLIBCXX_3.4.19, plus OpenMP `libgomp.so.1`. The inspected Windows
x86_64 PE imports `VCOMP140.DLL` in addition to Windows system DLLs. These are
observations of the pinned binaries, **not** checks performed on Windows/ARM/macOS.
No dependency software was installed during this unit.

## Safe, deterministic bootstrap

Construction stays IO/JNI-free. On the async preparation worker, before model
asset resolution, `EmbeddedNativeRuntime.prepare()`:

1. Validates Java 21+, 64-bit JVM and supported architecture aliases.
2. Rejects external `net.ladenthin.llama.lib.path`, architecture overrides and
   non-CPU backend overrides rather than silently falling back or loading them.
3. Creates a private per-JVM directory under `mysticism.embedding.nativeDirectory`
   (default `~/.cache/mysticism/native`; legacy upstream `tmpdir` can supply the
   root). It never cleans that shared root or reuses another JVM's extraction.
4. Streams the chosen bundled resource, bounds its length, and validates its exact
   SHA256 before any native load. Bad unpublished payloads are removed. Existing
   files cannot be overwritten by extraction.
5. Sets the upstream loader's library path to that private verified payload and
   its temp/cleanup path to a separate empty private scratch directory **before**
   `System.load`: JNI_OnLoad resolves LlamaModel and recursively initializes its
   upstream loader. Late configuration otherwise loads a second JNI into `/tmp`.
6. Calls `System.load` directly, preserving link/security failures and their OS
   causes, and requires the exact JNI build `b10682-5ea1b124e`.

Successful loading is process-wide and synchronized. Repeated/concurrent server
starts retain the same mapped library. Native libraries stay mapped until JVM
exit; server close still releases model contexts, never an in-use DLL. Cleanup
registration concerns only our private files/directories. Failed initialization
retains its first actionable error instead of later class-initialization noise;
restart Minecraft after fixing a native/bootstrap failure.

## Actionable failures

Preparation and subsequent embedding request failures preserve the platform cause.
`EmbeddingHelper.getInitializationStatus()` exposes the actual readiness exception.
There is no false Ready, silent GPU/native/model fallback or request to start an
external embedding server.

- `[JVM]`: select a 64-bit Java 21+ runtime for the Minecraft launch.
- `[UNSUPPORTED_PLATFORM]`: names os.name/os.arch, lists bundled desktops, and
  explains Intel macOS/Rosetta; no weights are accessed.
- `[CONFIGURATION]`: remove the specific conflicting upstream native override.
- `[MISSING_RESOURCE]`: restore the production nested llama jar or refresh the
  IntelliJ Gradle runtime classpath; never distribute the Yarn `-dev.jar`.
- `[INTEGRITY]`: replace a damaged/mismatched llama-5.1.0.jar.
- `[EXTRACTION]`: includes the failed root/cause and the configurable directory.
- `[NATIVE_LINK]`: includes the exact library path, OS linker's error, platform
  dependency requirements, and execution-permission/noexec remedy.
- `[ABI]` / `[INITIALIZATION]`: restart with the pinned runtime after fixing the
  reported build/link/classpath issue.

For a system-temp/home mount which prohibits native execution, set this in
**IntelliJ's Minecraft VM options**, not just Gradle's daemon options:

```text
-Dmysticism.embedding.nativeDirectory=/absolute/writable/execution-enabled/local/directory
```

Do not point it at an installed library: it is only an extraction root. The
backend creates its own private child. No command or dependency installer is
run automatically. Model download/integrity/memory errors remain distinct from
these native-platform diagnostics.

## Regression coverage

`EmbeddedPlatformTest` is in the discovered runtimeTest source root. It checks
architecture aliases/rejections, all five bundled native hashes/lengths, bounded
safe extraction and corrupt-payload cleanup, exact-path loading, retained linker
and security causes, dependency/noexec launch guidance, worker-only preflight
before asset access, and actionable request rejection. Fresh JVM fixtures execute
real production preflight with unsupported architecture, 32-bit runtime metadata,
file-as-directory, invalid native path, external-library and GPU override configurations, with an
invalid model path proving model assets are not touched first.

`EmbeddedNativeRuntimeSelfTest` executes actual packaged JNI initialization/build
ABI calls on the current desktop, concurrent/repeated bootstrap and CPU/mean
pooling/full-context argument assertions. On Linux it reads `/proc/self/maps`
to prove no second/unverified system-temp jllama was mapped. It does **not** load
weights, create a model context or execute inference. Packaging fixtures require
the bootstrap class and all five desktop payloads in the production jar.
`EmbeddedPackagedRuntimeTest` additionally extracts the five nested Java/runtime
jars from the real remapped artifact and executes the CPU bootstrap in an isolated
fresh JVM. Its classpath is only the probe class, production jar, those nested
jars and Minecraft's existing SLF4J API. It asserts class origins and mapped native
paths, so a working Gradle dependency cannot mask a broken production bundle.
This is not Fabric loader/game-launch verification.

Actual command results, exact candidate HEAD, and retained tmux handles are
reported in the completion response. No gameplay, IntelliJ launch, real model
forward pass, or non-Linux native execution is claimed by these deterministic
regressions. The separate `embeddedInferenceProbe` remains the explicit way to
verify real native v2 inference with the exact 957,680,480-byte GGUF.

Observed checks before commit: the canonical Gradle `build` passed all nine
discovered main-based runtime suites, the packaging/runner/descriptor fixtures,
and `verifyProductionJar`. Real Linux x86_64 JNI ABI/bootstrap passed both on the
Gradle runtime classpath and in the isolated remapped-jar subprocess. The explicit
`./gradlew --no-daemon --console=plain embeddedInferenceProbe` (without provisioning)
passed CPU preflight, then failed with `FileNotFoundException` for the absent exact
GGUF at `build/embedding-inference-probe/nomic-v2.gguf`. Thus real model inference
remains **blocked on the exact weights**; no model was downloaded, installed or
replaced to manufacture a passing result. Supply `-PembeddingModelPath` with the
exact pinned file (or explicitly opt into `-PembeddingProvision=true`) and rerun.
Expected corrupt-persistence fixture warnings, existing SLF4J test-classpath
provider warnings, and Gradle deprecation warnings do not represent new native
failures. Production packaging rejects duplicate nested logging libraries.
