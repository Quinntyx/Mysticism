package io.github.mysticism.net;

import io.github.mysticism.navigation.SpiritTransitionGate.Handoff;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** Ordered immediately BEFORE the vanilla respawn/position packets, after real terrain validation. */
public record SpiritTransitionPayload(Handoff handoff) implements CustomPayload {
    public static final Id<SpiritTransitionPayload> ID = new Id<>(Identifier.of("mysticism", "spirit/transition_v1"));
    public static final PacketCodec<RegistryByteBuf, SpiritTransitionPayload> CODEC = PacketCodec.of((p, b) -> {
        Handoff h = p.handoff();
        b.writeUuid(h.player()); b.writeString(h.from(), 256); b.writeString(h.to(), 256);
        b.writeDouble(h.x()); b.writeDouble(h.y()); b.writeDouble(h.z());
        b.writeFloat(h.yaw()); b.writeFloat(h.pitch());
    }, b -> new SpiritTransitionPayload(new Handoff(b.readUuid(), b.readString(256), b.readString(256),
            b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat())));

    public static void beforeTeleport(ServerPlayerEntity player, ServerWorld destination, Vec3d feet, float yaw, float pitch) {
        if (ServerPlayNetworking.canSend(player, ID)) {
            ServerPlayNetworking.send(player, new SpiritTransitionPayload(new Handoff(player.getUuid(),
                    player.getWorld().getRegistryKey().getValue().toString(),
                    destination.getRegistryKey().getValue().toString(), feet.x, feet.y, feet.z, yaw, pitch)));
        }
    }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
