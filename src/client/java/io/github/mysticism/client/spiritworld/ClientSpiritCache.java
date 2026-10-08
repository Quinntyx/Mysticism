package io.github.mysticism.client.spiritworld;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.SpiritGlyphSelection;
import io.github.mysticism.net.*;
import io.github.mysticism.vector.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Transport lifetime and live observer mirrors. No absolute/frozen projection placement. */
public final class ClientSpiritCache {
    public static final Map<String, Vec384f> VEC = new HashMap<>();
    public static final HashSet<String> VISIBLE = new HashSet<>();
    public static Basis384f target = new Basis384f(), playerLatentBasis = new Basis384f();
    public static Vec384f playerLatentPos = Vec384f.ZERO(), playerLatentAttunement = Vec384f.ZERO();
    /** Rendered-scene continuity cap: revisions beyond this are deliberate re-anchoring, not prediction divergence. */
    public static final double MAX_CONTINUITY_SHIFT = 32;
    // Last authoritative mirror. The render frame above also carries client prediction between syncs.
    private static Vec384f mirrorPos; private static Basis384f mirrorBasis;
    private static long mirrorEpoch = Long.MIN_VALUE, lastMotionEpoch = Long.MIN_VALUE;
    // Previous render frame for per-tick interpolation, and the world-space continuity offset that
    // keeps semantic geometry visually fixed across authoritative frame revisions while moving.
    private static Vec384f previousLatentPos; private static Basis384f previousLatentBasis;
    private static Vec3d continuityOffset = Vec3d.ZERO, previousContinuityOffset = Vec3d.ZERO;
    private static Vec3d lastMovement = Vec3d.ZERO;
    private static boolean frameHistory;
    private static Object connection, world, player;
    private static String dimension, sourceDimension = "";
    private static UUID playerId, serverNonce;
    private static long lastGeneration, sequence, sceneSequence;
    private static SpiritFramePayload.Session session;
    private static SpiritScenePayload scene;
    private static List<SpiritGlyphSelection.Glyph> glyphs = List.of();
    private static boolean active, deep, observerReady;
    private static Vec3d sourcePosition = Vec3d.ZERO;
    private ClientSpiritCache() {}

