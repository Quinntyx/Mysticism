package io.github.mysticism.net;
import net.fabricmc.fabric.api.networking.v1.*;
/** Registers only approved dynamic observer streams. Legacy frozen frame protocol is NOT registered. */
public final class SpiritNetworking {
    private static boolean initialized;private SpiritNetworking(){}
    public static void init(){
        if(initialized)return;initialized=true;
        PayloadTypeRegistry.playS2C().register(SpiritScenePayload.ID,SpiritScenePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SpiritDeltaPayload.ID,SpiritDeltaPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SpiritTerrainPayload.ID,SpiritTerrainPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SpiritTouchPayload.ID,SpiritTouchPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SpiritSessionAckPayload.ID,SpiritSessionAckPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SpiritTouchPayload.ID,(payload,context)->{var actor=context.player();var handler=actor.networkHandler;context.server().execute(()->{if(handler.player==actor&&actor.networkHandler==handler&&context.server().getPlayerManager().getPlayer(actor.getUuid())==actor)SpiritProjectionService.touch(actor,payload.target());});});
        ServerPlayNetworking.registerGlobalReceiver(SpiritSessionAckPayload.ID,(payload,context)->{var actor=context.player();var handler=actor.networkHandler;context.server().execute(()->{if(handler.player==actor&&actor.networkHandler==handler&&context.server().getPlayerManager().getPlayer(actor.getUuid())==actor)SpiritProjectionService.acknowledge(actor,payload);});});
    }
}
