package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/**
 * Three current-profile basis vectors stored as a {@link Basis384f}.
 * Uses CCA's data API for persistence and sync.
 * <p>
 * NOTE: Mutating the returned Basis384f (e.g., setI/J/K) changes this component;
 * remember to call MysticismEntityComponents.LATENT_BASIS.sync(player) after edits.
 */
public final class LatentBasis implements ComponentV3, AutoSyncedComponent {

    // Requires Basis384f to have public constructors.
    private Basis384f basis = new Basis384f();
    /** True only for client-side player instances: sync application consults prediction. */
    private final boolean clientSide;
    /** Client predictor hook; never set on server instances, so persistence is untouched. */
    private SyncReconciler reconciler;

    /** Client-side arrival policy for an authoritative sync. Runs on the client main thread. */
    public interface SyncReconciler {
        /** @return the basis this component must hold after the sync (may mutate either argument). */
        Basis384f reconcile(Basis384f serverValue, Basis384f current);
    }

    public LatentBasis() { this(false); }

    public LatentBasis(Basis384f initial) { this(false); set(initial); }

    /** Entity-component factories pass the owning world side; server instances never reconcile. */
    public LatentBasis(boolean clientSide) { this.clientSide = clientSide; }

    /** Installed by the client predictor on the LOCAL player's component only. */
    public void setSyncReconciler(SyncReconciler reconciler) { this.reconciler = reconciler; }
    public boolean hasSyncReconciler() { return reconciler != null; }

    /** Returns the live basis (mutable). */
    public Basis384f get() {
        return basis;
    }

    /** Replaces the basis (defensive clone of vectors). */
    public void set(Basis384f b) {
        if (b == null) {
            this.basis = new Basis384f();
            return;
        }
        // Deep copy the three axes to keep component ownership clear
        this.basis = new Basis384f(
                b.i != null ? b.i.clone() : Vec384f.ZERO(),
                b.j != null ? b.j.clone() : Vec384f.ZERO(),
                b.k != null ? b.k.clone() : Vec384f.ZERO()
        );
    }

    public void setI(Vec384f i) { if (i != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(i); this.basis.i = (i != null ? i.clone() : Vec384f.ZERO()); }
    public void setJ(Vec384f j) { if (j != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(j); this.basis.j = (j != null ? j.clone() : Vec384f.ZERO()); }
    public void setK(Vec384f k) { if (k != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(k); this.basis.k = (k != null ? k.clone() : Vec384f.ZERO()); }

    public Vec384f getI() { return this.basis.i; }
    public Vec384f getJ() { return this.basis.j; }
    public Vec384f getK() { return this.basis.k; }

    /* ------------------- CCA < 7 persistence/sync ------------------- */

    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        Basis384f decoded = null;
        if (EmbeddingNbt.compatible(tag) && tag.contains("b", NbtElement.INT_ARRAY_TYPE)) {
            try { decoded = Basis384f.fromBits(tag.getIntArray("b")); }
            catch (IllegalArgumentException incompatible) { decoded = null; }
        }
        if (decoded == null) decoded = new Basis384f();
        // Server load and non-predicting clients adopt plainly; the predicting client consults
        // the installed policy so syncs never regress healthy in-flight basis prediction.
        var policy = clientSide ? reconciler : null;
        this.basis = policy == null ? decoded : policy.reconcile(decoded, this.basis);
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("b", this.basis.toBits());
        tag.remove("embeddingArchive");
    }
}