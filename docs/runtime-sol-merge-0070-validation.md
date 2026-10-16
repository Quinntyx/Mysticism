# Retained merge-0070: generation coverage and embedded runtime integration

Branch `feat-runtime-sol-merge-0070`, targeting **dev**, never main. Both exact
accepted parents are retained in the merge ancestry:

- `7608d084ff015aedfc5eece304adae06d15e9651` (merge-0064: generation surveys,
  paged geometry-reference directory, IntelliJ embedded runtime/resources).
- `51a05b9f9c1e959314ed0079576fc38ec2a6cedd` (merge-0067: vertical discovery,
  native-v2 assets/pooling/readiness, unload/reload, native concurrency/lifecycle,
  spirit picking and projected interaction feedback).

The original commits and retained rejection/repair history were not rewritten or
reset. This integration does not open a new feature/review cohort. Earlier
validation documents remain historical evidence, not claims about this candidate.
No child delegation, other-checkout modification, dev/main update, model download,
substitute model, dependency installation, stash, reset or force-push was used.

## Combined behavior and repairs

Horizontal component/peak generation surveys and full-height vertical sampling
both survive pre-Session chunk loads. The Session adopts both retained queues;
survey work alternates with vertical/player/frontier hints. Generation work is
retired only by publication owning the exact sampled AIR/SOLID source cell, not
by readiness, metadata proximity or entry into spirit mode. Vertical/frontier
admission now uses the same bounded, non-evicting production helper: producers
retain their cursor when full, and duplicates cannot reorder/starve pending work.
The regression reproducing the old overflow explicitly uses its historical lossy
admission instead of mistaking the new backpressured helper for that old behavior.
Startup bytecode coverage follows both actual constructor-adoption call paths.

Local source growth/patches retain unread immutable geometry, canonical identity,
source ownership revision checks and competing-owner exclusion. Aggregate geometry
references use the first parent's paged directory, not a 4096-entry metadata list.
Unload/reload retains persisted ownership and generated-only observations; vertical
and frontier work re-admit without inventing unknown geometry. The bounded vertical
scan remains independent of the heightmap-only horizontal component survey.

The default provider is the first parent's lazy resource bridge, backed by the
second parent's local-only, fully verified native-v2 service. Explicit directory,
exploded/jar resources and ordinary approved local/cache assets remain supported;
invalid explicit selections fail closed. The bundle backend uses the same exact
asset verifier before and after allocation. Java model/cache paths must be
absolute; Gradle resolves project-relative paths before emitting Loom/IntelliJ
arguments, so Minecraft's `run/` cwd cannot change which model is selected.

The bridge was reconciled with the second parent's stop/drain/dispose protocol:
`requestStop` only signals cancellation; `close` waits for work and finishes native
and classloader teardown before releasing the outer process lease. Failed teardown
remains a failure and prevents unsafe reopening. Initialization and inference run
off game/render ticks, output is detached under serialized ownership, and model
errors revoke operational discovery/navigation readiness. Neither an HTTP endpoint
nor a populated item cache can certify readiness. Fixture launch checks now await
actual asynchronous teardown rather than assuming readiness-failure delivery also
means resources have already closed.

Full native 768 dimensions, exact authorized model/tokenizer, MEAN pooling, stable
L2/weighted composition and schema-2 provenance are retained. There is no v1 vector
migration, truncation, dummy fallback or automatically launched/external service.
The optional HTTP provider remains explicit administration only. Spirit carrier
block picking/highlights stay suppressed with projected touch/crosshair feedback.
Independent per-player deep/shallow projection and custom terrain render/collision
algorithms are not replaced by this integration.

## Final checks executed on this combined tree

Java 21 / Gradle 8.13, via required `tmux-jobs` in original session `$11`, origin
`%3818`, window `@2305`, pane `%3822`, run
`fb2c528f775f41b3a70cc616f194a1f6`. Actual argv:

```sh
./gradlew --no-daemon --console=plain clean build embeddedInferenceProbe embeddedIntellijInferenceProbe -PembeddingProvision=false -PmysticismEmbeddingDownload=false
```

**PASS**, exit **0**, **2m 5s**, **19 actionable tasks executed**. This is a fresh
clean build, not an inherited parent result. No weights were downloaded.

