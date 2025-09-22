package io.github.mysticism.net;

import io.github.mysticism.vector.Vec384f;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SpiritDeltaPayload(List<Added> add, List<String> remove) implements CustomPayload {
    public static final Id<SpiritDeltaPayload> ID =
            new Id<>(Identifier.of("mysticism", "spirit/visible_delta"));

    public static final PacketCodec<RegistryByteBuf, SpiritDeltaPayload> CODEC =
            PacketCodec.of((p, buf) -> {
                buf.writeVarInt(p.add.size());
                for (Added a : p.add) Added.CODEC.encode(buf, a);
                buf.writeVarInt(p.remove.size());
                for (String id : p.remove) buf.writeString(id);
            }, buf -> {
                int an = buf.readVarInt();
                var add = new ArrayList<Added>(an);
                for (int i = 0; i < an; i++) add.add(Added.CODEC.decode(buf));

                int rn = buf.readVarInt();
                var rem = new ArrayList<String>(rn);
                for (int i = 0; i < rn; i++) rem.add(buf.readString());

                return new SpiritDeltaPayload(add, rem);
            });

    @Override public Id<? extends CustomPayload> getId() { return ID; }

    public record Added(String id, int[] bits) {
        public static final PacketCodec<RegistryByteBuf, Added> CODEC =
                PacketCodec.of((a, buf) -> {
                    buf.writeString(a.id);
                    buf.writeVarInt(a.bits.length);
                    for (int v : a.bits) buf.writeInt(v);
                }, buf -> {
                    String id = buf.readString();
                    int n = buf.readVarInt();
                    int[] bits = new int[n];
                    for (int i = 0; i < n; i++) bits[i] = buf.readInt();
                    return new Added(id, bits);
                });

        public static Added of(String id, Vec384f v) {
            return new Added(id, v.toBits()); // your existing int[384]
        }
    }
}
