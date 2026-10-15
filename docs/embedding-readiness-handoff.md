# Runtime embedding readiness repair

## Embedded-provider integration follow-up

The HTTP-only backend blocker below is **historical**, not the current source.
This retained branch preserves its readiness commit and integrates the approved
embedded-default implementation (`ca2addcb52dc1bd3b46058045911c1e9cd8ba221`) plus
native output/pooling validation (`9814794d4fee2ea497542d4596f75bf0663f6646`
and its prerequisite). The normal provider is now local CPU/JNI; an invalid or
unreachable endpoint property cannot silently select HTTP. Remote Ollama remains
an explicit deployment choice, never a fallback. See
[native-v2 runtime contract](nomic-embedding-contract.md).

Additional actual repairs:

- Embedded tokenizer/protocol, native execution/linkage and invalid native-output
  failures become definitive `EmbeddingModelException`s with their original
  causes. Previously they escaped as ordinary descriptor errors, leaving the
  already-probed model ready for new discovery and serving cached vectors.
- The engine atomically revokes readiness, empties the generation cache, drains
  shared/active/queued subscribers, stops worker/deadline executors and disposes
  the failed provider asynchronously. A definitive error arriving after a
  request timeout still revokes readiness. The first model cause is retained;
  late success cannot revive or cache an invalidated generation.
- Descriptor validation and a valid tokenizer's >512-token result remain local
  request errors. Overflow is never truncated and does not destroy a healthy
  model. Empty/malformed tokenizer responses are model failures.
- These live states feed the retained real SourceLandmarks admission/cancellation
  and SpiritNavigationService walk admission/cancellation paths. Physical source
  reads, current-profile published anchors and already-staged atomic ownership
  publication do not acquire a spurious external-server dependency.

`EmbeddedDiscoveryReadinessSelfTest` exercises the actual embedded provider,
engine, work queue, helper lifecycle and production navigation policy with failure
fixtures. It checks zero/nonfinite/wrong-width output, malformed/empty tokenizer,
JNI execution/linkage loss, queued/cache invalidation, late errors, startup probe
failure, descriptor-local rejection and two default-helper launches with a
missing explicit local asset and an invalid endpoint. No fixture is installed as
runtime data. The integrated output suite now asserts terminal generation failure
on malformed output rather than assuming same-generation model recovery.

The independent player projections, source octree ownership/growth, native-v2
fingerprint and full 768-coordinate vectors, and terrain mesh/collision behavior
are unchanged. This is only the assigned embedding-readiness unit, not a claim
that all eleven gameplay reports were exercised or repaired here.

### Current verification

Canonical `./gradlew --no-daemon --console=plain build` passed. A forced
`./gradlew --no-daemon --console=plain build --rerun-tasks` also passed: exit 0,
46 seconds, **15/15 tasks executed**, including common/client compilation,
packaging/verifier suites, discovered runtime suites, remapping and production
jar verification. Retained handle: original session `$11`, origin `%2308`, job
pane `%3126`, run `19e14114700640a2a8de6949ce485295`.

The runner discovered and executed **12** classes with assertions enabled:

| Class | Checks / evidence |
| --- | ---: |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddedDiscoveryReadinessSelfTest (new) | 172 |
| EmbeddedEmbeddingSelfTest | 54 |
| EmbeddedNativeOutputTest | 3147 |
| EmbeddedNativeRuntimeSelfTest | Real JNI ABI `b10682-5ea1b124e`; no forward pass |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessSelfTest | 53 |
| NativeEmbeddingOutputTest | 107 |
| PinnedEmbeddingModelSelfTest | 25 |
| DiscoveryReadinessSelfTest | 18 |

Packaging passed valid-artifact checks, 26 rejection cases, 14 descriptor-only
rejections, decoded metadata cases, and runner discovery/order/assertion/failure
fixtures. `verifyProductionJar` accepted
`build/libs/mysticism-1.0-SNAPSHOT.jar`, including required nested JNI/runtime
dependencies. No build/test exclusions were used. The first build after
integration exposed a retained output test assuming same-generation recovery
after invalid output; that test now verifies the stronger terminal-failure
contract, using a fresh model generation per malformed output case.

Real-inference verification was attempted separately, **without provisioning**:
`./gradlew --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false`.
It failed explicitly (exit 1) with `FileNotFoundException: Pinned Nomic v2 GGUF
missing` at `build/embedding-inference-probe/nomic-v2.gguf`. Retained pane `%3143`,
run `2db0172de84d4edb9087497b680019b8`. The exact pinned blob is also absent from
both supported home-cache locations (`~/.ollama/models/blobs/sha256-<digest>` and
`~/.cache/mysticism/models/sha256-<digest>.gguf`). No weights/software were
installed or downloaded and no configuration was changed. Thus the old missing
*backend* blocker is repaired; missing approved local *weights* still blocks a
real native forward pass in this environment. Provide the exact 957,680,480-byte
SHA256-pinned GGUF and rerun the probe with `-PembeddingModelPath=...` to resolve
that verification gap; do not substitute another conversion/profile.

