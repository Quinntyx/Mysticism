package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/**
 * Three 384-D basis vectors stored as a {@link Basis384f}.
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

    public void setI(Vec384f i) { this.basis.i = (i != null ? i.clone() : Vec384f.ZERO()); }
    public void setJ(Vec384f j) { this.basis.j = (j != null ? j.clone() : Vec384f.ZERO()); }
    public void setK(Vec384f k) { this.basis.k = (k != null ? k.clone() : Vec384f.ZERO()); }

    public Vec384f getI() { return this.basis.i; }
    public Vec384f getJ() { return this.basis.j; }
    public Vec384f getK() { return this.basis.k; }

    /* ------------------- CCA 7+ persistence/sync ------------------- */

    @Override
    public void readData(ReadView readView) {
        this.basis = readView.getOptionalIntArray("b").map(Basis384f::fromBits).orElseGet(Basis384f::new);
    }

    @Override
    public void writeData(WriteView writeView) {
        writeView.putIntArray("b", this.basis.toBits());
    }
}
