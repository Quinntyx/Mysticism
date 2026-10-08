package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper; // Re-add this import
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public final class LatentPos implements ComponentV3, AutoSyncedComponent {
    private Vec384f v = Vec384f.ZERO();
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
    public void set(Vec384f value) { if (value != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(value); this.v = (value != null ? value.clone() : Vec384f.ZERO()); }

    /* ------------------- Persistence (Corrected) ------------------- */

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        Vec384f decoded = Vec384f.ZERO();
        if (EmbeddingNbt.compatible(tag) && tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            try { decoded = Vec384f.fromBits(tag.getIntArray("v")); }
            catch (IllegalArgumentException incompatible) { decoded = Vec384f.ZERO(); }
        }
        // Server load and clients other than the predicting player adopt the decoded value plainly.
        // The predicting client consults the installed policy so an authoritative sync never
        // regresses healthy in-flight prediction (movement jitter/rubber banding).
        var policy = clientSide ? reconciler : null;
        this.v = policy == null ? decoded : policy.reconcile(decoded, this.v);
    }

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", this.v.toBits());
        tag.remove("embeddingArchive");
    }
}