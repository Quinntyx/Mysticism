package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.RegistryByteBuf;
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

    public LatentBasis() {}

    public LatentBasis(Basis384f initial) {
        set(initial);
    }

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
        if (EmbeddingNbt.compatible(tag) && tag.contains("b", NbtElement.INT_ARRAY_TYPE)) {
            try { this.basis = Basis384f.fromBits(tag.getIntArray("b")); return; }
            catch (IllegalArgumentException incompatible) { /* Discard obsolete/corrupt semantic data. */ }
        }
        this.basis = new Basis384f();
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("b", this.basis.toBits());
        tag.remove("embeddingArchive");
    }

    /**
     * Client-side authoritative sync application with movement-ordering reconciliation; see
     * {@link LatentPos#applySyncPacket}. A basis sync whose divergence from the locally integrated
     * basis is fully explained by unacknowledged movement is held, never applied as a rollback;
     * every genuine authoritative correction (touch blend, support alignment, anchor) is accepted.
     */
    @Override
    public void applySyncPacket(RegistryByteBuf buf) {
        NbtCompound tag = buf.readNbt();
        if (tag == null) return;
        LatentBasis incoming = new LatentBasis();
        // The read path ignores the registry lookup; the wire format is self-describing bits.
        incoming.readFromNbt(tag, null);
        this.set(LatentSync.reconcile(this, this.basis, incoming.basis));
    }
}