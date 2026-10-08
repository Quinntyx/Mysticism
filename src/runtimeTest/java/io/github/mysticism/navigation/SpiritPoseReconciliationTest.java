package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.LatentBasis;
import io.github.mysticism.component.LatentPos;
import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime regressions for spirit movement prediction reconciliation: the four-tick
 * authoritative pose sync must never regress healthy in-flight client prediction
 * (reported movement jitter/rubber banding), while genuine divergence still snaps
 * to accepted server movement. Server persistence semantics stay unchanged.
 */
public final class SpiritPoseReconciliationTest {
    private static int assertions;
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private static Vec384f vector(float first) {
        float[] values = new float[EmbeddingSpace.DIMENSIONS];
        values[0] = first;
        return new Vec384f(values);
    }
    private static Vec384f moved(Vec384f from, int steps) {
        Vec384f result = from.clone();
        for (int i = 0; i < steps; i++) TraversalSteering.advance(result, new Basis384f(), 1, 0, 0); // one block per step
        return result;
    }
    private static double semanticBlocks(Vec384f pose, Vec384f origin) {
        return Math.sqrt(pose.squareDistance(origin)) * 96.0; // advance converted back to blocks
    }

    private static void policy() {
        check(SpiritPoseReconciliation.positionFraction(0f) == 0f, "Zero divergence reconciles nothing");
        float previous = -1f;
        for (float sq = 0f; sq <= SpiritPoseReconciliation.SNAP_POSITION * SpiritPoseReconciliation.SNAP_POSITION * 1.01f; sq += 1e-5f) {
            float fraction = SpiritPoseReconciliation.positionFraction(sq);
            check(fraction >= 0f && fraction <= 1f, "Fraction stays in range: " + sq);
            check(previous <= fraction, "Fraction is monotone");
            previous = fraction;
        }
        check(SpiritPoseReconciliation.positionFraction(SpiritPoseReconciliation.SNAP_POSITION * SpiritPoseReconciliation.SNAP_POSITION) == 1f,
                "Snap threshold reconciles fully");
        check(SpiritPoseReconciliation.positionFraction(Float.NaN) == 1f, "Nonfinite divergence is treated as broken prediction");
        check(SpiritPoseReconciliation.positionFraction(-1f) == 1f, "Negative divergence is treated as broken prediction");
        check(SpiritPoseReconciliation.positionFraction(1e-8f) < 0.001f, "Healthy phase error corrects imperceptibly");
        check(SpiritPoseReconciliation.basisFraction(0f) == 0f
                && SpiritPoseReconciliation.basisFraction(SpiritPoseReconciliation.SNAP_BASIS * SpiritPoseReconciliation.SNAP_BASIS) == 1f,
                "Basis ramp spans the same policy");
        check(Math.abs(SpiritPoseReconciliation.UNITS_PER_BLOCK * 96 - 1f) < 1e-4f, "Semantic units per block match the steering scale");
    }

