# Guarded tilted-support landing follow-up

Branch `feat-runtime-merge-0006`, targeting **dev**, never main. Started clean at
`08974768fbeaa2e18acffb913298bd32d81fce9d`; retained its commits and both navigation
merge parents. The supplied prior session records provider errors, not completed
edits. This follow-up addresses the second review of the natural-landing repair.

## Actual behavior change

The first repair admitted resting tilted contact to validation but its frame
transition guard still rejected the floor's conservative AABB. The old acquisition
test manually assigned an aligned frame, concealing that failure.

`MeshCollision.transitionClear` now uses the endpoint AABB union only as broad
phase. For an overlapping swept box, it certifies a continuous separating plane
against the actual affine collision shape. Face-normal cross products are quadratic
in interpolation time; vertex/normal signed distances are cubic. Checking the
Bernstein control coefficients proves separation across the entire interval, not
merely sampled times. Degenerate/ambiguous certificates and changed collision
shapes remain held. No contact-cell collision exemption was added. Swept bounds
also replace the old endpoint-only five-block culling, which could miss a moving
obstruction passing through the player between distant endpoints.

The navigation preflight and normal frame publisher share the same guard, including
proposed-frame affine body clearance. A real .6-wide body resting on the high edge
of a 10-degree source-flat floor has source feet about .052 blocks above the floor.
The source-floor proof now searches the existing **.12-block** contact band instead
of inspecting only feet minus .05 (which could select body AIR). The contact
threshold was not enlarged; missing floor proof and .121-block gaps still fail.

Final acquisition still requires normal y >= .99, exact live source ownership and
geometry revision, completed body/floor extraction, matching source grid and
semantic coordinate, affine clearance, continuous publication and transactional
rollback. Per-player projection/readiness, teleport reconciliation, stepping,
anti-spam, source-coordinate ownership/growth and embedding profiles/backend are
unchanged. No v1 migration, truncation, dummy embeddings or model dependency was
introduced.

## Regression and build evidence

Original tmux connection `/tmp/tmux-1000/default,30794,11`, origin `%2613`, session
`$11`; retained build pane `%2627`, window `@1595`. Every Gradle invocation used
`tmux-jobs run` with argv and bounded `wait`/`capture` monitoring.

- Red run `015f64e9a13f439cb11b506f61df8945`, exit **1**: with the retained
  broad-phase-only rejection restored locally, the new `AffineTransitionTest`
  failed at `safe tilted resting contact must pass the entire guarded leveling
  transition`. The temporary legacy condition was then replaced with the repair.
- Green run `a01bea7498a2443e94abd6a1b9341101`, exit **0**: all 17 runtime suites
  and packaging checks passed before adding the contact-band boundary assertions.
- Final run `253e3afc303147728530e651c775df13`, exit **0**, **BUILD SUCCESSFUL**,
  all **14 actionable tasks executed**:

  ```sh
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
    --no-daemon --console=plain --rerun-tasks \
    build buildSelfTest runtimeSelfTest verifyProductionJar
  ```

  Discovery executed **17 main-based runtime suites**, including the new affine
  transition suite (**4,768 checks**) and extended walk discovery/acquisition suite
  (**300 checks**). The latter performs 40 real `TraversalSteering.blend` steps:
  each basis-generated fixture frame must pass the production navigation and
  publication guards before committing frame/provenance, retaining ground contact
  and the discovered source owner. Only after those steps does it acquire shallow
  support. It never assigns an aligned replacement frame to skip the guard.

  Negative cases retain rejection for interior swept collision despite clear
  endpoints, distant crossing geometry, real body obstruction, flipping normals,
  missing source proof and out-of-band floor gaps. A deterministic 300-pair affine
  test compares accepted certificates to independent SAT clearance at 33 times;
  those samples are a regression oracle, not the production safety algorithm.
  Existing per-player entry (**28**), teleport reconciliation (**87**), stepping
  (**76**), landing cadence, walk retry/reporting/discovery and four embedding
  safety/persistence/pipeline suites passed. Packaging metadata, descriptor,
  runner discovery/failure tests and `verifyProductionJar` passed for
  `build/libs/mysticism-1.0-SNAPSHOT.jar`.

- `git diff --check` passed; both supplied merge parents remain ancestors.

## Limits

No live Minecraft/Fabric gameplay, multiplayer packets, injected mixin launch,
GPU rendering, IntelliJ launch or real model inference was performed. Projection
frame generation and server I/O in the acquisition test are deterministic fixtures;
its blend math, contact/candidate mapping, continuous guards, frame/provenance
commit and final acquisition/rollback are production code. The continuous proof
is intentionally conservative and can still hold complex safe deformations it
cannot certify. Existing API Guardian/mixin-target warnings, Gradle deprecations
and intentionally malformed embedding-persistence fixture logs remain nonfatal.
This scoped repair does not claim completion of all eleven umbrella issues.
