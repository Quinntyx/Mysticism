package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper; // Re-add this import
import net.minecraft.server.network.ServerPlayerEntity;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public final class LatentPos implements ComponentV3, AutoSyncedComponent {
    private Vec384f v = Vec384f.ZERO();
    /** Monotonic per-instance wire sequence for sync ordering; not persisted, session-local. */
    private int syncSequence;

    public LatentPos() { }
    public LatentPos(Vec384f initial) { set(initial); }

    public Vec384f get() { return v; }
    public void set(Vec384f value) { if (value != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(value); this.v = (value != null ? value.clone() : Vec384f.ZERO()); }

    /* ------------------- Persistence (Corrected) ------------------- */

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        if (EmbeddingNbt.compatible(tag) && tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            try { this.v = Vec384f.fromBits(tag.getIntArray("v")); return; }
            catch (IllegalArgumentException incompatible) { /* Discard obsolete/corrupt semantic data. */ }
        }
        this.v = Vec384f.ZERO();
    }

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", this.v.toBits());
        tag.remove("embeddingArchive");
    }

    /**
     * Client-side authoritative sync application with movement-ordering reconciliation.
     *
     * <p>The default implementation overwrites the component verbatim. That is correct for peers,
     * but for the local player the synced pose was integrated from movement packets the server had
     * already received, so it can lag the locally predicted pose by the in-flight movement; delayed
     * or reordered snapshots must not roll the predicted spirit pose back to a stale snapshot on
     * every sync cadence (jitter/rubber banding). The installed {@link LatentSync} filter decides
     * between the fresher prediction and the authoritative value using the wire sequence embedded
     * by {@link #writeSyncPacket}; without a filter (server side, tests, peers) the historical
     * behaviour is preserved.
     */
    @Override
    public void applySyncPacket(RegistryByteBuf buf) {
        int sequence = buf.readVarInt();
        NbtCompound tag = buf.readNbt();
        if (tag == null) return;
        LatentPos incoming = new LatentPos();
        // The read path ignores the registry lookup; the wire format is self-describing bits.
        incoming.readFromNbt(tag, null);
        this.set(LatentSync.reconcile(this, sequence, this.v, incoming.v));
    }

    /**
     * Embeds a monotonic per-instance sequence before the pose NBT so the client can distinguish
     * reordered/stale snapshots from fresh authoritative ones exactly, rather than guessing from
     * divergence geometry. Per-recipient streams observe monotonically increasing values.
     */
    @Override
    public void writeSyncPacket(RegistryByteBuf buf, ServerPlayerEntity recipient) {
        if (this.syncSequence == Integer.MAX_VALUE) this.syncSequence = 0;
        buf.writeVarInt(++this.syncSequence);
        NbtCompound tag = new NbtCompound();
        this.writeToNbt(tag, buf.getRegistryManager());
        buf.writeNbt(tag);
    }
}