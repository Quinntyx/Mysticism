package io.github.mysticism.client.spiritworld;

import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.vector.Basis384f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class ClientSpiritCache {
    /** Only active authenticated visible embeddings; removals release vectors. */
    public static final Map<String, Vec384f> VEC = new HashMap<>();

    /** Subset currently marked visible by the server. */
    public static final HashSet<String> VISIBLE = new HashSet<>();


    /** Target basis we’re easing toward (when attunement changes). */
    public static Basis384f target = new Basis384f();

    /** Mirrors of CCA-synced fields for use in rendering so we don't need to query the player object as much */
    public static Basis384f playerLatentBasis = new Basis384f();
    public static Vec384f playerLatentPos = Vec384f.ZERO();
    public static Vec384f playerLatentAttunement = Vec384f.ZERO();

    /** Last position of player, used for calculating changes in movement to update basis */
    private static Vec3d lastPos = null;

    private static Object connection, world, player;
    private static String dimension;
    private static java.util.UUID playerId, serverNonce;
    private static long lastGeneration, sequence;
    private static io.github.mysticism.net.SpiritFramePayload.Session session;
    private static SpiritGlyphFrame frame;
    /** Called on client thread before packets/render/tick. Preserve generation watermark across world changes. */
    public static void observe(Object nextConnection,Object nextWorld,Object nextPlayer,String nextDimension,java.util.UUID nextPlayerId) {
        if(connection!=nextConnection) { clear(); connection=nextConnection; serverNonce=null;lastGeneration=0; }
        if(world!=nextWorld || player!=nextPlayer || !java.util.Objects.equals(dimension,nextDimension))clear();
        world=nextWorld;player=nextPlayer;dimension=nextDimension;playerId=nextPlayerId;
    }
    public static void clear() { VISIBLE.clear();VEC.clear();frame=null;session=null;sequence=0; }
    public static boolean accept(io.github.mysticism.net.SpiritFramePayload payload) {
        var s=payload.session();
        if(connection==null || world==null || player==null || !s.dimension().equals(dimension) || !s.player().equals(playerId)
                || s.generation()<=lastGeneration || serverNonce!=null && !serverNonce.equals(s.connection()))return false;
        SpiritGlyphFrame next=new SpiritGlyphFrame(payload.frame());
        clear();serverNonce=s.connection();lastGeneration=s.generation();session=s;frame=next;return true;
    }
    public static boolean accept(io.github.mysticism.net.SpiritDeltaPayload payload) {
        if(session==null || !session.equals(payload.session()))return false;
        if(payload.sequence()<=sequence)return false;
        if(payload.sequence()!=sequence+1) { clear();return false; }
        if(payload.add().size()>128 || payload.remove().size()>128) { clear();return false; }
        var next=new java.util.HashSet<>(VISIBLE);next.removeAll(payload.remove());
        var ids=new java.util.HashSet<String>();
        for(var a:payload.add()) { if(a.position()==null || !ids.add(a.id())) { clear();return false; }next.add(a.id()); }
        if(next.size()>128) { clear();return false; }
        for(String id:payload.remove()) {VISIBLE.remove(id);VEC.remove(id);}
        frame.retain(next);
        for(var a:payload.add()) {VEC.put(a.id(),Vec384f.fromBits(a.bits()));VISIBLE.add(a.id());frame.place(a.id(),a.position());}
        sequence=payload.sequence();return true;
    }
    static SpiritGlyphFrame frame() { return frame; }
    private ClientSpiritCache() {}
}
