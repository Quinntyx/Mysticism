# Partial-entry return callback recovery (P1)

Scope: `feat-runtime-entry-ux-entry-failure-recovery`, follow-up to `56d60e9`.
This is an additive correction, not a replacement of the existing runtime work.

## Behavior

A validated source return can transfer the player and run navigation deactivation,
then throw from a later Fabric world-change callback. Previously the exception
selected carrier retention unconditionally, granting flight in the source world
when the saved abilities and active navigation state had already been cleared.

`EntryRecovery.reconcileSourceReturn` now runs the return attempt and probes the
actual current world **after** the attempt, including exceptions. A player outside
the carrier completes idempotent terrain/navigation cleanup, restoring saved
abilities if a callback has not already done so. Carrier retention runs only while
the player actually remains in the carrier. Cleanup errors are not mistaken for
return refusal and are not converted into off-carrier flight grants.

Both failed-entry recovery and the terrain-rebuild fallback use this orchestration.
The latter stops carrier update/integration after a transfer even if a callback
throws. Return cleanup resets velocity/fall distance because an exception during
teleport can skip the normal post-teleport motion reset. Pre-transfer abort behavior,
source validation, restoration cadence and carrier geometry retention are preserved.
No changes to projection, ownership, terrain meshes, shaders or embedding profiles.

## Regression coverage and validation

`src/runtimeTest/java/io/github/mysticism/navigation/EntryFailureRecoveryTest.java`
executes the production return orchestrator using simulated world-change operations
and real `SpiritNavigation`/`PlayerAbilities` state. Cases include transfer followed
by a throwing callback both before and after deactivation, survival/creative/flying
ability preservation, idempotent deactivation, normal transfer, refusal and throw
before transfer retaining the live carrier, and cleanup exception propagation.
It also retains the existing entry classification, NBT and registration checks.

Canonical CI command: cached Gradle 8.13, Java 21, in a visible tmux pane:

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --offline --no-daemon --console=plain build
```

Passed: all five discovered current runtime test mains (entry recovery: 77 checks),
packaging/runner self-tests, common/client compilation and production jar verification.
The embedding persistence suite emits expected malformed/incompatible fixture warnings.
No system installation, embedding server, model download or game bootstrap was required.

Remaining risks: callbacks are simulated, not a live Fabric server/client launch;
GPU rendering, multiplayer movement and native model inference are not validated
by these tests. The broader eleven runtime reports are not all resolved or certified
by this scoped correction. Arbitrary failures during cleanup itself still propagate;
they must not cause carrier flight to be granted outside the carrier.
