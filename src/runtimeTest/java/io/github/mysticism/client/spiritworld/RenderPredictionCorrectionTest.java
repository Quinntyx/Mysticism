package io.github.mysticism.client.spiritworld;

import io.github.mysticism.component.*;
import io.github.mysticism.vector.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.math.Vec3d;

/** Actual render-only prediction handoff: collision accounting, forced unchanged authority,
 * independent correction streams, ordinary sync retention, arrival reset, and lag bursts.
 * No GL, game entity, model substitute, or mutation of authoritative components. */
public final class RenderPredictionCorrectionTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void close(Vec3d expected, Vec3d actual, String message) {
        check(expected.squaredDistanceTo(actual) < 1e-20, message + ": " + actual);
    }
    private static void deliver(LatentPos server, LatentPos client) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        try { server.writeSyncPacket(buf, null); client.applySyncPacket(buf); } finally { buf.release(); }
    }
    private static void deliver(LatentBasis server, LatentBasis client) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        try { server.writeSyncPacket(buf, null); client.applySyncPacket(buf); } finally { buf.release(); }
    }
    private static Vec384f source() {
        float[] values = new float[EmbeddingSpace.DIMENSIONS]; values[0] = .01f;
        return new Vec384f(values);
    }
    private static void frame(LatentPos q, LatentBasis basis, Vec3d movement) {
        ClientLatentPredictor.advanceFrame(q.get(), basis.get(), source(), movement,
                true, true, false, false, true);
    }
    private static void accountedWindows() {
        Object player = new Object(), world = new Object();
        PredictionContinuity continuity = new PredictionContinuity();
        check(continuity.integrableDelta(player, world, Vec3d.ZERO, 7, true, true) == null, "fresh baseline");
        Vec3d burst = continuity.integrableDelta(player, world, new Vec3d(6, .1, 0), 7, true, true);
        close(new Vec3d(6, 0, 0), ClientLatentPredictor.semanticWindow(burst, new Vec3d(0, .1, 0), false),
                "4-8-block chosen window survives continuity and excludes actual depenetration");
        close(Vec3d.ZERO, ClientLatentPredictor.semanticWindow(burst, Vec3d.ZERO, true), "position-look is never chosen motion");
        close(Vec3d.ZERO, ClientLatentPredictor.semanticWindow(null, new Vec3d(.1, 0, 0), false), "broken chain cannot subtract correction into fake travel");
        close(Vec3d.ZERO, ClientLatentPredictor.semanticWindow(new Vec3d(0, .1, 0), new Vec3d(0, .1, 0), false), "push-only window is inert");
    }
    private static void independentAuthorityAndArrival() {
        LatentPos serverQ = new LatentPos(source()), clientQ = new LatentPos(source());
        LatentBasis serverBasis = new LatentBasis(), clientBasis = new LatentBasis();
        ClientSpiritCache.clear();
        ClientSpiritCache.updateNavigation(true, true, "minecraft:overworld", Vec3d.ZERO, 7);
        check(ClientLatentPredictor.refreshCorrectionState(clientQ, clientBasis), "new component context resets prediction");
        frame(clientQ, clientBasis, new Vec3d(.4, 0, 0));
        check(ClientSpiritCache.playerLatentPos.squareDistance(serverQ.get()) > 1e-8, "fixture has real render-only prediction");
        check(clientQ.get().squareDistance(serverQ.get()) == 0, "render prediction leaves synced component authoritative");
        deliver(serverQ, clientQ);
        check(!ClientLatentPredictor.refreshCorrectionState(clientQ, clientBasis), "ordinary unchanged sync does not reset pending prediction");
        frame(clientQ, clientBasis, Vec3d.ZERO);
        check(ClientSpiritCache.playerLatentPos.squareDistance(serverQ.get()) > 1e-8, "ordinary authority baseline retains predicted render frame");
        serverQ.markAuthoritativeCorrection(); deliver(serverQ, clientQ);
        check(ClientLatentPredictor.refreshCorrectionState(clientQ, clientBasis), "forced unchanged q reaches render cache");
        frame(clientQ, clientBasis, Vec3d.ZERO);
        check(ClientSpiritCache.playerLatentPos.squareDistance(serverQ.get()) == 0, "unchanged authority retires stale render prediction, even at rest");
        frame(clientQ, clientBasis, new Vec3d(.4, 0, 0));
        serverBasis.markAuthoritativeCorrection(); deliver(serverBasis, clientBasis);
        check(ClientLatentPredictor.refreshCorrectionState(clientQ, clientBasis), "later independent basis correction still resets");
        frame(clientQ, clientBasis, Vec3d.ZERO);
        check(ClientSpiritCache.playerLatentPos.squareDistance(serverQ.get()) == 0, "basis correction resets coherent render frame");
        check(!ClientLatentPredictor.refreshCorrectionState(clientQ, clientBasis), "each correction is consumed once");
        frame(clientQ, clientBasis, new Vec3d(.4, 0, 0));
        ClientLatentPredictor.onArrivalApplied(); frame(clientQ, clientBasis, Vec3d.ZERO);
        check(ClientSpiritCache.playerLatentPos.squareDistance(serverQ.get()) == 0, "arrival resets render prediction as well as physical baseline");
        check(ClientSpiritCache.active() && ClientSpiritCache.sourceDimension().equals("minecraft:overworld"), "arrival preserves actual navigation/session source");
        check(ClientLatentPredictor.refreshCorrectionState(new LatentPos(source()), new LatentBasis()), "replacement components reset revision identities");
        ClientSpiritCache.clear();
    }
    private static void actualCallbackWiring() throws Exception {
        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/client/java/io/github/mysticism/client/spiritworld/ClientLatentPredictor.java"));
        check(code.contains("boolean corrected = refreshCorrectionState(")
                        && code.contains("delta = semanticWindow(delta, correction, repositioned || positionLook || corrected)"),
                "actual end-tick callback consumes both correction-accounted displacement and forced authority");
    }
    public static void main(String[] args) throws Exception {
        try { accountedWindows(); independentAuthorityAndArrival(); actualCallbackWiring(); }
        finally { ClientPoseSync.clear(); LatentSync.clear(); ClientSpiritCache.clear(); }
        System.out.println("RenderPredictionCorrectionTest: " + checks + " checks passed (production render-only handoff; no game/GL)");
    }
}
