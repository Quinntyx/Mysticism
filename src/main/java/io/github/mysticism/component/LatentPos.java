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
    private boolean coordinateAvailable;
    /** Monotonic per-instance wire sequence for sync ordering; not persisted, session-local. */
    private int syncSequence;
    // Correction provenance is session-local and travels WITH the pose, not a separate nav packet.
    private long correctionRevision;
    private int receivedSequence;
    private long receivedCorrectionRevision;
    private Vec384f receivedPose;

    public void markAuthoritativeCorrection() { correctionRevision++; }
    public int receivedSequence() { return receivedSequence; }
    public long receivedCorrectionRevision() { return receivedCorrectionRevision; }
    public Vec384f receivedPose() { return receivedPose == null ? null : receivedPose.clone(); }
    /** True only for client-side player instances: sync application consults prediction. */
    private final boolean clientSide;
    /** Client predictor hook; never set on server instances, so persistence is untouched. */
    private SyncReconciler reconciler;

    /** Client-side arrival policy for an authoritative sync. Runs on the client main thread. */
    public interface SyncReconciler {
        /** @return the vector this component must hold after the sync (may mutate either argument). */
        Vec384f reconcile(Vec384f serverValue, Vec384f current);
    }

    public LatentPos() { this(false); }
    public LatentPos(Vec384f initial) { this(false); set(initial); }
    /** Entity-component factories pass the owning world side; server instances never reconcile. */
    public LatentPos(boolean clientSide) { this.clientSide = clientSide; }

    /** Installed by the client predictor on the LOCAL player's component only. */
    public void setSyncReconciler(SyncReconciler reconciler) { this.reconciler = reconciler; }
    public boolean hasSyncReconciler() { return reconciler != null; }

    public Vec384f get() { return v; }
    /** ZERO can be a real semantic coordinate; magnitude cannot distinguish it from a reset. */
    public boolean hasCoordinate() { return coordinateAvailable; }
    public void set(Vec384f value) {
        if (value != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(value);
        this.v = (value != null ? value.clone() : Vec384f.ZERO()); coordinateAvailable = value != null;
    }

    /* ------------------- Persistence (Corrected) ------------------- */

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        Vec384f decoded = Vec384f.ZERO();
        if (EmbeddingNbt.compatible(tag) && tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            try {
                decoded = Vec384f.fromBits(tag.getIntArray("v"));
                coordinateAvailable = !tag.contains("coordinateAvailable") || tag.getBoolean("coordinateAvailable");
            } catch (IllegalArgumentException incompatible) { coordinateAvailable = false; }
        } else coordinateAvailable = false;
        var policy = clientSide ? reconciler : null;
        this.v = policy == null ? decoded : policy.reconcile(decoded, this.v);
    }

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", this.v.toBits());
        tag.putBoolean("coordinateAvailable", coordinateAvailable);
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
        // Ordering already applies before this instance is recognized as the local predictor.
        // Otherwise a stale replacement/peer packet can leave pose and cached frontier disagreeing.
        if (receivedSequence != 0 && sequence <= receivedSequence) return;
        LatentPos incoming = new LatentPos();
        // The read path ignores the registry lookup; the wire format is self-describing bits.
        incoming.readFromNbt(tag, null);
        long revision = tag.getLong("poseCorrectionRevision");
        Vec384f reconciled = LatentSync.reconcile(this, sequence, revision, this.v, incoming.v, incoming.coordinateAvailable);
        // Availability belongs to authority, not the magnitude (or a held prediction) of q.
        // Incompatible/corrupt/reset payloads must remain unavailable on the wire too.
        this.set(incoming.coordinateAvailable ? reconciled : null);
        // Capture authority even when this component is not yet registered as the local predictor.
        // A later replacement-context initialization inherits its real wire frontier.
        if (sequence > receivedSequence) {
            receivedSequence = sequence;
            receivedCorrectionRevision = revision;
            receivedPose = incoming.v.clone();
        }
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
        tag.putLong("poseCorrectionRevision", correctionRevision);
        buf.writeNbt(tag);
    }
}