- `runtimeSelfTest`: **36 discovered main-based suites**, assertions enabled,
  all passed. The intentionally disabled framework `test` task is not counted.
- `GenerationSurveyContiguousGrowthTest`: **3,617,794 checks**. All **361** adjacent
  radius-nine probes drain through production local preparation/publication with
  persisted parents. References cross **4096 at probe 305**, cold-reload there,
  and the remaining **56** adjoining probes publish. Final persisted union is
  **1,763,442 cells / 4,800 pages**, one canonical source identity; unread geometry
  and exact known floor/air ownership survive, unknown remains unowned. Retained
  fixture: `/tmp/generation-contiguous-10387265419950058307`.
- Geometry reference directory: **655 checks**, including 4096/4097/4800 and 70001
  references, schema/count validation and missing/corrupt dependency rejection.
- Generation overflow **2579**, startup **31**, hint retention **17**, survey hint
  integration **15**; full-height frontier reload **853**; vertical lifecycle
  **1000**, discovery **8900**, scheduler **43324** checks passed. These use source
  and persistence fixtures, not a live generated world.
- Embedded resource tests: **42** checks; directory/exploded/jar child launches,
  isolated built-in missing/invalid-asset launches, launch argv/classpath/default
  provider/offline guards, readiness failure fixtures, output/concurrency/lifecycle
  and pooling suites passed. Negative teardown/state fixtures intentionally log
  exceptions; these are not failures of the real native model.
- `buildSelfTest` passed descriptor, packaging, discovery/order/assertion/failure
  runner fixtures. Common/client compilation, source/remapped jars, Loom's actual
  IntelliJ XML/classpath verification and `verifyProductionJar` passed.
- `embeddedInferenceProbe`: **real in-process inference passed** on JNI
  `b10682-5ea1b124e`, full **768** coordinates. Actual tokenizer, stable output for
  four real landmark texts, coordinate-wise normalization/weighted references,
  overflow rejection, concurrent requests, three world-owner lifecycle identities,
  retiring cancellation, late-stop isolation and completed JNI teardown/reopening
  passed. Operational readiness released discovery admission and composed a real
  native cave embedding without fabricating an anchor; shutdown revoked readiness.
- `embeddedIntellijInferenceProbe`: a **fresh child JVM** uses actual Loom client
  embedding arguments and default embedded selection from an unrelated cwd with
  spaces. Approved cache discovery, hash validation, full native inference, finite
  normalized tail, descriptor differences, cache isolation, shutdown and reopening
  passed twice. Cwd retained under
  `build/embedding-intellij-inference-probe/Minecraft run with spaces 5234651756805581137`.
  This is **not** an IntelliJ GUI or Minecraft gameplay launch.
- `git diff --cached --check` passed after resolving all index conflicts.

Both real probes used the authorized, already provisioned asset:

```text
/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf
bytes: 957680480
SHA256: a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f
```

No HTTP service was used. Observed oak/birch cosine `0.86688715`, oak/lava
`0.8003141`; these are not semantic-corpus quality claims. Production artifact:
`build/libs/mysticism-1.0-SNAPSHOT.jar`.

## Limits and risks

No live Minecraft client/server/IntelliJ GUI, real world/chunk IO, movement,
render/collision/crosshair, shader/GPU effect or render-distance convergence timing
was exercised. This combined unit does not claim all eleven original problems are
gameplay-verified. Picking checks are offline contract/geometry tests. Real model
success is Linux x86_64 CPU evidence, not cross-platform or semantic-quality proof.

Vertical sampling and horizontal component surveys are bounded, not an exhaustive
cave/cell census. Geometry directory traversal/cache and metadata construction remain
linear in aggregate references; cold owner-thread metadata IO and disk amplification
remain performance risks, and vanilla multi-file saves are not crash-atomic. Other
full-parent transfer/merge budgets remain. Native initialization rereads the complete
asset. A hung native call or failed close intentionally blocks reopening until it
returns or the JVM restarts. Existing Mixin/annotation, Jackson semver and Gradle
warnings remain; native missing-newline/mask-token, speculative-decoding and no-GPU
warnings were emitted without changing the approved asset. No executed-check,
approved-asset or external-service blocker remains for this combined candidate.
