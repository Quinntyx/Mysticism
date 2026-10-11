# Runtime embedding readiness repair (partial / embedded backend blocked)

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
