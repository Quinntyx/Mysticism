# Retained merge-0035 integration

**Current status:** native-provider blocker resolved and real inference verified.
The initial integration record below is retained as history; its HTTP-only
blocker is superseded by [the resumed integration](#resumed-exact-model-integration).

Branch: `feat-runtime-sol-merge-0035`; target: dev, never main.

The starting checkout contained the unfinished merge of
`93f4294d359f7be9a82436ffa6b367c18989a145` into
`57701f66d656395f9e6cccac0e95aae5baa9ee22`. Existing commits and staged changes
were preserved. Only this owned checkout was written; no delegation, software
installation, model download, reset, stash or other branch mutation occurred.

## Resolution and preserved behavior

`AsyncEmbeddingEngine` needed both sides of its conflict: the cache logger/load/
save lifecycle and the reserved off-thread composition helpers. Neither is a
replacement for the other. The resolution keeps both. The automatically merged
helper still supplies the cache path, captures one engine generation per
composition, and obtains independent readiness subscriptions.

The retained parents provide:

- Mesh-only grounding, collision-resolved crouch edge probes and vertical floor
  constraint resolution that does not project gravity into sideways slope creep.
  Missing/hidden/noncollidable cells do not fabricate support.
- Complete current-profile, native 768-coordinate, bounded LRU disk reuse after
  provider health validation; stale v1/foreign/corrupt files require fresh
  inference. No migration, truncation or dummy production vectors.
- Worker-based weighted composition even for cache hits, bounded admission and
  reserved callback publication, generation binding, and client-executor
  readiness notifications with stale-world suppression.
- Validated one-shot spirit dimension handoffs, normal vanilla world/player
  replacement and teleport acknowledgements, exact destination pose seeding,
  suppression of the two loading-screen installation sites for matched spirit
  travel only, and a bounded outgoing-world color bridge.

Independent per-player projection, source-coordinate ownership/octree growth,
and shared visible/collision geometry remain unchanged by this resolution.

## New merge regression

`AsyncEmbeddingCompositionTest.persistedHelperComposition` uses real cache files
and engines plus fixture providers. It persists a native-tail vector, restarts
with delayed readiness, rejects composition before provider health, then composes
256 restored descriptors through the helper without repeated inference. It
checks caller-mutation isolation, off-caller publication, 20 independent sibling
compositions while one callback blocks, and unchanged persisted tail coordinates.
A monitor gate attaches the callback before cache completion; the test does not
incorrectly assume late CompletableFuture callbacks avoid the attaching caller.
This adds 31 checks, bringing this discovered suite to 113.

## Actual checks

Canonical installed Gradle 8.13 / Java 21 command (no exclusions/overrides):

```text
/usr/bin/env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --rerun-tasks build
```

The first resolved-merge build passed (59s, all 14 tasks executed), before adding
the integration fixture. The final build including that fixture passed (23s,
all 14 tasks executed). `runtimeSelfTest` discovered and ran exactly 11 mains:

| Suite | Checks/assertions |
| --- | ---: |
| MeshGroundedMovementTest | 1282 |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingCompositionTest | 113 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingCacheReuseTest | 86 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessFeedbackTest | 17 |
| SpiritTransitionBridgeTest | 48 |
| SpiritTransitionContractTest | 76 |
| SpiritTransitionGateTest | 625 |

`buildSelfTest` passed decoded metadata, 14 descriptor rejection cases, 19
packaging rejection cases and runner discovery/order/assertions/failure fixtures.
`verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar`.
The framework `test` task is deliberately skipped; runtime discovery is through
`runtimeSelfTest`, not the discarded historical prototype suites.
`git diff --check` passed and no conflict markers remain in source/docs.
Expected negative-fixture cache/NBT/UI warnings and baseline API Guardian,
public-target mixin, deprecated-API/Gradle warnings remain nonfatal.

All long commands used the required tmux-jobs helper: original connection
`/tmp/tmux-1000/default,30794,11`, origin `%2300`, session `$11`, window `@1459`,
retained pane `%2319`. Final build run `725c1800c87e4a679eba63039fc49f29`, exit 0;
initial run `562d78850e3a44e6a8772fb1250d8cdb` also exited 0.

## Real blocker and risks

This candidate is not completion of all eleven runtime problems. The supplied
parents and this checkout still use the HTTP `EmbeddingService` as the helper's
default. No native inference runtime/model is packaged here. The existing parent
handoff identifies a separate native-provider/lifecycle integration; its reference
commit summaries were inspected, but it was not silently substituted for these
supplied parents. A no-external-server IntelliJ launch therefore remains blocked
on that integration, including safe native serialization/drained teardown and
real model validation. Provider-independent cache/async fixtures cannot certify
in-process model inference.

No Minecraft/IntelliJ launch, live mixin application, multiplayer movement/frame-
delay test, GL output, gameplay performance profile or actual model forward pass
was performed. Transition contracts are offline mapped-bytecode/wire checks;
the bridge can show a stationary image for at most five seconds. Dense mesh
crouch probing and client/server reconciliation need gameplay profiling. Cache
save remains synchronous on orderly shutdown (not per movement/render tick).
Do not report server-free inference or jitter-free gameplay based on this build.

## Resumed exact-model integration

User authorized the provisioned exact asset; no weights were downloaded or
substituted in this checkout. Reviewed native implementations reachable from
`e84ff3a1e47f19358f710dcade99264e9ffa6b42` (merge-0063) were inspected read-only
and the needed provider/asset/runtime/output implementation and fixtures were
integrated here. Unrelated source discovery/render implementations were not
copied. The prior retained merge and all its code/regressions remain in ancestry.
Both **original requested tips** are ancestors (checked with `git merge-base
--is-ancestor`, exit 0 for each):

- `57701f66d656395f9e6cccac0e95aae5baa9ee22` (mesh movement + cache).
- `93f4294d359f7be9a82436ffa6b367c18989a145` (async callbacks + spirit travel).

### Integration details

- Default helper now constructs `EmbeddedEmbeddingService`, not HTTP. Endpoint
  configuration cannot silently opt into a service. Verified local model
  allocation, health inference and cache restoration stay on preparation workers.
  The preparation budget is independent of short request deadlines.
- Both full-file hashes, exact length, tokenizer/prefix/token cap, native mean
  pooling and normalized full 768-coordinate output remain mandatory. There is
  no v1 migration, dimension reduction, fallback model or synthetic production
  vector. Native and cache profiles match the existing complete v2 contract.
- Kept the retained cache-path lifecycle **and** reserved off-thread composition,
  detached subscriber cancellation, generation capture and client-executor UI
  delivery. Added live helper readiness/model-failure state instead of relying
  on a completed startup probe. Definitive native failure clears the generation
  cache and pending work; it cannot masquerade as ready on later cache hits.
- Native context is serialized, closed only after in-flight use drains, and
  teardown does not wait for JNI on the caller. Added a request-ownership check
  after acquiring the native lock, so requests that expired waiting for another
  call do not subsequently launch inference. A definitive error after timeout
  still revokes readiness; a mere request deadline does not fail a healthy model.
- Added `EmbeddedRequestOwnershipTest`: 20 checks covering an expired waiter,
  successful recovery, late-success cache suppression, asynchronous context
  teardown, late definitive failure, live helper diagnostics/readiness, cached
  requests and composition after failure. Its model is explicitly a fixture.
- Production packaging requires the pinned native jar/resources, provider
  classes and Java dependencies, while rejecting weights and duplicate logging.
  Stable native normalization/weighted pooling regressions are included.
- No movement, pose-handoff, source-coordinate ownership, independent per-player
  projection or collision/render mesh code was replaced during native integration.

### Real exact-model evidence on this candidate

Original connection `/tmp/tmux-1000/default,30794,11`, origin `%3574`, session
`$11`, window `@2180`; all long commands used the required tmux-jobs helper,
explicit argv, bounded monitoring and retained output.

```text
/usr/bin/env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain embeddedInferenceProbe
```

**PASSED**, exit 0, 20s; pane `%3596`, run
`9034c9d4573a4732a474d1ae43b23612`. Default discovery selected:

```text
/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf
```

The probe used **no provisioning/download flag**, cleared provider overrides and
made the HTTP endpoint unusable. It executed JNI **b10682-5ea1b124e**, verified
exact SHA256/957680480-byte identity before/after allocation, performed actual
readiness and canonical descriptor inference, retained all 768 finite normalized
coordinates and rejected token overflow without truncation. Observed cosine
values oak/birch `0.86688715`, oak/lava `0.8003141` are **not a corpus-quality claim**.

The augmented probe also uses the actual `EmbeddingHelper` lifecycle twice:
first computes two fresh native descriptors, saves disk cache, then restarts
with the same complete profile. Both generations perform native readiness;
the second restores two entries before readiness completion and returns completed
cache hits without scheduling descriptor inference. It verifies exact bits for
all 768 persisted coordinates, clone/mutation isolation and weighted composition
through an explicit `Mysticism-ProbeClient` executor. Readiness/composition
callbacks execute there, not the caller/native worker. Helper initialization
returned in 39ms / 3ms in this run while preparation continued on workers; these
observations are not game tick/frame-latency guarantees.

### Canonical regression and packaging evidence

```text
/usr/bin/env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --rerun-tasks build
```

**PASSED**, exit 0, 40s, **15 tasks executed**. Retained pane `%3586`, run
`fa629ebe02f44354a958f0922c378b47`. Discovery ran **20** current runtime mains:

| Suite | Checks/assertions |
| --- | ---: |
| MeshGroundedMovementTest | 1282 |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingCompositionTest | 113 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddedEmbeddingSelfTest | 54 |
| EmbeddedNativeOutputTest | 3147 |
| EmbeddedNativeRuntimeSelfTest | real JNI ABI, not model inference |
| EmbeddedRequestOwnershipTest | 20 |
| EmbeddingCacheReuseTest | 86 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| EmbeddingReadinessFeedbackTest | 17 |
| LandmarkEmbeddingPoolingTest | 808 |
| LocalNomicV2ModelAssetTest | 47 |
| NativeEmbeddingOutputTest | 107 |
| NomicV2ModelAssetsTest | 113 |
| PinnedEmbeddingModelSelfTest | 25 |
| SpiritTransitionBridgeTest | 48 |
| SpiritTransitionContractTest | 76 |
| SpiritTransitionGateTest | 625 |

The explicit inference probe is not a discovered fixture suite. Historical
prototype suites outside the configured source roots were not claimed as run.
`buildSelfTest` passed decoded metadata, 14 descriptor-only rejection cases,
26 packaging rejection cases and runner discovery/order/assertion/failure
fixtures. `verifyProductionJar` passed the remapped production jar including
required native resources/dependencies. No shim, task exclusion or classpath
workaround was used. Expected negative-fixture warnings and baseline API
Guardian/mixin/deprecation warnings remain nonfatal.

### Remaining limitations (not the old external-server blocker)

Real native inference is now verified in this owned checkout, not inferred from
another branch's CI. There is **no known build/native-provider blocker** for this
retained scope. This is not a claim to have completed the other runtime units or
verified all eleven symptoms in Minecraft. No IntelliJ/client/server gameplay,
live mixin application, GL output, multiplayer/frame-delay or CPU profiling was
performed. The existing mesh movement and spirit travel assertions remain
numerical/policy/mapped-wire checks. Native warnings about CPU-only GPU settings,
missing vocabulary newline/mask metadata and speculative decoding were nonfatal
with the exact approved file; the probe does not establish embedding quality or
portability to all shipped native platforms. Already-running JNI is allowed to
drain safely rather than being forcibly cancelled. Model assets must remain
immutable; no whole-file rehash runs per request. Cache save remains synchronous
on orderly shutdown. See the initial record's mesh/bridge gameplay risks.

Final repeat after documentation integration: same installed Gradle/JDK and argv
`--no-daemon --console=plain --rerun-tasks build embeddedInferenceProbe` passed
in **43s**, exit 0, **16 tasks executed**, all 20 runtime suites plus packaging,
production verification and actual default-helper/native probe. Retained window
`@2180`, pane `%3612`, run `05434d4873cf481d87c80c863fbe73b1` in the same original
session/origin. No provisioning/download flags or task exclusions. `git diff
--check` and staged whitespace checks passed.
