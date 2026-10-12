package io.github.mysticism.client.net;

import io.github.mysticism.net.*;
import io.github.mysticism.dimension.spiritworld.SpiritGlyphSelection;
import io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import java.util.*;

/** One reader per stream; connection/world/player/session guards precede all cache/collision changes. */
public final class SpiritNetworkingClient {
    private static boolean initialized;private static Object handler,world,player;
    private static final Map<String,SpiritGlyphSelection.Glyph> glyphs=new TreeMap<>();private static long glyphSequence;
    private static final TerrainFrameAssembler terrainAssembler=new TerrainFrameAssembler(null);
    private static long lastResyncNanos,lastTerrainPartNanos;
    private static boolean resyncSent;
    private SpiritNetworkingClient(){}
    public static List<SpiritGlyphSelection.Glyph> glyphs(){return List.copyOf(glyphs.values());}
    private static void geometryClear(){glyphs.clear();glyphSequence=0;terrainAssembler.reset();lastResyncNanos=lastTerrainPartNanos=0;resyncSent=false;SpiritTerrainClient.clear();}
    public static void clear(){SpiritSceneClient.clear();geometryClear();}
    private static void observe(MinecraftClient c){Object h=c.getNetworkHandler(),w=c.world,p=c.player;if(h!=handler||w!=world||p!=player){clear();if(h!=handler)SpiritSceneClient.resetConnection();handler=h;world=w;player=p;}if(c.world==null||!c.world.getRegistryKey().getValue().toString().equals("mysticism:spirit"))clear();}
    private static boolean current(MinecraftClient c,Object h,Object w,Object p,SpiritFramePayload.Session session){return c.getNetworkHandler()==h&&c.world==w&&c.player==p&&c.player!=null&&c.world!=null&&session.player().equals(c.player.getUuid())&&session.dimension().equals(c.world.getRegistryKey().getValue().toString());}
    private static boolean session(SpiritFramePayload.Session s){return SpiritSceneClient.session().map(s::equals).orElse(false);}
    private static void terrain(SpiritTerrainPayload packet){
        if(!session(packet.session()))return;
        lastTerrainPartNanos=System.nanoTime();
        var outcome=terrainAssembler.apply(packet);
        if(outcome==TerrainFrameAssembler.Outcome.COMMITTED)SpiritTerrainClient.accept(terrainAssembler.committed());
        else if(outcome==TerrainFrameAssembler.Outcome.DESYNCED)requestResync(packet.session());
    }
    /** A broken/incompatible delta stream must be rebuilt from a full revision, never composited. */
    private static void requestResync(SpiritFramePayload.Session session){
        if(!session(session))return;
        long now=System.nanoTime();
        if(resyncSent && now-lastResyncNanos<500_000_000L)return;
        lastResyncNanos=now;resyncSent=true;
        ClientPlayNetworking.send(new SpiritTerrainSyncPayload(session.connection(),session.generation()));
    }
    private static void tick(MinecraftClient client){
        observe(client);
        if(terrainAssembler.incomplete() && System.nanoTime()-lastTerrainPartNanos>=1_000_000_000L)terrainAssembler.expirePartial();
        if(terrainAssembler.needsResync())SpiritSceneClient.session().ifPresent(SpiritNetworkingClient::requestResync);
    }
    public static void init(){
        if(initialized)return;initialized=true;ClientTickEvents.START_CLIENT_TICK.register(SpiritNetworkingClient::tick);
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->{handler=null;world=null;player=null;clear();SpiritSceneClient.resetConnection();observe(c);});
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->{clear();SpiritSceneClient.resetConnection();handler=world=player=null;});
        ClientPlayNetworking.registerGlobalReceiver(SpiritScenePayload.ID,(payload,context)->{
            var c=context.client();var h=context.player().networkHandler;var w=c.world;var p=context.player();
            c.execute(()->{observe(c);if(!current(c,h,w,p,payload.session()))return;var old=SpiritSceneClient.session();if(!SpiritSceneClient.accept(payload))return;if(old.isEmpty()||!old.get().equals(payload.session()))geometryClear();ClientPlayNetworking.send(new SpiritSessionAckPayload(payload.session().connection(),payload.session().generation()));});
        });
        ClientPlayNetworking.registerGlobalReceiver(SpiritDeltaPayload.ID,(payload,context)->{
            var c=context.client();var h=context.player().networkHandler;var w=c.world;var p=context.player();
            c.execute(()->{observe(c);if(payload.session()==null||!current(c,h,w,p,payload.session())||!session(payload.session())||payload.sequence()<=glyphSequence)return;payload.remove().forEach(glyphs::remove);for(var added:payload.add())glyphs.put(added.id(),added.glyph());if(glyphs.size()>128){glyphs.clear();return;}glyphSequence=payload.sequence();});
        });
        ClientPlayNetworking.registerGlobalReceiver(SpiritTerrainPayload.ID,(payload,context)->{
            var c=context.client();var h=context.player().networkHandler;var w=c.world;var p=context.player();
            c.execute(()->{observe(c);if(current(c,h,w,p,payload.session())&&session(payload.session()))terrain(payload);});
        });
    }
}
