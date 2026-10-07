package io.github.mysticism.client.net;

import io.github.mysticism.client.spiritworld.ClientSpiritCache;
import io.github.mysticism.net.*;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.*;

@Environment(EnvType.CLIENT)
public final class SpiritNetworkingClient {
    public static final Logger LOGGER=LoggerFactory.getLogger("MysticismClient-SpiritNetworking");
    private static boolean initialized;
    public static void observe(MinecraftClient c) {
        ClientSpiritCache.observe(c.getNetworkHandler(),c.world,c.player,c.world==null?null:c.world.getRegistryKey().getValue().toString(),c.player==null?null:c.player.getUuid());
    }
    /** Guards queued work by actual connection/world/player object identity, not dimension string alone. */
    public static boolean sameLifetime(Object connection,Object world,Object player,Object currentConnection,Object currentWorld,Object currentPlayer) {
        return connection!=null && connection==currentConnection && world!=null && world==currentWorld && player!=null && player==currentPlayer;
    }
    public static void init() {
        if(initialized)return;initialized=true;
        ClientTickEvents.START_CLIENT_TICK.register(SpiritNetworkingClient::observe);
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->{ClientSpiritCache.observe(null,null,null,null,null);observe(client);});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->ClientSpiritCache.observe(null,null,null,null,null));
        ClientPlayNetworking.registerGlobalReceiver(SpiritFramePayload.ID,(payload,context)->{
            var c=context.client();var handler=context.player().networkHandler;var world=c.world;var player=context.player();
            c.execute(()->{if(!sameLifetime(handler,world,player,c.getNetworkHandler(),c.world,c.player))return;observe(c);if(!ClientSpiritCache.accept(payload))LOGGER.debug("Rejected stale/foreign glyph frame");});
        });
        ClientPlayNetworking.registerGlobalReceiver(SpiritDeltaPayload.ID,(payload,context)->{
            var c=context.client();var handler=context.player().networkHandler;var world=c.world;var player=context.player();
            c.execute(()->{if(!sameLifetime(handler,world,player,c.getNetworkHandler(),c.world,c.player))return;observe(c);if(!ClientSpiritCache.accept(payload))LOGGER.debug("Rejected legacy/stale/unbootstrapped glyph delta");});
        });
    }
}