    private static void positionSession() {
        var session = new SpiritPoseReconciliation.Session();
        var server = vector(1);
        check(session.awaitingPositionResync(), "Fresh session awaits its first resync");
        check(session.reconcilePosition(server, null) == server, "First sync is adopted");
        check(!session.awaitingPositionResync(), "First sync consumed the resync");

        // Healthy prediction one tick ahead of accepted movement: kept (identity preserved),
        // only corrected toward the server pose by an imperceptible fraction.
        var predicted = moved(server, 1);
        var beforePull = predicted.clone();
        check(session.reconcilePosition(server, predicted) == predicted, "In-flight prediction survives the sync");
        check(predicted.squareDistance(beforePull) < predicted.squareDistance(server),
                "Kept prediction stays overwhelmingly on the predicted side");

        // Persistent divergence converges smoothly instead of snapping: each sync moves the
        // predicted pose strictly toward the server pose, by less than the full divergence.
        var stuck = vector(1);
        var drifting = moved(vector(1), 2);
        float before = stuck.squareDistance(drifting);
        var pulled = session.reconcilePosition(stuck, drifting);
        check(pulled == drifting, "Partial convergence keeps the predicted object");
        float after = stuck.squareDistance(drifting);
        check(after < before, "Partial convergence moves toward the server pose");
        check(after > 0f, "Partial convergence does not snap below the threshold");

        // Genuine divergence snaps to the accepted server movement.
        var broken = moved(vector(1), 10);
        var snapped = session.reconcilePosition(vector(1), broken);
        check(snapped != broken && snapped.squareDistance(vector(1)) == 0f, "Beyond the threshold the server pose is adopted");

        // Reset: the next sync is adopted fully (mode/epoch transitions).
        session.reset();
        check(session.awaitingPositionResync(), "Reset re-arms full adoption");
        var freshServer = vector(2);
        check(session.reconcilePosition(freshServer, vector(1)) == freshServer, "Post-reset sync is adopted");

        // Embedding-profile mismatch cannot corrupt reconciliation: adopt, never throw.
        var session2 = new SpiritPoseReconciliation.Session();
        session2.reconcilePosition(vector(1), null);
        var foreign = new Vec384f(new float[EmbeddingSpace.DIMENSIONS], "other-profile");
        check(session2.reconcilePosition(foreign, vector(1)) == foreign, "Profile mismatch adopts the server pose");
    }

    private static void basisSession() {
        var session = new SpiritPoseReconciliation.Session();
        var server = new Basis384f();
        check(session.reconcileBasis(server, null, false) == server, "First basis sync is adopted");
        // Server-driven alignment (support/landing approach) always adopts: the client cannot
        // reproduce the server's bounded alignment blend.
        var predicted = new Basis384f();
        predicted.i = vector(1);
        check(session.reconcileBasis(server, predicted, true) == server, "Server-driven alignment adopts every sync");
        // Healthy rotation phase error: kept.
        check(session.reconcileBasis(server, predicted, false) == predicted, "Healthy basis prediction survives the sync");
        // Persistent divergence converges via an orthonormalized blend, never a snap, below threshold.
        // (Predicted frames are orthonormal in production: BasisIntegrator applies exact rotations.)
        var rotated = new Basis384f();
        float theta = 0.1f, sin = (float) Math.sin(theta), cos = (float) Math.cos(theta);
        rotated.i = server.i.clone().mul(cos).add(server.j.clone().mul(sin));
        rotated.j = server.i.clone().mul(-sin).add(server.j.clone().mul(cos));
        rotated.k = server.k.clone();
        var before = rotated.clone();
        var blended = session.reconcileBasis(server, rotated, false);
        check(blended != rotated && blended != server, "Partial basis convergence produces a blended frame");
        float errorBefore = SpiritPoseReconciliation.basisError(server, before);
        float errorAfter = SpiritPoseReconciliation.basisError(server, blended);
        check(errorAfter < errorBefore, "Partial basis convergence moves toward the server frame");
        float orthonormality = blended.i.dot(blended.j) + blended.i.dot(blended.k) + blended.j.dot(blended.k);
        check(Math.abs(orthonormality) < 1e-3f, "Blended basis stays orthonormal");
        // Beyond threshold: snap to the server frame.
        var broken = new Basis384f();
        broken.i = server.i.clone().mul(-1);
        broken.j = server.j.clone().mul(-1);
        check(session.reconcileBasis(server, broken, false) == server, "Broken basis prediction snaps to the server frame");
        // Independent per-channel resync.
        var session2 = new SpiritPoseReconciliation.Session();
        session2.reset();
        check(session2.reconcileBasis(server, null, false) == server, "Post-reset basis sync is adopted");
    }

