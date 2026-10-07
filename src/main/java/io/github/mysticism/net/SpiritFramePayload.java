package io.github.mysticism.net;

import io.github.mysticism.landmark.ProjectionFrame;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.*;

/** Session bootstrap, delivered before its deltas. Transport trust is the active play connection. */
public record SpiritFramePayload(Session session,ProjectionFrame frame) implements CustomPayload {
    public static final Id<SpiritFramePayload> ID=new Id<>(Identifier.of("mysticism","spirit/frame_v1"));
    public SpiritFramePayload { Objects.requireNonNull(session); Objects.requireNonNull(frame); if(session.frameEpoch()!=frame.epoch())throw new IllegalArgumentException("Frame epoch mismatch"); SpiritProjectionState.encodeFrame(frame); }
    public record Session(UUID connection,UUID player,String dimension,long generation,long frameEpoch) {
        public Session { Objects.requireNonNull(connection); Objects.requireNonNull(player); if(!"mysticism:spirit".equals(dimension) || generation<=0 || frameEpoch<0)throw new IllegalArgumentException("Invalid glyph session"); }
        static void encode(RegistryByteBuf b,Session s) { b.writeUuid(s.connection); b.writeUuid(s.player); b.writeString(s.dimension,256); b.writeLong(s.generation); b.writeLong(s.frameEpoch); }
        static Session decode(RegistryByteBuf b) { return new Session(b.readUuid(),b.readUuid(),b.readString(256),b.readLong(),b.readLong()); }
        int bytes() { return 32+16+1+dimension.length(); }
    }
    public static final PacketCodec<RegistryByteBuf,SpiritFramePayload> CODEC=PacketCodec.of((p,b)->{Session.encode(b,p.session); b.writeNbt(SpiritProjectionState.encodeFrame(p.frame));},b->{
        if(b.readableBytes()>32768)throw new IllegalArgumentException("Oversized glyph frame");
        var s=Session.decode(b); var n=b.readNbt(); if(n==null)throw new IllegalArgumentException("Missing glyph frame"); return new SpiritFramePayload(s,SpiritProjectionState.decodeFrame(n));
    });
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
