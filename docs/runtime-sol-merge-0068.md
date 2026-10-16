# Runtime merge-0068: combined retained candidate

## Lineage and recovery

This is the in-place integration of the exact accepted parents:

- merge-0058: `7a07db84bf780f20a2f173aedbfc5f655c22cdb4`
- merge-0066: `da2420dcd7a22a8c6a1d869cd8ee7a125b1b1bba`

The target is `feat-runtime-sol-merge-0068`, intended for `dev`, never `main`.
The interrupted merge, commits and existing repairs were retained. No reset,
stash, substitute model, v1 migration, vector truncation or external-service
prerequisite was introduced. No other checkout was edited; no agents were delegated.

## Reconciliation

- Preserve actionable discovery/navigation diagnostics and source-coordinate
  ownership while combining dimension-qualified, render-distance discovery with
  loaded-terrain backfill, changed-terrain invalidation and bounded worker/mesh work.
  The actual source session uses separate read, requested-discovery and background
  lanes: wide scans cannot monopolize ordinary reads or starve explicit entry work.
  The spatial/age-prioritized hint queue and conservative retry coverage remain.
  Parked jobs retain resource ownership and are invalidated/restarted appropriately.
- Preserve coherent producing-frame geometry for custom mesh rendering, collision,
  shallow distortion and support proof. Readiness follows the applied frame/revision,
  not merely a completed asynchronous read. Independent per-player deep/shallow
  projections and source-coordinate octree growth remain in place.
- Retain swept mesh contact/step/crouch, grounded entry, bounded supported-walking
  grace, adaptive approach/arrival, cancellation and latest-destination arbitration.
  Preserve ordered pose synchronization, correction provenance, prediction history
  and the acknowledged flight-correction barrier rather than letting competing
  reconciliation implementations own prediction simultaneously.
- Connect the same initial source request to both `SourceDiscoveryAttempt`
  (truthful diagnostics) and `InitialSourceDiscovery` (pending walking lifecycle).
  Worker completion must not settle either before application on the server thread.
  `EntrySupportDiscoveryTest` additionally checks successful-empty and exceptional
  completion, single application and rejection of delayed duplicate completion.
- Preserve seamless transition handoff/source-pose restoration and both post-process
  probes. Kuwahara/fog resources and wider fog behavior from merge-0058 remain;
  resize/reload/normal-startup contracts are still covered by deterministic suites.
- Combine the selected native-v2 tokenizer/preprocessing, complete 768-coordinate
  output, async default embedded provider, read-only exact-asset discovery, profile-
  keyed persistent cache and owning-executor callbacks. The optional probe-only
  downloader is not normal Minecraft startup. Ordinary build does not require a
  model, display or server. Both opt-in probes remain independently registered;
  the build-contract regression checks their combined task wiring.

Merge-specific regressions also retain production read-lane/fairness fixtures,
frame-revision/shallow projection assertions, supported-walking/retarget wiring and
full-coordinate native-output checks. Stale parent assertions/fixtures were updated
where the combined API or lifecycle changed; failing runs were not counted as passes.

## Checks on the combined tree

Original tmux session `$11`, origin `%3716`; installed Java 21 and Gradle 8.13.
All commands used the required `tmux-jobs` helper with explicit argv, bounded
monitoring and retained pane/run handles.

### Canonical build

`gradle --offline --no-daemon --console=plain --rerun-tasks build`

- Pane `%3717`, run `716a5a3435204e04a76fd8daeb1a8219`.
- Exit **0**, `BUILD SUCCESSFUL in 43s`, **15 tasks executed**.
- **83 discovered main-based runtime suites passed**, plus `buildSelfTest` and
  `verifyProductionJar`. The disabled JUnit `test` task is not counted as coverage.
- Covers movement ordering/timing/prediction, mesh tunneling/ground/crouch/arrival,
  walking/cancellation/retarget, source scheduling/backfill/dimensions/revisions,
  filtering/fog contracts, tokenizer, embedding lifecycle/cache and packaging.
- Artifact: `build/libs/mysticism-1.0-SNAPSHOT.jar` (remapped production jar).

### Real model and real OpenGL

`xvfb-run -a gradle --offline --no-daemon --console=plain embeddedInferenceProbe -PembeddingProvision=false kuwaharaGpuTest`

- Pane `%3722`, run `d3a3f9a6f5134e4f857f392bf9995c65`.
- Exit **0**, `BUILD SUCCESSFUL in 19s`.
- **No model-path override**: default read-only runtime discovery selected the
  authorized preprovisioned cache asset:
  `/home/zlare/.cache/mysticism/models/sha256-a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f.gguf`.
  Exact size **957,680,480 bytes**, complete-file SHA-256
  `a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f`
  validated before native loading. No weights were downloaded.
- Real JNI **b10682-5ea1b124e**, **768 coordinates**, independently checked L2
  normalization, stable repeated landmark/multilingual texts, native tail retained
  and overlength input rejected rather than truncated. The HTTP endpoint was
  deliberately unusable. Both default-helper generations performed real native
  readiness; persisted coordinates restored exactly, composition and owning-client-
  executor callbacks passed. Initialization returned in 42/35 ms while native
  preparation continued on workers. These are observations, not performance guarantees.
- OpenGL **4.6 Core**, Mesa **26.2.3-arch1.1**, **llvmpipe LLVM 22.1.8** under Xvfb.
  Executed packaged Kuwahara/fog programs at 25/17/33/25 pixel target sizes in
  RGBA32F and Minecraft-like RGBA8. Enabled final impulse changed from `.9` to
  `.1888889` / `.19215688`; wider fog retained useful foreground/distant contrast
  and geometry/sky horizon across tested tiers. This is actual GL execution on a
  software renderer, not Minecraft gameplay or physical-GPU acceptance.
- Earlier explicit-path probe on the same combined tree also passed: pane `%3722`,
  run `59bf978eabf942c89b7093414ebaa7a1`, exit 0, 17 seconds. The newer run above is
  the decisive evidence for normal local-cache asset selection.

Conflict-marker inspection and `git diff --check` passed before staging.

## Limits / acceptance still required

No live Minecraft gameplay, IntelliJ launch, multiplayer/network-latency session,
resource-reload callback inspection, visual loading-screen timing or full-world
throughput benchmark was performed. Offline fixtures and offscreen shaders do not
prove all eleven original symptoms are resolved in gameplay. Recheck supported
entry/exit/re-entry, flight-off and delayed arrival, `/tp` mesh readiness, deep/shallow
switches, custom-mesh edge/crouch/tunneling, live render-distance discovery, resize
and reload in Minecraft. Native CPU/no-GPU and missing newline/mask-token warnings
were emitted despite successful forward passes; this is not a semantic-quality
assessment. Gradle reports deprecated features for Gradle 9. The approved external
model file must remain locally available; it is intentionally not bundled in the jar.
