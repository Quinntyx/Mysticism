# Retained merge-0035 integration

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
