# Embedding / visibility safety handoff

Only engine, command, common visibility/CPU selection and three new regression tests changed. No initializer, components, networking payloads, renderer/shader, terrain/core, mixins or build config edits. CORE_API and existing tests were read; AGENTS.md was absent in worktree/ancestors. No agents spawned.

## Changes / wiring

- `AsyncEmbeddingEngine`: atomic request/readiness/shutdown terminal claims under monitor; **every future completion outside it**, including cache/rejection/unavailability. Late work cannot cache/revive expired requests. Detached caller cancellation, cloned caches/results, total queue+inference/readiness deadlines, queued-task removal, bounded LRU, shutdown cleanup.
- `EmbeddingCommand`: nonblocking `/myst embed status`, `get <descriptor>`, `vector <descriptor>`, legacy `/embedding get_init <slot>`. Registry/tag capture and result delivery remain server-thread-only; validates player identity/dimension/current profile. Per-server latest-request tracker capped at 128; replacement/disconnect/respawn/dimension/stop cancellation. Status supports console; vector queries require a player. Uses parent's actual `vector.IndexPair` API; **merge parent’s IndexPair/KnnIndex/SimpleKnnIndex migration first**. Those vector files were not edited here.
- `SpiritVisibilityService`: existing item-generation index, not nearest K. Snapshot/radius-wide selection on one bounded worker, 64 queued jobs. Tick only polls, copies current vectors, re-gates <=128 members and sends deltas. Clears state on dimension/respawn/disconnect/stop. Catalogue cap 4096; larger catalogues fail closed.
- `SpiritGlyphSelection`: gate all members BEFORE deterministic clustering so outside-window outliers/dense groups cannot bury sparse groups. Max 32 seeds, 16 representatives/group, 128 total; persistent eligible seeds/member slots; opposite/quadrant/octant angular filling. Scalar-distance LRU <=512KiB. Public head-relative `position(...)` preserves projected semantic direction, separates projection-null groups and deterministically rebuilds IDs/positions.

Existing `SpiritVisibilityService.init()` and `EmbeddingCommand.register(...)` install hooks; registration calls idempotent command `init()`. Retain EmbeddingHelper start/stop wiring. No new registration/config is needed.

**Client conflict outstanding:** inspected sibling volumetrics renderer freezes `frozenBasis/frozenOrigin/realmAnchor` and uses `Projection384f`, not this head-relative layout. Membership balancing IS wired; new positions are NOT rendered yet. Parent/client reviewer must resolve this spec conflict. API: constructor `(Map<String,Vec384f>, embedding.EmbeddingProfile)`; `select(current,profile,limit,previousGlyphs)`; record `Glyph(id,clusterId,clusterSlot,slot,embedding)`; `position(glyph,current,basis,interpolatedHead,radius)`. Payload still carries only IDs/vectors. Client can cache selection from its decoded visible snapshot outside render/tick, retain slots and interpolate position targets. Do not re-key terrain/realm projections or teleport players. No fog changes.

## Actual validation

Interactive tmux **%460**, explicit **bash -lc**, visible output, no final-build redirection:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --no-daemon --max-workers=2 --init-script /tmp/mysticism-safety-parent-api.gradle compileJava compileTestJava selfTest
```

Temporary outside-repo init script replaces ONLY stale local KnnIndex/SimpleKnnIndex compiler inputs with parent's actual three vector files (including IndexPair) from sibling dev. **No dependency/shim or worktree changes.** After merging parent APIs, omit `--init-script`; committed tests have no sibling requirement.

Final result: **BUILD SUCCESSFUL in 13s**, all seven feature suites plus packaging contract/runner checks:

| Suite | Checks |
|---|---:|
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingCommandLifecycleTest | 144 |
| SpiritGlyphSelectionTest | 296 |
| EmbeddingPipelineTest | 133 |
| EmbeddingPersistenceTest | 93 |
| LandmarkCoreSelfTest | 802 |
| LandmarkPersistenceSelfTest | 2388 |

Tests cover real engine callback/state-lock deadlock, monitor assertions, cancellation/dedupe/deadline/queue recovery/cache clones/LRU, 80 submit-close races; actual command lifecycle gate; density/outlier/radius/profile/bounds/slot/head-position/rebuild selection; existing actual NBT/disk save-reload/corruption tests. Expected corruption fixtures log warnings/errors. Measured 4096-candidate selection: dense **7ms**, 32-cluster cold **106ms**, warm **22ms**, off-thread; observations, not guarantees.

A preceding no-shim run requested `verifyProductionJar`: seven suites passed, but `:jar` failed on duplicate `assets/mysticism/lang/en_us.json`; verifier did not execute. Parent subsequently reports its baseline full selfTest + verifyProductionJar passing after consolidation. No config/resource edits here. Earlier cached-DJL diagnostic runs are superseded by final no-shim validation. No logs/temp scripts committed.

## Limitations / review

- Client adoption/interpolation remains outstanding; tangent pole changes need smoothing. Slots are transient: deterministic rebuild is tested, historical membership hysteresis is not saved.
- No live Fabric player/event/network or GPU smoke test. Actual production gate/selection are tested; event/send wiring compiles but needs integrated review.
- Future callbacks remain synchronous on the completing thread, outside engine monitor; blocking callbacks can delay worker/timer. Interrupt-ignoring providers can continue on bounded daemon workers after timeout. EmbeddingHelper.shutdown still holds its own class monitor while calling close: parent should review caller-held locks.
- Cold selection is substantial bounded CPU work; worker overload sheds queued work and current-vector gating rejects stale off-radius results. Refresh detects index-generation replacement/size changes every 20 ticks, not same-size in-place mutations; authoritative wave-1 item generations replace the index.
- Legacy spatial/KNN commands retain synchronous index operations. Inspected seed/readiness paths already compose async work; no model IO/inference/join was added to startup/tick.
