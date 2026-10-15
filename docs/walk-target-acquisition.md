# Cross-owner walk discovery repair

Scope: `feat-runtime-walking-walk-target-acquisition`, for integration into `dev`.
This is the walk-target acquisition slice, not a claim to repair/validate all eleven
runtime issues in isolation.

## Behavior

A contacted terrain producer can retain landmark A's binding while displaying
source cells owned by B. `KEEP_BINDING` now keeps A's placement, metadata and
support embedding **and** saves B as a separate per-session discovered walk
target, associated with that exact retained producer. Discovery still seeds the
inverse-mapped source body air of the contacted cell using the real bounded
`SourceLandmarks.ensureSourceLocation` pipeline.

The saved discovery is not permission to walk by itself. Candidate lookup still
requires current exact source ownership of both the contact and body, live
metadata in the same source dimension, a matching discovered geometry revision,
and the same producer in the accepted frame. Acquisition additionally requires
matching support/ownership identity, a current extraction proof for that owner,
known clear body samples and a sampled solid floor, unchanged source basis and
projected coordinate, and body/continuous-transition clearance of the proposed
custom mesh. Only the validated transaction switches the observer's local view
to shallow B; A's producer binding remains intact. Transport failure rolls back
the session and accepted frame/provenance together and retains the target for
retry. Cancellation/expiry and success clear the transient discovered target.

No semantic coordinate normalization, embedding replacement, v1 migration,
vector truncation, player pose/velocity mutation, global player frame, source
block writes, or collision/render substitutions were added.

## Regression and verification

`WalkDiscoveryRuntimeTest` is automatically discovered by `runtimeSelfTest`.
It executes the production discovery-completion, candidate, acquisition and
publication transaction with deterministic server I/O fixtures. The successful
A-producer/B-owner path is covered for both local and independently retained
region producers, including inverse mapping through a non-orthogonal floor,
source owner compaction, shallow publication and accepted producer provenance.
Negative cases cover stale ownership/geometry, mixed-owner body, eviction,
per-session isolation, wrong dimension, cancellation, unknown/blocked body or
floor, pending/mismatched proof, basis/coordinate discontinuity, mesh obstruction,
swept continuity, and publication rollback followed by successful retry.

Ran visibly in tmux `%1074` with installed Java 21 and cached Gradle 8.13:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --offline --no-daemon --console=plain runtimeSelfTest build
```

Passed: 106 new runtime-path checks; all 27 existing walk policy/wiring checks;
all six discovered main-based test classes; build/packaging self-tests; common
and client compilation; remapped production-jar verification. Final build log:
`/tmp/walk-acquisition-repair-build-2.log` (`BUILD SUCCESSFUL`, exit 0).
Persistence tests intentionally log rejected malformed vectors/spatial fixtures.
Existing compiler annotation/mixin-target and Gradle deprecation warnings remain.

Remaining validation: no live client/server launch, actual extraction scheduling
under load, network-delayed acquisition, or renderer observation was performed.
The fixture supplies metadata/ownership/region I/O, mesh construction and
transport; it does not claim to exercise an entire running Minecraft world or
resolve independently scoped embedding/entry/visual repairs.