    public static void observe(Object nextConnection,Object nextWorld,Object nextPlayer,String nextDimension,UUID nextPlayerId) {
        if (connection != nextConnection) { clear(); connection = nextConnection; serverNonce = null; lastGeneration = 0; }
        if (world != nextWorld || player != nextPlayer || !Objects.equals(dimension,nextDimension)) clear();
        world=nextWorld; player=nextPlayer; dimension=nextDimension; playerId=nextPlayerId;
    }
    public static void clear() {
        VISIBLE.clear(); VEC.clear(); glyphs=List.of(); scene=null; session=null; sequence=sceneSequence=0;
        active=deep=observerReady=false; sourceDimension=""; sourcePosition=Vec3d.ZERO;
        playerLatentPos=Vec384f.ZERO(); playerLatentBasis=new Basis384f(); target=new Basis384f();
        playerLatentAttunement=Vec384f.ZERO();
        mirrorPos=null; mirrorBasis=null; mirrorEpoch=lastMotionEpoch=Long.MIN_VALUE;
        previousLatentPos=null; previousLatentBasis=null;
        continuityOffset=Vec3d.ZERO; previousContinuityOffset=Vec3d.ZERO;
        lastMovement=Vec3d.ZERO; frameHistory=false;
    }
    public static boolean accept(SpiritFramePayload payload) {
        var s=payload.session();
        if (connection==null || world==null || player==null || !s.dimension().equals(dimension) || !s.player().equals(playerId)
                || s.generation()<=lastGeneration || serverNonce!=null && !serverNonce.equals(s.connection())) return false;
        // Transport epoch is NOT a world frame. Old payload frame/positions are deliberately ignored.
        clear(); serverNonce=s.connection(); lastGeneration=s.generation(); session=s; return true;
    }
    public static boolean accept(SpiritDeltaPayload payload) {
        if (session==null || !session.equals(payload.session()) || payload.sequence()<=sequence) return false;
        if (payload.sequence()!=sequence+1 || payload.add().size()>128 || payload.remove().size()>128) { clear(); return false; }
        var next=new HashSet<>(VISIBLE); next.removeAll(payload.remove()); var ids=new HashSet<String>();
        for (var a:payload.add()) { if (!ids.add(a.id())) { clear(); return false; } next.add(a.id()); }
        if (next.size()>128) { clear(); return false; }
        for (String id:payload.remove()) { VISIBLE.remove(id); VEC.remove(id); }
        var retained=new LinkedHashMap<String,SpiritGlyphSelection.Glyph>();
        for (var glyph:glyphs) if (next.contains(glyph.id())) retained.put(glyph.id(),glyph);
        for (var a:payload.add()) {
            var vector=Vec384f.fromBits(a.bits()); VEC.put(a.id(),vector); VISIBLE.add(a.id());
            retained.put(a.id(),new SpiritGlyphSelection.Glyph(a.id(),a.clusterId(),a.clusterSlot(),a.slot(),vector));
        }
        glyphs=List.copyOf(retained.values());
        sequence=payload.sequence(); return true;
    }
    /** Network owner invokes after its validated delta; keeps real cluster/member slots intact. */
    public static void replaceGlyphs(List<SpiritGlyphSelection.Glyph> value) {
        if (value.size()>128) throw new IllegalArgumentException("Glyph budget");
        glyphs=List.copyOf(value);
    }
    public static List<SpiritGlyphSelection.Glyph> glyphs() { return glyphs; }
    /** Optional canonical network cache storage; only current authenticated scene is retained. */
    public static boolean accept(SpiritScenePayload value) {
        if (session==null || !session.equals(value.session()) || value.sequence()<=sceneSequence) return false;
        scene=value; sceneSequence=value.sequence(); return true;
    }
    public static Optional<SpiritScenePayload> scene() { return io.github.mysticism.client.net.SpiritSceneClient.snapshot(); }
    public static void updateNavigation(boolean enabled, boolean isDeep, String source, Vec3d position, long motionEpoch) {
        active=enabled; deep=isDeep; lastMotionEpoch=motionEpoch;
        sourceDimension=Objects.requireNonNull(source); sourcePosition=Objects.requireNonNull(position);
    }
    /** Roll render-frame history so renderers can interpolate this tick's advance sub-tick. */
    public static void beginTick() {
        previousLatentPos=playerLatentPos.clone(); previousLatentBasis=playerLatentBasis.clone();
        previousContinuityOffset=continuityOffset; frameHistory=observerReady;
    }
    public static void updateObserver(Vec384f position, Basis384f basis) { updateObserver(position, basis, lastMovement); }
    /**
     * Mirror one authoritative observer frame. A frame revision that arrives while the observer is
     * moving and navigation state is unchanged is prediction divergence, not semantic travel: the
     * rendered scene is kept continuous by shifting the projection anchor, instead of snapping every
     * projected glyph/peer when the sync replaces the predicted frame (movement jitter/rubber banding).
     * Stationary revisions and navigation-state transitions remain visible snaps on purpose.
     */
    public static void updateObserver(Vec384f position, Basis384f basis, Vec3d movement) {
        EmbeddingSpace.requireCurrent(position); EmbeddingSpace.requireCurrent(basis.i);
        EmbeddingSpace.requireCurrent(basis.j); EmbeddingSpace.requireCurrent(basis.k);
        if (position.length()<1e-6 || Math.abs(basis.i.dot(basis.i)-1)>.02
                || Math.abs(basis.j.dot(basis.j)-1)>.02 || Math.abs(basis.k.dot(basis.k)-1)>.02
                || Math.abs(basis.i.dot(basis.j))>.02 || Math.abs(basis.i.dot(basis.k))>.02 || Math.abs(basis.j.dot(basis.k))>.02) {
            observerReady=false; return; // initial/default CCA must not manufacture projection
        }
        lastMovement = movement == null ? Vec3d.ZERO : movement;
        boolean mirrorChanged = mirrorPos == null || mirrorPos.squareDistance(position) > 0 || basisShift(mirrorBasis, basis) > 0;
        if (!mirrorChanged) return; // per-frame mirror refresh; the predicted render frame stands
        // Preserve the coherent pre-revision render state (position, basis AND offset as last
        // rendered) before any compensation, so every tickDelta interpolates between what was on
        // screen and the compensated revision — not a half-applied mix of both.
        previousLatentPos=playerLatentPos.clone(); previousLatentBasis=playerLatentBasis.clone();
        previousContinuityOffset=continuityOffset; frameHistory=observerReady;
        if (observerReady && lastMotionEpoch == mirrorEpoch && lastMovement.lengthSquared() > 1e-12) {
            Vec3d shift = semanticShift(position, playerLatentPos, playerLatentBasis);
            if (shift.lengthSquared() <= MAX_CONTINUITY_SHIFT * MAX_CONTINUITY_SHIFT)
                continuityOffset = continuityOffset.add(shift);
        }
        playerLatentPos=position.clone(); playerLatentBasis=basis.clone();
        mirrorPos=position.clone(); mirrorBasis=basis.clone(); mirrorEpoch=lastMotionEpoch;
        observerReady=true;
    }
    private static double basisShift(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i)+a.j.squareDistance(b.j)+a.k.squareDistance(b.k);
    }
    /** World-space displacement this frame revision alone gives the semantic neighborhood of the observer. */
    private static Vec3d semanticShift(Vec384f next, Vec384f from, Basis384f basis) {
        Vec384f delta = next.clone().sub(from);
        return new Vec3d(TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT*delta.dot(basis.i),
                TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT*delta.dot(basis.j),
                TraversalSteering.BLOCKS_PER_SEMANTIC_UNIT*delta.dot(basis.k));
    }
    /** Tick-interpolated render frame; keeps projected geometry continuous within a movement tick. */
    public static Vec384f interpolatedPos(float tickDelta) {
        if (!frameHistory || previousLatentPos == null) return playerLatentPos.clone();
        return previousLatentPos.clone().converge(playerLatentPos, clampUnit(tickDelta));
    }
    public static Basis384f interpolatedBasis(float tickDelta) {
        if (!frameHistory || previousLatentBasis == null) return playerLatentBasis.clone();
        return TraversalSteering.blend(previousLatentBasis, playerLatentBasis, clampUnit(tickDelta));
    }
    public static Vec3d interpolatedOffset(float tickDelta) {
        if (!frameHistory) return continuityOffset;
        return previousContinuityOffset.lerp(continuityOffset, clampUnit(tickDelta));
    }
    private static float clampUnit(float value) { return Math.max(0, Math.min(1, value)); }
    /** Read the actual synced navigation/observer components, never infer mode from dimension alone. */
    public static void syncObserver(MinecraftClient client) {
        observe(client.getNetworkHandler(),client.world,client.player,
                client.world==null?null:client.world.getRegistryKey().getValue().toString(),
                client.player==null?null:client.player.getUuid());
        if (client.player==null || client.world==null) { active=observerReady=false; return; }
        var current=io.github.mysticism.client.net.SpiritNetworkingClient.glyphs();
        if (!glyphs.equals(current)) {
            glyphs=current; VISIBLE.clear(); VEC.clear();
            for(var glyph:current) { VISIBLE.add(glyph.id()); VEC.put(glyph.id(),glyph.embedding()); }
        }
        var nav=MysticismEntityComponents.SPIRIT_NAVIGATION.get(client.player);
        updateNavigation(nav.active(),nav.deep(),nav.sourceDimension(),nav.sourcePosition(),nav.motionEpoch());
        updateObserver(MysticismEntityComponents.LATENT_POS.get(client.player).get(),
                MysticismEntityComponents.LATENT_BASIS.get(client.player).get());
    }
    public static boolean active() { return active; }
    public static boolean deep() { return deep; }
    public static boolean observerReady() { return observerReady && active; }
    public static String sourceDimension() { return sourceDimension; }
    public static Vec3d sourcePosition() { return sourcePosition; }
}
