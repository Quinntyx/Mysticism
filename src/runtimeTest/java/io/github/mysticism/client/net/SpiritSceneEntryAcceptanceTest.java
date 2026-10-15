package io.github.mysticism.client.net;

import io.github.mysticism.net.SpiritFramePayload;
import io.github.mysticism.net.SpiritScenePayload;
import java.util.List;
import java.util.UUID;

/**
 * Regression for repeated entry / reconnect projection coherence: a re-entering or reconnecting
 * observer must accept the NEW transport session exactly when it is newer than anything already
 * authenticated. Same-connection re-entry always advances the generation on the same connection
 * nonce; a physical reconnect restarts the generation on a new nonce and is only admissible after
 * the connection-scoped high-water mark was reset on JOIN. Without these guards a second
 * /spirit enter would either replay a stale scene or be rejected forever.
 */
public final class SpiritSceneEntryAcceptanceTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID NONCE_A = UUID.randomUUID();
    private static final UUID NONCE_B = UUID.randomUUID();

    private static SpiritFramePayload.Session session(UUID nonce, long generation) {
        return new SpiritFramePayload.Session(nonce, PLAYER, "mysticism:spirit", generation, generation);
    }
    private static SpiritScenePayload scene(SpiritFramePayload.Session session, long sequence) {
        return new SpiritScenePayload(session, sequence, true, false, null, List.of(), List.of(), List.of(), List.of());
    }

    public static void main(String[] args) {
        firstVisit();
        repeatedEntry();
        reconnect();
        staleScenes();
        System.out.println("SpiritSceneEntryAcceptanceTest passed: " + checks + " checks");
    }

    private static void firstVisit() {
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.snapshot().isEmpty(), "fresh client has no scene");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 1), 1)), "bootstrap scene accepted");
        check(SpiritSceneClient.snapshot().isPresent() && SpiritSceneClient.session().isPresent(), "bootstrap retained");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 1), 1)), "duplicate bootstrap rejected");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 1), 2)), "newer sequence accepted");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 1), 2)), "stale sequence rejected");
    }

    private static void repeatedEntry() {
        // Same connection, second /spirit enter: the server invalidates and reactivates the
        // observer connection, advancing the generation on the SAME nonce.
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 1), 5)), "first visit bootstrap accepted");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 2), 1)), "re-entry session with advanced generation accepted");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 2), 1)), "re-entry bootstrap not replayable");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 2), 2)), "post re-entry sequence accepted");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 3), 1)), "third entry accepted");
        // A transport epoch/generation mismatch is never admissible.
        check(!SpiritSceneClient.accept(scene(new SpiritFramePayload.Session(NONCE_A, PLAYER, "mysticism:spirit", 4, 3), 1)),
                "frame epoch must equal generation");
    }

    private static void reconnect() {
        // Physical reconnect: JOIN resets the connection-scoped high-water mark, so the fresh
        // connection's restarted generation is admissible.
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 3), 9)), "pre-reconnect scene accepted");
        SpiritSceneClient.resetConnection();
        check(SpiritSceneClient.accept(scene(session(NONCE_B, 1), 1)), "post-reconnect bootstrap accepted after reset");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 1), 2)), "old-connection scene rejected after reset");
        // Without the JOIN reset a restarted generation on a new nonce must NOT be accepted:
        // this is exactly the stale-scene replay a mid-session reconnect would otherwise allow.
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 3), 9)), "pre-reconnect scene accepted");
        // (no resetConnection here)
        check(!SpiritSceneClient.accept(scene(session(NONCE_B, 1), 1)), "new nonce with restarted generation rejected without reset");
        // clear() alone keeps the high-water mark (world change within a connection) so a
        // re-entry on the same connection cannot be downgraded by a cleared scene buffer.
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 3), 9)), "baseline accepted");
        SpiritSceneClient.clear();
        check(SpiritSceneClient.snapshot().isEmpty(), "scene cleared on world change");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 3), 1)), "same-generation replay rejected after world change");
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 4), 1)), "next re-entry generation accepted after world change");
    }

    private static void staleScenes() {
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
        check(SpiritSceneClient.accept(scene(session(NONCE_A, 2), 4)), "baseline accepted");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 2), 3)), "sequence regression rejected");
        check(!SpiritSceneClient.accept(scene(session(NONCE_A, 1), 1)), "cross-connection generation downgrade rejected");
        check(SpiritSceneClient.snapshot().isPresent(), "rejected scenes never clear an accepted snapshot");
        SpiritSceneClient.resetConnection();
        SpiritSceneClient.clear();
    }
}