    private static void componentPersistenceUnchanged() {
        LatentPos.SyncReconciler mustNotRun = (incoming, current) -> { throw new AssertionError("Server position sync must not consult prediction"); };
        LatentBasis.SyncReconciler mustNotRunBasis = (incoming, current) -> { throw new AssertionError("Server basis sync must not consult prediction"); };

        // Server instances keep byte-identical persistence even with a reconciler installed.
        var server = new LatentPos(false);
        server.set(vector(2));
        server.setSyncReconciler(mustNotRun);
        var tag = new NbtCompound();
        server.writeToNbt(tag, null);
        var loaded = new LatentPos(false);
        loaded.setSyncReconciler(mustNotRun);
        loaded.readFromNbt(tag, null);
        check(loaded.get().squareDistance(vector(2)) == 0f, "Server load adopts persisted vectors");
        var roundtrip = new NbtCompound();
        loaded.writeToNbt(roundtrip, null);
        check(roundtrip.getIntArray("v").length == EmbeddingSpace.DIMENSIONS, "Persistence roundtrip unchanged");

        var serverBasis = new LatentBasis(false);
        var written = new Basis384f();
        serverBasis.set(written);
        serverBasis.setSyncReconciler(mustNotRunBasis);
        var basisTag = new NbtCompound();
        serverBasis.writeToNbt(basisTag, null);
        var loadedBasis = new LatentBasis(false);
        loadedBasis.setSyncReconciler(mustNotRunBasis);
        loadedBasis.readFromNbt(basisTag, null);
        check(SpiritPoseReconciliation.basisError(loadedBasis.get(), written) == 0f, "Server load adopts persisted basis");

        // Corrupt/legacy data resets to the zero pose without reconciliation interference.
        var corrupt = new NbtCompound();
        EmbeddingNbt.stamp(corrupt);
        corrupt.putIntArray("v", new int[384]);
        var legacy = new LatentPos(false);
        legacy.setSyncReconciler(mustNotRun);
        legacy.readFromNbt(corrupt, null);
        check(legacy.get().squareDistance(Vec384f.ZERO()) == 0f, "Incompatible persisted vector resets to zero");
    }

    private static void clientArrivalReconciliation() {
        // Client-side instances route every authoritative sync through the installed policy.
        var session = new SpiritPoseReconciliation.Session();
        var component = new LatentPos(true);
        component.setSyncReconciler((serverValue, existing) -> session.reconcilePosition(serverValue, existing));
        var server = vector(1);
        component.readFromNbt(encoded(server), null);
        check(component.hasSyncReconciler(), "Hook is installed");
        check(component.get().squareDistance(server) == 0f, "First client sync adopts the server pose");
        component.readFromNbt(encoded(server), null);
        check(component.get().squareDistance(server) == 0f, "Repeated identical syncs keep the adopted pose");

        // Beyond-threshold divergence snaps the component to the accepted pose.
        var session2 = new SpiritPoseReconciliation.Session();
        var component2 = new LatentPos(true);
        component2.setSyncReconciler((serverValue, existing) -> session2.reconcilePosition(serverValue, existing));
        component2.readFromNbt(encoded(vector(1)), null);
        component2.readFromNbt(encoded(vector(9)), null);
        check(component2.get().squareDistance(vector(9)) == 0f, "Large divergence snaps the client component to the server pose");

        // Corrupt sync payload hands the policy a decoded zero pose instead of throwing.
        var session3 = new SpiritPoseReconciliation.Session();
        var component3 = new LatentPos(true);
        List<Vec384f> seen = new ArrayList<>();
        component3.setSyncReconciler((serverValue, existing) -> { seen.add(serverValue); return session3.reconcilePosition(serverValue, existing); });
        var bad = new NbtCompound();
        EmbeddingNbt.stamp(bad);
        bad.putIntArray("v", new int[3]); // wrong dimensionality
        component3.readFromNbt(bad, null);
        check(seen.size() == 1 && seen.get(0).squareDistance(Vec384f.ZERO()) == 0f, "Corrupt sync decodes to the zero pose for the policy");
        check(component3.get().squareDistance(Vec384f.ZERO()) == 0f, "Corrupt sync leaves the component at the decoded pose");

        // Basis component: same arrival contract.
        var session4 = new SpiritPoseReconciliation.Session();
        var basisComponent = new LatentBasis(true);
        basisComponent.setSyncReconciler((serverValue, existing) -> session4.reconcileBasis(serverValue, existing, false));
        var basisTag = new NbtCompound();
        EmbeddingNbt.stamp(basisTag);
        basisTag.putIntArray("b", new Basis384f().toBits());
        basisComponent.readFromNbt(basisTag, null);
        check(SpiritPoseReconciliation.basisError(basisComponent.get(), new Basis384f()) == 0f, "Client basis sync adopts the server frame");
        basisComponent.readFromNbt(basisTag, null);
        basisComponent.readFromNbt(basisTag, null);
        check(SpiritPoseReconciliation.basisError(basisComponent.get(), new Basis384f()) == 0f, "Repeated identical basis syncs are stable");

        // Without an installed hook the client component adopts plainly (pre-prediction window).
        var plain = new LatentPos(true);
        plain.readFromNbt(encoded(vector(3)), null);
        check(plain.get().squareDistance(vector(3)) == 0f, "Client component without a hook adopts the sync");
    }

