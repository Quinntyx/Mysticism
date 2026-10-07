package io.github.mysticism.net;

import io.github.mysticism.landmark.ProjectionFrame;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.minecraft.server.network.*;
import net.minecraft.util.WorldSavePath;
import java.nio.file.Files;
import java.util.*;

/** Server-thread connection lifetime. Parent bridges the REAL frozen terrain frame, never CCA defaults. */
public final class SpiritProjectionService {
    private static final Map<ServerPlayNetworkHandler,Connection> connections=new IdentityHashMap<>();
    private static boolean initialized;
    private static final class Connection {
        final UUID nonce=UUID.randomUUID(); long generation,sequence; ServerPlayerEntity player;
        SpiritProjectionState state; SpiritFramePayload.Session session;
        void invalidate(){session=null;state=null;player=null;sequence=0;}
    }
    private SpiritProjectionService() {}
    public static void init() {
        if(initialized)return;initialized=true;
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->connections.remove(handler));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player,from,to)->invalidate(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer,newPlayer,alive)->invalidate(newPlayer));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->connections.keySet().removeIf(h->h.player.getServer()==server));
    }
    private static void invalidate(ServerPlayerEntity player) { var c=connections.get(player.networkHandler);if(c!=null)c.invalidate(); }
    private static void thread(ServerPlayerEntity player) { if(player.getServer()==null || !player.getServer().isOnThread())throw new IllegalStateException("Projection networking requires server thread"); }
    /** True iff a new bootstrap was sent: parent MUST reset visibility snapshot and send full current set. */
    public static boolean activate(ServerPlayerEntity player,ProjectionFrame terrainFrame) {
        thread(player);
        if(!player.getWorld().getRegistryKey().getValue().toString().equals("mysticism:spirit")) { invalidate(player);return false; }
        var c=connections.computeIfAbsent(player.networkHandler,h->new Connection());
        if(c.session!=null && c.player==player) {
            if(c.session.frameEpoch()!=terrainFrame.epoch())throw new IllegalStateException("Terrain projection epoch changed; migration required");
            return false;
        }
        String key=SpiritProjectionState.key(player.getUuid());var manager=player.getServer().getOverworld().getPersistentStateManager();
        var state=manager.get(SpiritProjectionState.TYPE,key);
        if(state==null) {
            if(Files.exists(player.getServer().getSavePath(WorldSavePath.ROOT).resolve("data").resolve(key+".dat")))throw new IllegalStateException("Unreadable glyph projection save; refusing replacement");
            state=new SpiritProjectionState();state.initialize(terrainFrame);manager.set(key,state);
        } else state.initialize(terrainFrame);
        if(!ServerPlayNetworking.canSend(player,SpiritFramePayload.ID) || !ServerPlayNetworking.canSend(player,SpiritDeltaPayload.ID))throw new IllegalStateException("Client lacks authoritative glyph protocol");
        var session=new SpiritFramePayload.Session(c.nonce,player.getUuid(),"mysticism:spirit",Math.incrementExact(c.generation),state.frame().epoch());
        ServerPlayNetworking.send(player,new SpiritFramePayload(session,state.frame()));
        c.generation=session.generation();c.sequence=0;c.session=session;c.player=player;c.state=state;return true;
    }
    /** Adds get persistent first-ID positions. Advance visibility snapshot ONLY after this returns. */
    public static void send(ServerPlayerEntity player,List<SpiritDeltaPayload.Added> additions,List<String> removals) {
        thread(player);var c=connections.get(player.networkHandler);
        if(c==null || c.session==null || c.player!=player || !c.session.dimension().equals(player.getWorld().getRegistryKey().getValue().toString()))throw new IllegalStateException("No active authoritative glyph session");
        if(additions.size()>128 || removals.size()>128)throw new IllegalArgumentException("Active glyph budget");
        var placed=new ArrayList<SpiritDeltaPayload.Added>(additions.size());
        for(var a:additions){var vector=Vec384f.fromBits(a.bits());placed.add(new SpiritDeltaPayload.Added(a.id(),a.bits(),c.state.position(a.id(),vector)));}
        long next=Math.incrementExact(c.sequence);
        for(var packet:SpiritDeltaPayload.batches(c.session,next,placed,removals))ServerPlayNetworking.send(player,packet);
        c.sequence=next;
    }
}
