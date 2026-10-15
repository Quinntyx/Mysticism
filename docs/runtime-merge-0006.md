# Runtime navigation merge 0006

Follow-up scoped review repair and verification: [tilted natural landing](tilted-natural-landing-repair.md).

Scope: retained `feat-runtime-merge-0006`, for eventual integration into **dev**, never main.
The retained checkout was mid-merge: HEAD `927bae4ee877414cf338d505720a4066360be383`
with merge parent `d393144f3e5faee8b21647c69a31e9a976f06579` and one unresolved
`SpiritNavigationService` conflict. The previous session recorded only provider timeouts.
Both parents and their staged changes were preserved.

## Integrated behavior

- Per-server/per-player navigation, terrain and motion state remain independent.
  Incoming deep touch does not cancel the recipient's pending walk/landing/blend.
- Teleports reset prediction at both epoch delivery and actual client arrival,
  clear relative-axis residual momentum on both peers, and rebaseline server motion.
  Arrival also clears the arriving player's stale fall/step/resting and basis-blend
  state and pending terrain approaches; it retains mode, readiness, captured target,
  capture request and anti-spam cooldown. Old ground contact is cleared on both peers.
- Shared custom-mesh stepping retains depenetration and spends only the remaining
  horizontal movement budget. Ordinary shallow step descents tolerate temporary
  loss of support, without treating every height change as deep takeoff.
- Resting airborne flight requests real terrain acquisition. The merge additionally
  allows contact to start bounded source discovery *before* a walking candidate is
  known, rather than making a known candidate a prerequisite for discovering it.
- Failed explicit walk requests preserve airborne history so a subsequent natural
  landing is not blocked by expiry or the explicit-request cooldown. Pending and
  cooling-down explicit requests cannot repeatedly restart validation.
- Actual discovery, semantic-anchor waiting and acquired-support alignment are
  separately reported by `/spirit` and periodic progress. Expiry retains the real
  extraction phase while also applying the failure cooldown and teardown policy.
- Cross-owner discovery keeps producer A's placement/binding and a separate target
  B. Walking commits only after exact body/floor ownership, current proof, source
  grid/coordinate agreement, body clearance and continuous mesh publication pass.

No embedding backend, native-dimensionality/profile, source octree storage/growth,
render projection or safe-substitute terrain changes were made in this scope.

## Actual verification

Installed Java 21, cached Gradle 8.13; original tmux connection
`/tmp/tmux-1000/default,30794,11`, origin `%2024`, session `$11`.
Commands ran through the tmux-jobs skill with explicit argv, bounded monitoring,
and retained pane `%2041` in window `@1307`.

Final command (environment `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --no-daemon --console=plain --rerun-tasks \
  build buildSelfTest runtimeSelfTest verifyProductionJar
```

Final run token `3eebfb63c36944528e14b18492a0be9f`: exit **0**, **BUILD SUCCESSFUL**,
14 tasks executed. All **15** main-based suites were visibly discovered/executed:
mesh stepping (76), natural contact (9), walk discovery/acquisition (115), embedding
callback isolation (28), embedding engine safety (184), embedding persistence (90),
embedding pipeline (135), feedback (9), natural landing policy (36), navigation
report (14), per-player lifecycle (28), teleport arrival (87), walk/natural contract
(22), retry gate (11), walk target policy/wiring (27).
Packaging fixtures, descriptor-only rejection fixtures and runner discovery/order/
assertion/failure checks passed. Common/client compilation and remapped production
jar verification passed (`build/libs/mysticism-1.0-SNAPSHOT.jar`).
The earlier non-rerun build also passed (token `b217aa11aa41408d8cbead24ccbed838`).
`git diff --check` passed.

New merged-path regressions cover truthfully distinguished discovery/anchor/alignment
status; an undiscovered foreign-owner contact triggering natural validation followed
by the real discovery/candidate/acquisition transaction; arrival state reset preserving
another player's session/readiness and the arriving player's captured target/capture.
Server I/O/mesh construction/transport in the acquisition fixture are deterministic
fixtures, not an actual running world.

## Remaining risks / not verified

No live Fabric client/server, multiplayer packets, injected mixin boot, GPU gameplay,
IntelliJ launch or model inference was performed. This unit does **not** claim to fix
or verify all eleven umbrella issues, notably seamless entry, locality generation,
shader effects and in-process embedding backend (separate scopes).

The retained 200-tick acquisition bound still leaves invalid/unready geometry deep;
failed natural acquisition requires renewed airtime before another natural attempt,
whereas explicit walk expiry preserves airtime. No substitute floor/teleport is used.
Extraction scheduling under load and real delayed network reconciliation need gameplay
validation. Existing API Guardian/mixin-target compiler warnings, Gradle deprecations,
and expected malformed-persistence fixture logs remain; they did not fail verification.
