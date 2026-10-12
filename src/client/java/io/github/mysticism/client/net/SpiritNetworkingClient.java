package io.github.mysticism.client.net;

import io.github.mysticism.net.*;
import io.github.mysticism.dimension.spiritworld.SpiritGlyphSelection;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.client.spiritworld.terrain.SpiritTerrainClient;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import java.util.*;

/** One reader per stream; connection/world/player/session guards precede all cache/collision changes. */
public final class SpiritNetworkingClient {
    private static boolean initialized;private static Object handler,world,player;
    private static final Map<String,SpiritGlyphSelection.Glyph> glyphs=new TreeMap<>();private static long glyphSequence,terrainSequence;
    private static TerrainMeshFrame terrain;private static SpiritTerrainPayload pending;private static int nextPart;
    private static final Map<Long,TerrainMeshFrame.Cell> assembled=new TreeMap<>();
    private SpiritNetworkingClient(){}
    public static List<SpiritGlyphSelection.Glyph> glyphs(){return List.copyOf(glyphs.values());}
    private static void geometryClear(){glyphs.clear();glyphSequence=terrainSequence=0;terrain=null;pending=null;nextPart=0;assembled.clear();SpiritTerrainClient.clear();}
    public static void clear(){SpiritSceneClient.clear();geometryClear();}
    private static void observe(MinecraftClient c){Object h=c.getNetworkHandler(),w=c.world,p=c.player;if(h!=handler||w!=world||p!=player){clear();if(h!=handler)SpiritSceneClient.resetConnection();handler=h;world=w;player=p;}if(c.world==null||!c.world.getRegistryKey().getValue().toString().equals("mysticism:spirit"))clear();}
    private static boolean current(MinecraftClient c,Object h,Object w,Object p,SpiritFramePayload.Session session){return c.getNetworkHandler()==h&&c.world==w&&c.player==p&&c.player!=null&&c.world!=null&&session.player().equals(c.player.getUuid())&&session.dimension().equals(c.world.getRegistryKey().getValue().toString());}
    private static boolean session(SpiritFramePayload.Session s){return SpiritSceneClient.session().map(s::equals).orElse(false);}
    private static void terrain(SpiritTerrainPayload packet){
        if(!session(packet.session())||packet.sequence()<=terrainSequence)return;
        if(pending==null||packet.sequence()!=pending.sequence()){
            if(packet.part()!=0)return;pending=packet;nextPart=0;assembled.clear();
            if(!packet.full()){if(terrain==null||!terrain.materials().equals(packet.frame().materials())||!terrain.sourceDimension().equals(packet.frame().sourceDimension())){pending=null;return;}terrain.cells().forEach(c->assembled.put(c.key(),c));}
            packet.removed().forEach(assembled::remove);
        }
        var f=packet.frame();var base=pending.frame();
        if(packet.part()!=nextPart||packet.parts()!=pending.parts()||packet.full()!=pending.full()||f.revision()!=base.revision()||f.shallow()!=base.shallow()||!f.materials().equals(base.materials())||!f.sourceDimension().equals(base.sourceDimension())||!f.sourceOrigin().equals(base.sourceOrigin())||!f.carrierOrigin().equals(base.carrierOrigin())){pending=null;assembled.clear();return;}
        f.cells().forEach(c->assembled.put(c.key(),c));if(assembled.size()>2048){pending=null;assembled.clear();return;}nextPart++;
        if(nextPart==packet.parts()){
            var complete=new TerrainMeshFrame(f.revision(),f.shallow(),f.sourceDimension(),f.sourceOrigin(),f.carrierOrigin(),f.materials(),List.copyOf(assembled.values()));
            SpiritTerrainClient.accept(complete);terrain=complete;terrainSequence=packet.sequence();pending=null;assembled.clear();
        }
    }
    public static void init(){
        if(initialized)return;initialized=true;ClientTickEvents.START_CLIENT_TICK.register(SpiritNetworkingClient::observe);
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->{handler=null;world=null;player=null;clear();SpiritSceneClient.resetConnection();observe(c);});
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->{clear();SpiritSceneClient.resetConnection();handler=world=player=null;});
        ClientPlayNetworking.registerGlobalReceiver(SpiritFlightCorrectionPayload.ID,(payload,context)->{
            var c=context.client();var p=context.player();
            // Fabric play receivers run on the client game thread. The preceding vanilla
            // abilities update has been applied; echo now, before later input can send a retry.
            // No mode change here: this is receipt, not a synthetic flight-on/walk gesture.
            if(c.player==p&&c.getNetworkHandler()==p.networkHandler&&c.world!=null
                    &&c.world.getRegistryKey().getValue().toString().equals("mysticism:spirit"))
                ClientPlayNetworking.send(payload);
        });
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
            c.execute(()->{observe(c);if(current(c,h,w,p,payload.session()))terrain(payload);});
        });
    }
}
