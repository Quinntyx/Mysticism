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
        PayloadTypeRegistry.playS2C().register(SpiritFlightCorrectionPayload.ID,SpiritFlightCorrectionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SpiritFlightCorrectionPayload.ID,SpiritFlightCorrectionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SpiritFlightCorrectionPayload.ID,(payload,context)->{
            var actor=context.player();var handler=actor.networkHandler;
            // Fabric play receivers run on the server thread, in connection order with vanilla
            // abilities. Do not defer this barrier past a subsequent genuine flight-off packet.
            if(handler.player==actor&&actor.networkHandler==handler&&context.server().getPlayerManager().getPlayer(actor.getUuid())==actor)
                io.github.mysticism.navigation.SpiritNavigationService.acknowledgeFlightCorrection(actor,payload.token());
        });
        ServerPlayNetworking.registerGlobalReceiver(SpiritTouchPayload.ID,(payload,context)->{var actor=context.player();var handler=actor.networkHandler;context.server().execute(()->{if(handler.player==actor&&actor.networkHandler==handler&&context.server().getPlayerManager().getPlayer(actor.getUuid())==actor)SpiritProjectionService.touch(actor,payload.target());});});
        ServerPlayNetworking.registerGlobalReceiver(SpiritSessionAckPayload.ID,(payload,context)->{var actor=context.player();var handler=actor.networkHandler;context.server().execute(()->{if(handler.player==actor&&actor.networkHandler==handler&&context.server().getPlayerManager().getPlayer(actor.getUuid())==actor)SpiritProjectionService.acknowledge(actor,payload);});});
    }
}