No Minecraft/IntelliJ client or dedicated server was launched. Native-model
compatibility, latency/semantic quality and gameplay remain unverified. The
forced build still reports 98 existing client annotation/Mixin warnings, Gradle
deprecations, a Jackson version-semver warning and multiple SLF4J providers on the
development test classpath; expected malformed-persistence traces are fixtures,
not evidence of live model failures.

## Historical retained repair and its original blocker

The following record describes `7b16aee7f7a344914f0bfd00e26950e0847704ab`
**before** the embedded integration above. Its missing-backend statements and
six-suite count must not be used as the current implementation status.

Branch: `feat-runtime-embeddings-embedded-readiness`; base retained HEAD:
`bfbce90c4752e8f6ee821769eb9d51a43453a13b`.

## Actual behavior repaired

* `EmbeddingReadiness` is a nonblocking operational snapshot, not a promise that a
  successful startup probe makes a model ready forever. Initializing, ready,
  failed and stopped are distinct; terminal states retain the original cause.
* A definitive `EmbeddingModelException` during inference revokes readiness,
  discards the generation's cache, removes queued work and terminates all
  active/shared callers. A definitive error arriving after the caller's deadline
  still revokes readiness. Late success does not resurrect invalidated work.
  Per-descriptor overflow/transient errors do not permanently poison the model.
* The existing HTTP adapter classifies missing/revision-changed models,
  mismatched response models and malformed/non-native vectors as model failures.
  Native v2 keeps all 768 coordinates; no v1 migration or truncation was added.
* Source discovery admission now depends on live model readiness, not a populated
  persisted item catalogue. Initialization queues bounded discovery work; failure
  rejects/drains it with the real cause. Physical source reads can bypass pending
  discovery. Ownership reads and topology transfers remain model-independent.
* Active discovery is cancelled on model loss before mutation staging. Already
  staged atomic geometry publication is allowed to finish, avoiding a torn
  ownership transaction. There is no item-only fallback swallowing model errors
  or substituting for biome/structural descriptors.
* Navigation distinguishes a model from a published current-profile source
  anchor. Unanchored walk requests are rejected/cancelled on terminal model
  failure; initialization may remain pending. Restored valid source anchors
  remain usable without new inference. Free-flight diagnostics update on model
  phase transitions and capture failures retain the provider cause.

The repair does not change independent per-player projection, source-coordinate
ownership geometry/growth, mesh collision/rendering or vector profile identity.

## Verification

Canonical command, existing Java 21 / Gradle 8.13:

```text
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain build
```

Also rerun with `build --rerun-tasks`: exit 0, 14/14 tasks executed in 25s.
Retained final tmux handle: session `$11`, origin `%1866`, job pane `%1871`,
run `8abb9ea37f9a4ebc80e2e2ae4e0e091a`.

Passed compilation of common/client/runtime tests, `buildSelfTest`,
`runtimeSelfTest`, remapping and `verifyProductionJar`. Runner discovery showed
six main-based classes, all executed with assertions enabled:

| Class | Checks |
| --- | ---: |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessSelfTest (new) | 53 |
| DiscoveryReadinessSelfTest (new) | 18 |

Total: 508 runtime assertions/checks. Packaging fixtures also check 19 rejection
cases, 14 descriptor-linkage rejections, valid production metadata/resources and
main-runner discovery/failure behavior. Expected malformed-persistence warnings
are negative fixtures, not live model failures. Client compilation still reports
98 annotation/Mixin warnings and Gradle reports existing deprecations.

The current runner compiles `src/runtimeTest/java` and the retained embedding
suites in `src/test/java/io/github/mysticism/embedding`. Archived prototype suites
outside those paths are not claimed as verified. New navigation coverage tests
production readiness policy, not a running Fabric player or support acquisition.
HTTP tests use loopback synthetic protocol fixtures only; no weights are used.

## Real remaining blocker

**In-process native Nomic v2-MoE inference is not restored by this commit.**
`EmbeddingHelper.initializeServer` still constructs the existing HTTP
`EmbeddingService`; its default endpoint remains `http://127.0.0.1:11434/`.
An IntelliJ launch with no embedding service therefore now fails discovery
promptly/actionably, but cannot generate fresh semantic source anchors.
That is not the requested server-free runtime and must not be presented as fixed.

The retained checkout has no embedded v2 inference adapter/runtime or provisioned
v2 model weights. Investigation found older v1-related cached assets, not an
approved usable v2 model; these were not repurposed. Installing software/model
weights was prohibited. A follow-up must supply an approved pinned v2 artifact,
tokenizer/pooling/task-prefix contract, a supported packaged in-process backend,
and corresponding artifact checks (the current packaging contract explicitly
rejects model-runtime leakage). Do not bypass this with dummy vectors, a v1
model, resized old vectors, or an external-server prerequisite declared solved.

No gameplay, IntelliJ Minecraft launch, GPU/CPU model inference, or semantic
quality verification was performed. Terminal model failure currently requires a
new embedding lifecycle/server restart; automatic retry/reprovisioning was not
added. Full retained task status is **blocked/partial**, despite the passing
canonical build and completed readiness subrepair.
