# Retained runtime merge 0050

Integration of `56f966ec49faab8a109c06446ff5933a72db3a2d` and
`9aa8fa17350d57aa093b2016eb03c044c13b83e4` on
`feat-runtime-sol-merge-0050`. Both retained histories are preserved. The older
walking/tokenizer handoff documents describe their individual branch checks;
this document describes the combined candidate, not all eleven runtime fixes.

## Integrated behavior

- Entry starts shallow with flight off. A real accepted local mesh floor can
  retain walking while initial source discovery or exact octree ownership is
  pending, including worker completion queued for server-thread application.
  Current empty ownership is not mistaken for completed discovery. Local
  producer provenance and floor identity must match; this is not ownership,
  capture, source-entity or exit authority. No synthetic floor is added.
- The same mode-only hold applies after supported walking has been acquired,
  including deep/shallow switches and later ownership revalidation. It is bounded
  to 100 ticks per episode and resets only on actual confirmed support. Jump
  grace remains 14 ticks; settled-unowned ground and unsupported edges do not
  gain an indefinite hold. Public source mapping/exit never uses retained IDs
  as exact ownership proof.
- Deep walk requests use one per-player alignment state and one lifetime.
  Duplicate toggles cannot restart either. Changed real contacts rebase from the
  live basis without overwriting q, captured attunement or the player's pose.
  Progress can reset a 200-tick alignment stall window, but repeated rebindings
  cannot; the whole request remains bounded to 2400 ticks. Late source discovery
  has a separate 1200-tick wait. Real pending source proof pauses alignment
  stalls even with an already-aligned visible candidate; it cannot evade the
  absolute lifetime bound.
- Expiry clears transient navigation/prediction intent and acknowledges usable
  deep flight, with a specific failure reason. It retains terrain proof for a
  retry; explicit cancellation/session or source unload still releases it.
  Acquisition retains exact ownership, body/floor clearance and whole-frame
  render/collision continuity validation. Client/server support prediction uses
  the same bounded actual-motion integration, never support-target attraction.
- In-process preprocessing uses the bundled hash-pinned Nomic v2-MoE XLM-R
  Unigram tokenizer and pinned Unicode grapheme data, not v1 WordPiece or generic
  NFKC. The document prefix is shared with the existing HTTP provider. BOS/EOS,
  padding/masks and special tokens preserve upstream IDs; context overflow
  fails rather than truncating. Native 768-coordinate embeddings, independent
  per-player projection and source-coordinate octree growth are not replaced.

## Verification

Final canonical full build (installed Gradle 8.13 / Java 21; no task
exclusions, classpath overrides, extra software, live embedding service or game
bootstrap):

```sh
/home/zlare/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle --no-daemon --console=plain --continue --rerun-tasks build runtimeSelfTest buildSelfTest verifyProductionJar
```

**Final verification passed on 2026-10-15**, including the final slow-proof /
alignment-stall refinement and its regression in source commit
`c15b243e9ef6106859acd0cdc27d1b07c0d6baff`. The required helper recovered after
the earlier workspace-busy blocker; no direct-shell workaround was used. The
resumed connection's original origin was `%3064`, session `$11`. Retained build
pane `%3065`, run `d680821514c74767a2704c037ff16ff4`: exit 0, 3m43s,
15 actionable tasks executed (`--rerun-tasks`). Subsequent changes only update
this handoff document.

All eleven runtime suites were discovered and passed:

| Suite | Checks |
| --- | ---: |
| EntrySupportDiscoveryTest | 124 |
| InitialSourceDiscoveryTest | 129 |
| AsyncEmbeddingCallbackIsolationTest | 28 |
| AsyncEmbeddingEngineSafetyTest | 184 |
| EmbeddingPersistenceTest | 90 |
| EmbeddingPipelineTest | 135 |
| NomicV2TokenizerTest | 8,455 (+766 official grapheme cases) |
| SpiritEntrySupportedWalkingTest | 555 |
| SupportedWalkingIntegrationTest | 93 |
| WalkIntentLifetimeTest | 9,457 |
| WalkingModePolicyTest | 14,062 |

Packaging/runner fixtures, decoded metadata, descriptor rejection regressions,
and `verifyProductionJar` passed for `build/libs/mysticism-1.0-SNAPSHOT.jar`.
Malformed persistence fixtures intentionally emit rejection warnings/exceptions;
their assertions passed. Compilation still reports existing API Guardian,
mixin-target and deprecated API warnings; Gradle reports deprecated features.

Runtime checks are main-based tests under `src/runtimeTest`, discovered by
`runtimeSelfTest`; Gradle's framework `test` action is intentionally skipped.
The full build discovered eleven runtime suites, ran packaging/runner
fixtures and verified the remapped production artifact. Checks include actual SAT
mesh contact and accepted-cell inverse primitives, plus source-contract checks
of production wiring. They do not instantiate a Minecraft player/server.
Tokenizer fixtures include 2,058 exact HF reference cases and all 766 official
Unicode 17 grapheme-boundary cases. Existing embedding-provider tests use test
doubles, not real weights.

No Minecraft/IntelliJ launch, gameplay, packet-latency, GPU or native model
inference verification was performed. The default embedding provider remains
HTTP: this scoped tokenizer unit is ready for consumption by the separately
owned local provider but does not remove the overall external-server prerequisite.
The overall in-process inference requirement and other out-of-scope runtime
reports remain unverified/unresolved here.
