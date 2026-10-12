# Embedded inference ownership handoff

## Retained scope

The prior retained session stopped on provider rate limits/timeouts after reading
sources; its checkout had no uncommitted edits. This change preserves its history
and repairs the `AsyncEmbeddingEngine` provider-ownership boundary, not the other
ten gameplay/rendering units or model provisioning.

Previously, distinct descriptors could concurrently enter one provider, and
shutdown could close that provider while readiness/inference was still using it.
`EmbeddingProviderSession` now owns one provider per engine:

- readiness, inference, validation, complete native-vector snapshot and
  normalization have exclusive access to the provider's reusable state;
- queued workers recheck request ownership after acquiring that access, so expired
  or shut-down requests cannot subsequently start inference;
- shutdown wakes waiters and cancels subscribers without waiting for an active
  native call. Only the returning active owner disposes native resources, exactly
  once. No inference/lifecycle monitor executes subscriber callbacks;
- independently created replacement engines/providers do not share this gate.

The existing queue, deadlines, cache copies and detached subscriber cancellation
remain intact. Projection, source ownership, terrain and the fresh native Nomic v2
profile (all **768** coordinates) are unchanged. No migration, truncation, model
weight downloads, dependency installations or production dummy vectors were added.

## Native adapter integration requirement / real blocker

This checkout still initializes `EmbeddingService`, the HTTP/Ollama implementation,
from `EmbeddingHelper`. It contains **no in-process native model adapter** or
bundled model runtime. This change therefore cannot by itself satisfy the IntelliJ
no-external-server requirement, and does not claim native model inference testing.
The separately restored adapter must pass a fresh provider to each engine, never
share it across engines or invoke it directly after ownership transfer, and finish
all native work/release per-call tensors before returning from provider methods.
An adapter may reuse a mutable `Vec384f` output; the engine snapshots it exclusively.

For safety this boundary serializes *every* provider, including the current HTTP
implementation. Native throughput is one active call per provider; more engine
workers do not imply more predictors. A native call that ignores interruption and
never returns retains its resources indefinitely after shutdown rather than
risking use-after-free. Backend-specific cooperative cancellation remains a native
adapter responsibility. Idle disposal itself is synchronous and relies on a
well-behaved provider `close()`.

## Actual verification

Canonical command, with the already installed Gradle/JDK and no installations:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build
```

The canonical runner discovers these five runtime classes:
`AsyncEmbeddingCallbackIsolationTest`, `AsyncEmbeddingEngineSafetyTest`,
`EmbeddedInferenceConcurrencyTest`, `EmbeddingPersistenceTest`, and
`EmbeddingPipelineTest`. The new class has **23,924 checks**, covering concurrent
admission, borrowed-output snapshots including coordinate 767, cache ownership,
timeouts/expired session waiters, error recovery, readiness/inference shutdown
while native fixtures ignore interruption, restart independence and detached
subscriber cancellation. Providers in these tests are deliberately unsafe fixtures,
not real models. Historical prototype suites excluded by `scripts/selftest.gradle`
are not represented as current runtime coverage.

A JDK-only diagnostic under ignored `build/embedded-concurrency-check.py` compiled
the new tests against the original HEAD's engine: it failed with
`Shared predictor called concurrently`. The fixed sources passed **12 consecutive
runs**. The script is a local diagnostic, not a new Gradle task.

`build` also runs decoded-metadata, descriptor, packaging and runner-discovery
fixtures (`buildSelfTest`), remaps the production artifact, and runs
`verifyProductionJar`. The distributable path is
`build/libs/mysticism-1.0-SNAPSHOT.jar`; passing static packaging checks is not proof
of an in-process model, Minecraft launch, or gameplay correctness. No game launch
or real model/service call was performed for this scope.
