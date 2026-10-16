# Current-profile runtime embedding cache

This cache is independent of the blocking `EmbeddingProvider` implementation.
It does not change model provisioning, select a fallback model, or replace a
provider health check. The retained integration now defaults to in-process
`EmbeddedEmbeddingService`; `EmbeddingService` is an explicit legacy HTTP option,
not a fallback. See [native contract](nomic-embedding-contract.md).

## Behavior and configuration

`EmbeddingHelper` now supplies a disposable cache path to `AsyncEmbeddingEngine`:

- `-Dmysticism.embedding.cacheFile=cache/mysticism/embeddings-v2.bin` is the
  default, relative to the launch working directory (including IntelliJ's run
  directory). Give each independently running game/server its own cache file.
- Set the property to an empty string to disable disk persistence.
- The existing engine constructor without a path remains memory-only. The new
  overload appends a nullable `java.nio.file.Path` argument; it works unchanged
  with any provider implementing the complete current profile.
- Requests still coalesce in flight and use the cloned, bounded access-order LRU.
  Descriptor text is exact: canonical tag changes are misses, never approximate
  cache hits. Capacity is unchanged (8192 entries for the helper).
- Startup checks the provider and reads the cache on the readiness worker, not
  the Minecraft startup/tick thread. Readiness is exposed only after restoration.
  Requests before readiness still fail. A failed/expired readiness or shutdown
  cannot be revived by a late read. A cache is not evidence of a healthy model.
- Orderly shutdown snapshots successful entries, clears pending/process state,
  closes the provider and writes outside both lifecycle monitors. Shutdown IO is
  synchronous and bounded by the 64 MiB file cap; it is not performed per request
  or tick. Crashes can lose the newest cache entries. This is derived data, not
  world state or a durability guarantee.

## Rejection and ownership

The binary cache has a distinct magic/format, embedding schema, descriptor
version, complete `(model, revision, dimensions, semantics, fingerprint)` header,
entry count, exact descriptor strings, native **768-coordinate** vector records,
and SHA-256 integrity checksum. Equal dimensions or a copied fingerprint alone
cannot certify another model/profile. The checksum detects corruption; it is
not authentication against an attacker allowed to rewrite the cache file.

Every loaded record must be finite, nonzero and already L2-normalized. All records,
including those outside a smaller runtime capacity, must validate before any
entry is installed. Duplicate/blank/oversized descriptors, legacy/foreign headers,
wrong dimensions, invalid values, oversized files/counts, truncated records,
checksum failures and trailing payload cause whole-cache rejection and fresh
inference. No v1 conversion, padding, truncation, projection or dummy vectors are
used. Native tail coordinates survive a save/restart exactly.

Reads and writes are bounded to 65536 entries / 64 MiB. If large descriptors fill
the byte budget before the entry budget, persistence keeps the most-recent suffix
without altering any vector. Writes use a unique sibling temporary file followed
by atomic replacement; an unsupported atomic move or IO failure leaves the old
cache untouched and is logged. Load/save failures do not take a healthy provider
down. Failed provider readiness does not overwrite an existing cache. Concurrent
processes sharing one cache may overwrite each other's cache membership (not
world data); use distinct paths. There is no cross-process locking.

This changes no world/index/landmark/component save keys, source-coordinate
ownership, per-player projection, terrain collision, renderer, or native embedding
profile constants. Persisted item/spatial state continues its existing full-profile
validation separately.

## Regression coverage

`src/test/java/io/github/mysticism/embedding/EmbeddingCacheReuseTest.java` is found
by the existing `runtimeSelfTest` main-based runner; no new dependency/framework
or model download is needed. It exercises real cache files and actual async
engines with controlled providers: save/restart hits, native tail preservation,
mutation isolation, LRU and smaller-capacity restoration, every profile field,
legacy schema/descriptor versions, 256/384/wrong-size rejection, zero/nonunit/
nonfinite vectors, late decode failure/all-or-nothing restoration, checksums,
truncation, file bounds, inference failure/retry, failed readiness preservation,
shutdown cancellation/idempotence and expendable load/save errors.

These fixtures are deterministic cache/lifecycle checks, not model inference or
live gameplay. The separate real `embeddedInferenceProbe` now also verifies
native default-helper cache save/restart and weighted callbacks using the exact
approved file; evidence is in [runtime-merge-0035](runtime-merge-0035.md).
Neither check claims live Minecraft/IntelliJ or GPU validation. Run the canonical Gradle
`build` to execute all discovered runtime suites, packaging fixtures and the
production remap/verifier.

### Historical standalone cache-unit verification

The retained integration now runs 20 runtime suites; see its handoff above.
The following five-suite record belongs to the original cache-only parent.

Canonical command (installed Gradle 8.13 / Java 21, no software installation,
classpath overrides, exclusions or external model calls):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain build --rerun-tasks
```

Final result: **BUILD SUCCESSFUL in 18s**, 14 tasks executed. Runner discovered
exactly five configured runtime mains: callback isolation (28 checks), engine
safety (184), cache reuse (86), persistence/network (90), pipeline (135).
`buildSelfTest` passed decoded metadata, descriptor, packaging and runner fixtures;
`verifyProductionJar` verified `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Historical prototype tests outside the configured runtime source set were not
claimed as executed. Expected corrupt-cache/NBT fixtures log warnings. Existing
APIGuardian/mixin/deprecation warnings remain nonfatal.

Retained tmux handle: original session `$11`, origin `%1967`, job window `@1274`,
pane `%1971`, final run `a9039a1b00924ef0bece546cc8e7b649`, exit 0. An initial
compile failed on a duplicate catch-variable name in this unit; it was repaired
before the passing builds. `git diff --check` passed.
