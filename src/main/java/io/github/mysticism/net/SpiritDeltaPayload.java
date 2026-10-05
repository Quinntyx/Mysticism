package io.github.mysticism.net;

import io.github.mysticism.vector.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Protocol 2: profile-validated vectors, bounded IDs and a compression-independent byte budget. */
public record SpiritDeltaPayload(List<Added> add, List<String> remove) implements CustomPayload {
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_ID_CHARS = 256;
    // Below the 1 MiB custom-payload limit, with room for packet/channel/framing overhead.
    public static final int MAX_ENCODED_BYTES = 1_048_000;
    private static final int BATCH_HEADER_BYTES = varIntBytes(EmbeddingSpace.SCHEMA)
            + stringBytes(EmbeddingSpace.FINGERPRINT) + varIntBytes(EmbeddingSpace.DIMENSIONS)
            + 2 * varIntBytes(MAX_ENTRIES);

    public SpiritDeltaPayload {
        add = List.copyOf(add); remove = List.copyOf(remove);
        count(add.size()); count(remove.size());
        for (String id : remove) validateId(id);
        if (encodedBytes(add, remove) > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Spirit delta exceeds transport byte budget; use batches");
    }
    public static final Id<SpiritDeltaPayload> ID = new Id<>(Identifier.of("mysticism", "spirit/visible_delta_v2"));
    private static int count(int count) {
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid delta count");
        return count;
    }
    private static void validateId(String id) {
        Objects.requireNonNull(id);
        if (id.isEmpty() || id.length() > MAX_ID_CHARS) throw new IllegalArgumentException("Invalid spirit ID length");
        // Reject malformed UTF-16 so UTF-8 byte accounting is identical to the wire encoder.
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == id.length() || !Character.isLowSurrogate(id.charAt(i)))
                    throw new IllegalArgumentException("Malformed spirit ID Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed spirit ID Unicode");
        }
    }
    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7f) != 0) { value >>>= 7; bytes++; }
        return bytes;
    }
    private static int stringBytes(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(length) + length;
    }
    private static int additionBytes(Added value) {
        return stringBytes(value.id()) + varIntBytes(EmbeddingSpace.DIMENSIONS) + Integer.BYTES * EmbeddingSpace.DIMENSIONS;
    }
    private static int encodedBytes(List<Added> additions, List<String> removals) {
        int size = varIntBytes(EmbeddingSpace.SCHEMA) + stringBytes(EmbeddingSpace.FINGERPRINT)
                + varIntBytes(EmbeddingSpace.DIMENSIONS) + varIntBytes(additions.size()) + varIntBytes(removals.size());
        for (Added a : additions) size += additionBytes(a);
        for (String id : removals) size += stringBytes(id);
        return size;
    }
    /** Exact size of this payload's canonical codec output, excluding packet/channel wrappers. */
    public int encodedBytes() { return encodedBytes(add, remove); }

    /** Split a larger logical delta into bounded immutable packets. Send all in list order.
     * Addition/removal order is preserved within each stream; visibility deltas have disjoint IDs.
     * The sender must update its visibility snapshot only after sending every batch.
     */
    public static List<SpiritDeltaPayload> batches(List<Added> additions, List<String> removals) {
        List<Added> addSnapshot = List.copyOf(additions);
        List<String> removeSnapshot = List.copyOf(removals);
        List<SpiritDeltaPayload> packets = new ArrayList<>();
        List<Added> addPart = new ArrayList<>(); List<String> removePart = new ArrayList<>();
        int size = BATCH_HEADER_BYTES;
        for (Added value : addSnapshot) {
            int bytes = additionBytes(value);
            if (addPart.size() == MAX_ENTRIES || size + bytes > MAX_ENCODED_BYTES) {
                packets.add(new SpiritDeltaPayload(addPart, removePart));
                addPart.clear(); removePart.clear(); size = BATCH_HEADER_BYTES;
            }
            addPart.add(value); size += bytes;
        }
        for (String id : removeSnapshot) {
            validateId(id); int bytes = stringBytes(id);
            if (removePart.size() == MAX_ENTRIES || size + bytes > MAX_ENCODED_BYTES) {
                packets.add(new SpiritDeltaPayload(addPart, removePart));
                addPart.clear(); removePart.clear(); size = BATCH_HEADER_BYTES;
            }
            removePart.add(id); size += bytes;
        }
        if (!addPart.isEmpty() || !removePart.isEmpty()) packets.add(new SpiritDeltaPayload(addPart, removePart));
        return List.copyOf(packets);
    }
    private static void checkConsumed(RegistryByteBuf buf, int start) {
        if (buf.readerIndex() - start > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Oversized spirit delta");
    }
    public static final PacketCodec<RegistryByteBuf, SpiritDeltaPayload> CODEC = PacketCodec.of((payload, buf) -> {
        buf.writeVarInt(EmbeddingSpace.SCHEMA); buf.writeString(EmbeddingSpace.FINGERPRINT); buf.writeVarInt(EmbeddingSpace.DIMENSIONS);
        buf.writeVarInt(payload.add.size()); for (Added a : payload.add) Added.CODEC.encode(buf, a);
        buf.writeVarInt(payload.remove.size()); for (String id : payload.remove) buf.writeString(id, MAX_ID_CHARS);
    }, buf -> {
        int start = buf.readerIndex();
        if (buf.readVarInt() != EmbeddingSpace.SCHEMA || !EmbeddingSpace.FINGERPRINT.equals(buf.readString(64))
                || buf.readVarInt() != EmbeddingSpace.DIMENSIONS) throw new IllegalArgumentException("Incompatible spirit embedding protocol/profile");
        int n = count(buf.readVarInt()); List<Added> additions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { additions.add(Added.CODEC.decode(buf)); checkConsumed(buf, start); }
        n = count(buf.readVarInt()); List<String> removals = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { removals.add(buf.readString(MAX_ID_CHARS)); checkConsumed(buf, start); }
        checkConsumed(buf, start);
        return new SpiritDeltaPayload(additions, removals);
    });
    @Override public Id<? extends CustomPayload> getId() { return ID; }
    public record Added(String id, int[] bits) {
        public Added { validateId(id); Vec384f.fromBits(bits); bits = bits.clone(); }
        @Override public int[] bits() { return bits.clone(); }
        public static final PacketCodec<RegistryByteBuf, Added> CODEC = PacketCodec.of((added, buf) -> {
            buf.writeString(added.id, MAX_ID_CHARS); buf.writeVarInt(EmbeddingSpace.DIMENSIONS);
            for (int value : added.bits) buf.writeInt(value);
        }, buf -> {
            String id = buf.readString(MAX_ID_CHARS);
            if (buf.readVarInt() != EmbeddingSpace.DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimension");
            int[] bits = new int[EmbeddingSpace.DIMENSIONS];
            for (int i = 0; i < bits.length; i++) bits[i] = buf.readInt();
            return new Added(id, bits);
        });
        public static Added of(String id, Vec384f vector) { EmbeddingSpace.requireCurrent(vector); return new Added(id, vector.toBits()); }
    }
}
