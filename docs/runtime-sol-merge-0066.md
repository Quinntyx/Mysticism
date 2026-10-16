# Runtime merge-0066: retained two-parent integration

## Candidate lineage and scope

This merge preserves both accepted histories, not only the first-parent implementation:

- merge-0060: `fcf5046be4276f0da6f6fcd98ef2d3fc61bf4500`
- merge-0065: `051007f61cb5527335396f146ae4ecadbe97bb1a`

Work is confined to `feat-runtime-sol-merge-0066`, intended for `dev`, never `main`.
The interrupted merge and its retained repairs were recovered in place; no reset,
stash, replacement history, or other checkout edits were used.

## Reconciliation

- Keep the actual-ground movement/step/crouch kernel shared by prediction and server
  from merge-0065, with merge-0060's descending approach discovery, ordinary contact,
  alignment/arrival and guarded movement adapters. An approach candidate is not
  current grounded authority. Ownership, contact and continuity gates still decide
  acquisition; retained identity never authorizes capture or exit.
- Preserve supported entry and pending local ownership without converting real
  mesh ground into unconditional flight. Keep bounded walk discovery/alignment
  lifetime, usable acknowledged flight on failure and retry, and latest-destination
  cancellation from merge-0060.
- Separate `initialDiscovery` (server-applied entry ownership lifecycle) from wider
  source discovery. Retain render-distance scheduling, bounded source reads/mesh
  work, dimension-qualified ownership/provenance, independent shallow projection,
  and producing-frame support validation from merge-0065. Reused approach proof
  must also match dimension, owner and geometry revision.
- Keep the native v2 preprocessing/tokenizer and full 768-coordinate profile with
  the async default embedded provider, current-profile persistent cache, owning
  executor callbacks and read-only local/cache asset resolution. No v1 migration,
  truncation, dummy embedding or external service prerequisite is introduced.
- Register both the ordered flight-correction acknowledgement and pre-respawn
  transition handoff. Preserve source pose restoration and local transition scene;
  reconnect/unrelated transitions still clear stale state.
- Do not let oversized bucket queries silently omit published mesh surfaces.
  A stationary visibility ray now tests occupancy instead of treating every
  zero-length air ray as blocked.

## Additional regression coverage

`ApproachSupportSweepTest` now exercises deep descent into actual contact followed
by 100 grounded crouch ticks with equal server/client accepted motion; long
sweep/ray/direct-motion collision against a published distant cell; and stationary
rays in air versus occupied mesh. Diagonal final displacement is deliberately not
used as a tunneling oracle: sliding around a finite cell is valid.
`EntrySupportDiscoveryTest` checks the reconciled `initialDiscovery` wiring.

## Checks executed on this combined working tree

Original tmux origin `%3675`, session `$11`, retained window `@2228`:

1. `./gradlew --no-daemon build --rerun-tasks`
   - pane `%3682`, run `563e9d0986934d37b54851532363f3eb`
   - exit 0, `BUILD SUCCESSFUL in 49s`, all 15 tasks executed.
   - all **50 discovered main-based runtime suites** passed, including movement,
     arrival, entry ownership, crouch, retargeting, bounded walk intent, source
     discovery/fairness/dimensions, projection, transition contracts and embedding
     regressions. `buildSelfTest` and `verifyProductionJar` also passed. JUnit's
     disabled `test` task is not represented as runtime coverage.
   - tokenizer: 8,455 checks including 2,058 pinned HF reference cases; 766
     official Unicode grapheme cases. These deterministic checks are not inference.
2. `./gradlew --no-daemon embeddedInferenceProbe -PembeddingModelPath=/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf -PembeddingProvision=false`
   - pane `%3685`, run `8da4204f08d44bff83965bfa1b3834c0`
   - exit 0, `BUILD SUCCESSFUL in 21s`.
   - exact approved 957,680,480-byte asset verified by pinned SHA-256
     `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`.
   - real in-process JNI `b10682-5ea1b124e`, 768 coordinates, independently checked
     L2 output and repeated multilingual/landmark text; actual token overflow was
     rejected rather than truncated. Endpoint deliberately unusable; no HTTP
     service. Both default-helper generations performed native readiness, restored
     all cached coordinates exactly, composed vectors and dispatched completion to
     the owning executor. No replacement model was downloaded.
3. Conflict-marker inspection and `git diff --check` passed before staging.

Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar` (remapped production jar).
Retained pane output is the execution evidence; earlier failed attempts and parent
handoffs are not substituted for these successful combined-candidate runs.

## Limits / remaining validation

No live Minecraft client/server gameplay, IntelliJ launch, GL/shader inspection,
loading-screen timing, or full-world performance run was performed in this
recovery. Automated policy/wiring/mesh tests do not prove the user-visible eleven
symptoms are all resolved. In particular, Kuwahara/fog and visual seamlessness
still require in-game inspection. Native inference emitted CPU/no-GPU and missing
newline/mask-token warnings; actual forward passes and coordinate checks passed,
but this is not a semantic-quality assessment. Generated-source scheduling tests
exercise bounded production read paths under fixtures, not a live world benchmark.
The model must remain available at its authorized local/cache location for a
normal launch; it is intentionally not bundled in the mod jar.
