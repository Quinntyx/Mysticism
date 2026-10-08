package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.vector.*;
import net.minecraft.util.math.Vec3d;

/**
 * Observer frame continuity regressions (no client/GPU boot): a per-player projection frame
 * revision that arrives while the observer is moving must not snap the rendered semantic
 * scene (movement jitter/rubber banding), and the predicted render frame must advance
 * continuously within a movement tick. Stationary revisions and navigation-state
 * transitions remain deliberate visible snaps.
 */
public final class ObserverFrameContinuityTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void near(double actual, double expected, double tolerance) {
        check(Math.abs(actual - expected) <= tolerance, actual + " != " + expected + " (tolerance " + tolerance + ")");
    }
    private static Vec384f axis(int index) {
        float[] data = new float[EmbeddingSpace.DIMENSIONS]; data[index] = 1; return new Vec384f(data);
    }
    private static Vec384f scaled(int index, float value) { return axis(index).mul(value); }
    private static final double SCALE = TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT;
    private static final Basis384f IDENTITY = new Basis384f(axis(0), axis(1), axis(2));
    private static final Vec3d HEAD = new Vec3d(100, 64, -30);
    private static final long EPOCH = 7;

    private static void beginSession() {
        ClientSpiritCache.clear();
        ClientSpiritCache.updateNavigation(true, true, "minecraft:overworld", new Vec3d(1, 2, 3), EPOCH);
        ClientSpiritCache.updateObserver(scaled(0, .01f), IDENTITY, Vec3d.ZERO);
        check(ClientSpiritCache.observerReady(), "valid first authoritative frame becomes ready");
        near(ClientSpiritCache.interpolatedOffset(0).x, 0, 0);
    }

    /** Deep flight: predicted semantic advance plus head movement keeps objects world-stationary. */
    private static void predictionKeepsSceneWorldFixed() {
        beginSession();
        Vec384f object = axis(1);
        Vec3d before = frame(HEAD, 1).project(object);
        Vec3d movement = new Vec3d(.4, 0, 0);
        // Attunement parallel to the movement direction: ordinary deep travel without basis rotation.
        Vec384f target = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, .1f));
        ClientSpiritCache.beginTick();
        TraversalSteering.deepStep(ClientSpiritCache.playerLatentPos, ClientSpiritCache.playerLatentBasis,
                target, movement.x, movement.y, movement.z, false);
        Vec3d after = frame(HEAD.add(movement), 1).project(object);
        near(after.distanceTo(before), 0, 1e-3);
        // Sub-tick interpolation keeps the same object stationary halfway through the tick.
        Vec3d half = frame(HEAD.add(movement.multiply(.5)), .5f).project(object);
        near(half.distanceTo(before), 0, 1e-3);
        // Interpolated basis stays orthonormal while blending.
        Basis384f halfBasis = ClientSpiritCache.interpolatedBasis(.5f);
        near(halfBasis.i.length(), 1, 1e-3); near(halfBasis.j.length(), 1, 1e-3);
        near(halfBasis.i.dot(halfBasis.j), 0, 1e-3);
        // Mirror idempotence: unchanged authoritative mirror never disturbs the predicted frame.
        Vec384f predicted = ClientSpiritCache.playerLatentPos.clone();
        ClientSpiritCache.updateObserver(scaled(0, .01f), IDENTITY);
        check(ClientSpiritCache.playerLatentPos.squareDistance(predicted) == 0, "per-frame refresh keeps predicted render frame");
        near(ClientSpiritCache.interpolatedOffset(.5f).length(), 0, 0);
    }

    /** A sync revision while moving is prediction divergence: rendered positions must not jump. */
    private static void movingSyncRevisionIsCompensated() {
        beginSession();
        Vec384f object = axis(1);
        Vec3d movement = new Vec3d(.4, 0, 0);
        ClientSpiritCache.beginTick();
        TraversalSteering.advance(ClientSpiritCache.playerLatentPos, ClientSpiritCache.playerLatentBasis,
                movement.x, movement.y, movement.z);
        Vec3d movedHead = HEAD.add(movement); // the observer head advanced with the predicted movement
        Vec3d before = frame(movedHead, 1).project(object);
        // Server flew 0.2 blocks further than the client predicted this cycle.
        Vec384f authoritative = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.2 / SCALE)));
        ClientSpiritCache.updateObserver(authoritative, IDENTITY, movement);
        Vec3d offset = ClientSpiritCache.interpolatedOffset(1);
        near(offset.x, .2, 1e-4); near(offset.y, 0, 0); near(offset.z, 0, 0);
        Vec3d after = frame(movedHead, 1).project(object);
        near(after.distanceTo(before), 0, 1e-3);
        // Sub-tick coherence: EVERY tickDelta renders the pre-revision scene, not just the
        // tickDelta=1 endpoint — tickDelta=0 replays the exact pre-revision history and the
        // compensated divergence glides linearly without a partial-offset mix in between.
        near(frame(movedHead, 0).project(object).distanceTo(before), 0, 1e-3);
        near(frame(movedHead, .25f).project(object).distanceTo(before), 0, 1e-3);
        near(frame(movedHead, .5f).project(object).distanceTo(before), 0, 1e-3);
        near(frame(movedHead, .75f).project(object).distanceTo(before), 0, 1e-3);
        near(ClientSpiritCache.interpolatedOffset(0).x, 0, 1e-9);
        near(ClientSpiritCache.interpolatedOffset(.5f).x, .1, 1e-4);
        // The observer semantic point itself stays at its previously rendered position.
        near(frame(movedHead, 1).project(authoritative).distanceTo(movedHead.add(offset)), 0, 1e-6);
    }

    /** Stationary authoritative revisions and navigation transitions are genuine and stay visible. */
    private static void stationaryAndTransitionRevisionsSnap() {
        beginSession();
        Vec384f object = axis(1);
        Vec3d before = frame(HEAD, 1).project(object);
        Vec384f relocated = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.2 / SCALE)));
        ClientSpiritCache.updateObserver(relocated, IDENTITY, Vec3d.ZERO);
        near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 0);
        check(frame(HEAD, 1).project(object).distanceTo(before) > .01, "stationary semantic relocation stays visible");

        beginSession();
        before = frame(HEAD, 1).project(object);
        relocated = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.2 / SCALE)));
        ClientSpiritCache.updateNavigation(true, true, "minecraft:overworld", new Vec3d(1, 2, 3), EPOCH + 1);
        ClientSpiritCache.updateObserver(relocated, IDENTITY, new Vec3d(.4, 0, 0));
        near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 0);
        check(frame(HEAD, 1).project(object).distanceTo(before) > .01, "navigation-state transition stays visible");

        beginSession();
        before = frame(HEAD, 1).project(object);
        Vec384f far = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, 1));
        ClientSpiritCache.updateObserver(far, IDENTITY, new Vec3d(.4, 0, 0));
        near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 0);
        check(frame(HEAD, 1).project(object).distanceTo(before) > 1, "re-anchoring beyond the divergence cap snaps");
    }

    /** Basis-only revisions pivot around the observer instead of translating the scene. */
    private static void basisRevisionPivotsAroundObserver() {
        beginSession();
        Vec384f object = axis(1);
        Vec3d before = frame(HEAD, 1).project(object);
        Vec384f authoritative = ClientSpiritCache.playerLatentPos.clone();
        Basis384f rotated = IDENTITY.clone();
        BasisIntegrator384f.step(rotated, Vec384f.ZERO(), axis(1), 1, 0, 0, .05f);
        ClientSpiritCache.updateObserver(authoritative, rotated, new Vec3d(.4, 0, 0));
        // The observer semantic point is anchored exactly; the scene pivots, it does not translate.
        near(frame(HEAD, 1).project(authoritative).distanceTo(HEAD), 0, 1e-6);
        near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 1e-9);
        // Distant objects legitimately pivot with the basis; continuity is anchored, not frozen.
        check(frame(HEAD, 1).project(object).distanceTo(before) > 1e-6, "basis revision still pivots distant geometry");
    }

    /** Actual shader refresh -> predictor publication -> sub-tick render ordering, without a GPU. */
    private static void shaderRefreshThenPredictorPreservesRevisionHistory() {
        beginSession();
        Vec3d firstMovement = new Vec3d(.4, 0, 0);
        Vec384f firstMirror = ClientSpiritCache.playerLatentPos.clone();
        predict(firstMirror, IDENTITY, firstMovement, true);
        Vec3d head = HEAD.add(firstMovement);
        Vec384f previous = ClientSpiritCache.playerLatentPos.clone();
        Basis384f previousBasis = ClientSpiritCache.playerLatentBasis.clone();
        Vec384f authoritative = previous.clone().add(scaled(0, (float)(.2 / SCALE)));
        Basis384f rotated = IDENTITY.clone();
        BasisIntegrator384f.step(rotated, Vec384f.ZERO(), axis(1), 1, 0, 0, .05f);
        Vec3d movement = new Vec3d(.3, 0, 0);
        Vec3d before = new SpiritGlyphFrame(previous, previousBasis, head).project(axis(1));
        // ShaderManager.updateSession calls refreshObserver earlier at END_CLIENT_TICK and
        // again at WorldRenderEvents.START. Neither refresh may consume a revision/history.
        ClientSpiritCache.refreshObserver(authoritative, rotated);
        ClientSpiritCache.refreshObserver(authoritative, rotated);
        predict(authoritative, rotated, movement, true);
        near(frame(head, 0).project(axis(1)).distanceTo(before), 0, 1e-3);
        near(ClientSpiritCache.interpolatedBasis(0).i.squareDistance(previousBasis.i), 0, 1e-9);
        near(ClientSpiritCache.interpolatedOffset(0).length(), 0, 0);
        near(ClientSpiritCache.interpolatedOffset(1).x, .2, 1e-4);
        Vec384f expectedEnd = authoritative.clone();
        TraversalSteering.advance(expectedEnd, rotated, movement.x, movement.y, movement.z);
        near(ClientSpiritCache.playerLatentPos.squareDistance(expectedEnd), 0, 1e-12);
        for (float fraction : new float[]{0, .25f, .5f, .75f, 1}) {
            SpiritGlyphFrame expected = new SpiritGlyphFrame(previous.clone().converge(expectedEnd, fraction),
                    TraversalSteering.blend(previousBasis, rotated, fraction), head.add(movement.multiply(fraction)),
                    new Vec3d(.2 * fraction, 0, 0));
            near(frame(head.add(movement.multiply(fraction)), fraction).project(axis(1))
                    .distanceTo(expected.project(axis(1))), 0, 1e-3);
            // A render-time refresh must not rewind the tick or roll interpolation history.
            ClientSpiritCache.refreshObserver(authoritative, rotated);
            near(frame(head.add(movement.multiply(fraction)), fraction).project(axis(1))
                    .distanceTo(expected.project(axis(1))), 0, 1e-3);
        }
        check(ClientSpiritCache.interpolatedBasis(.5f).i.squareDistance(previousBasis.i) > 1e-8,
                "basis correction interpolates rather than freezing");
        check(ClientSpiritCache.interpolatedBasis(.5f).i.squareDistance(rotated.i) > 1e-8,
                "basis correction interpolates rather than snapping");
        // Next unchanged mirror rolls history once and keeps both prediction and accumulated offset.
        Vec384f predicted = ClientSpiritCache.playerLatentPos.clone();
        predict(authoritative, rotated, Vec3d.ZERO, true);
        near(ClientSpiritCache.interpolatedPos(0).squareDistance(predicted), 0, 1e-12);
        near(ClientSpiritCache.interpolatedPos(1).squareDistance(predicted), 0, 1e-12);
        near(ClientSpiritCache.interpolatedOffset(0).x, .2, 1e-4);
        near(ClientSpiritCache.interpolatedOffset(1).x, .2, 1e-4);
        near(authoritative.squareDistance(previous.clone().add(scaled(0, (float)(.2 / SCALE)))), 0, 0);
        near(rotated.i.squareDistance(ClientSpiritCache.playerLatentBasis.i), 0, 1e-12);
    }

    /** Start/stop revisions must use CURRENT movement, not ShaderManager's previous tick delta. */
    private static void shaderRefreshUsesCurrentMovement() {
        for (boolean deep : new boolean[]{true, false}) {
            beginSession();
            ClientSpiritCache.updateNavigation(true, deep, "minecraft:overworld", new Vec3d(1, 2, 3), EPOCH);
            Vec384f original = ClientSpiritCache.playerLatentPos.clone();
            Vec3d before = frame(HEAD, 1).project(axis(1));
            Vec384f authoritative = original.clone().add(scaled(0, (float)(.2 / SCALE)));
            Vec3d movement = new Vec3d(.4, 0, 0);
            ClientSpiritCache.refreshObserver(authoritative, IDENTITY); // prior movement was zero
            predict(authoritative, IDENTITY, movement, deep);
            near(ClientSpiritCache.interpolatedOffset(1).x, .2, 1e-4);
            for (float fraction : new float[]{0, .25f, .5f, .75f, 1})
                near(frame(HEAD.add(movement.multiply(fraction)), fraction).project(axis(1)).distanceTo(before), 0, 1e-3);

            beginSession();
            ClientSpiritCache.updateNavigation(true, deep, "minecraft:overworld", new Vec3d(1, 2, 3), EPOCH);
            original = ClientSpiritCache.playerLatentPos.clone();
            predict(original, IDENTITY, movement, deep);
            Vec3d movedHead = HEAD.add(movement);
            before = frame(movedHead, 1).project(axis(1));
            authoritative = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.2 / SCALE)));
            ClientSpiritCache.refreshObserver(authoritative, IDENTITY); // prior movement was nonzero
            predict(authoritative, IDENTITY, Vec3d.ZERO, deep);
            near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 0);
            check(frame(movedHead, 1).project(axis(1)).distanceTo(before) > .1,
                    "stopping revision is visible, not compensated using stale movement");

            beginSession();
            authoritative = ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.2 / SCALE)));
            ClientSpiritCache.updateNavigation(true, deep, "minecraft:overworld", new Vec3d(1, 2, 3), EPOCH + 1);
            ClientSpiritCache.refreshObserver(authoritative, IDENTITY);
            predict(authoritative, IDENTITY, movement, deep);
            near(ClientSpiritCache.interpolatedOffset(1).length(), 0, 0);
        }
    }

    private static void predict(Vec384f authoritative, Basis384f basis, Vec3d movement, boolean deep) {
        ClientLatentPredictor.advanceFrame(authoritative, basis, authoritative, movement,
                deep, true, false, false, true);
    }

    /** clear() resets continuity state; the 3-arg frame constructor offsets projection. */
    private static void frameConstructionAndReset() {
        beginSession();
        ClientSpiritCache.beginTick();
        ClientSpiritCache.updateObserver(ClientSpiritCache.playerLatentPos.clone().add(scaled(0, (float)(.1 / SCALE))),
                IDENTITY, new Vec3d(.4, 0, 0));
        check(ClientSpiritCache.interpolatedOffset(1).x > .05, "compensation recorded before reset");
        // A refresh staged between frames must not survive disconnect/session clear.
        ClientSpiritCache.refreshObserver(scaled(0, .02f), IDENTITY);
        ClientSpiritCache.clear();
        ClientSpiritCache.beginTick(Vec3d.ZERO);
        near(ClientSpiritCache.playerLatentPos.length(), 0, 0);
        near(ClientSpiritCache.interpolatedOffset(0).length(), 0, 0);
        check(!ClientSpiritCache.observerReady(), "clear releases observer readiness");

        Vec384f q = scaled(0, .01f);
        Vec3d plain = new SpiritGlyphFrame(q, IDENTITY, HEAD).project(axis(1));
        Vec3d offset = new Vec3d(.2, 0, 0);
        Vec3d shifted = new SpiritGlyphFrame(q, IDENTITY, HEAD, offset).project(axis(1));
        near(shifted.distanceTo(plain.add(offset)), 0, 1e-9);
        Vec3d nullSafe = new SpiritGlyphFrame(q, IDENTITY, HEAD, null).project(axis(1));
        near(nullSafe.distanceTo(plain), 0, 0);
        try {
            new SpiritGlyphFrame(new Vec384f(axis(0).data(), "wrong-fingerprint"), IDENTITY, HEAD);
            throw new AssertionError("incompatible observer embedding accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static SpiritGlyphFrame frame(Vec3d head, float tickDelta) {
        return new SpiritGlyphFrame(ClientSpiritCache.interpolatedPos(tickDelta),
                ClientSpiritCache.interpolatedBasis(tickDelta), head, ClientSpiritCache.interpolatedOffset(tickDelta));
    }

    public static void main(String[] args) {
        predictionKeepsSceneWorldFixed();
        movingSyncRevisionIsCompensated();
        stationaryAndTransitionRevisionsSnap();
        basisRevisionPivotsAroundObserver();
        frameConstructionAndReset();
        shaderRefreshThenPredictorPreservesRevisionHistory();
        shaderRefreshUsesCurrentMovement();
        System.out.println("Observer frame continuity: " + checks + " checks passed (moving frame revisions stay render-continuous; no live client)");
    }
}