    private static NbtCompound encoded(Vec384f value) {
        var tag = new NbtCompound();
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", value.toBits());
        return tag;
    }

    /**
     * The reported regression: with a two-tick acceptance delay and a four-tick sync, blind
     * overwrite regresses the rendered pose by the in-flight movement at every sync, while the
     * reconciliation policy keeps prediction intact and bounded. A change that reintroduces
     * sync-regression jitter fails here.
     */
    private static void walkingFlightSawtooth() {
        double perTick = 0.28; // ordinary walking, blocks per tick
        int ticks = 80, syncInterval = 4, acceptanceDelay = 2;

        // --- Old behavior: blind authoritative overwrite on every sync. ---
        double clientOldBlocks = 0, serverOldAccepted = 0, worstRegression = 0;
        for (int t = 0; t < ticks; t++) {
            clientOldBlocks += perTick; // client predicts its own movement immediately
            if (t >= acceptanceDelay) serverOldAccepted += perTick;
            if (t % syncInterval == syncInterval - 1) {
                double before = clientOldBlocks;
                clientOldBlocks = serverOldAccepted; // overwrite with the (stale) server pose
                worstRegression = Math.max(worstRegression, before - clientOldBlocks);
            }
        }
        check(worstRegression > 0.5, "Blind overwrite demonstrably regresses the pose (baseline sanity)");

        // --- New behavior: reconciliation session. ---
        var server = vector(1);
        var client = vector(1);
        var session = new SpiritPoseReconciliation.Session();
        session.reconcilePosition(server.clone(), null); // initial anchor
        double worstStepBack = 0, worstDivergence = 0;
        for (int t = 0; t < ticks; t++) {
            TraversalSteering.advance(client, new Basis384f(), perTick, 0, 0);
            if (t >= acceptanceDelay) TraversalSteering.advance(server, new Basis384f(), perTick, 0, 0);
            if (t % syncInterval == syncInterval - 1) {
                double before = semanticBlocks(client, vector(1));
                session.reconcilePosition(server.clone(), client);
                double after = semanticBlocks(client, vector(1));
                worstStepBack = Math.max(worstStepBack, Math.max(0, before - after));
            }
            worstDivergence = Math.max(worstDivergence,
                    Math.abs(semanticBlocks(client, vector(1)) - semanticBlocks(server, vector(1))));
        }
        check(worstStepBack <= SpiritPoseReconciliation.SNAP_POSITION * 96 * 0.5,
                "Reconciliation never regresses the rendered pose by more than a fraction of the snap band");
        check(worstDivergence <= SpiritPoseReconciliation.SNAP_POSITION * 96,
                "Reconciled prediction stays within one snap band of accepted movement");
        double finalDrift = semanticBlocks(client, vector(1)) - semanticBlocks(server, vector(1));
        check(Math.abs(finalDrift) < SpiritPoseReconciliation.SNAP_POSITION * 96 * 0.5,
                "Steady-state prediction error converges instead of accumulating");
    }

    public static void main(String[] args) {
        policy();
        positionSession();
        basisSession();
        componentPersistenceUnchanged();
        clientArrivalReconciliation();
        walkingFlightSawtooth();
        System.out.println("Spirit pose reconciliation checks passed (" + assertions + " assertions)");
    }
}
