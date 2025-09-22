// src/main/java/io/github/mysticism/component/Attunement.java
package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public final class LatentAttunement implements ComponentV3, AutoSyncedComponent {
    private Vec384f v = Vec384f.ZERO();

    public LatentAttunement() { }
    public LatentAttunement(Vec384f initial) { this.v = (initial != null ? initial.clone() : Vec384f.ZERO()); }

    /** Returns the live vector (mutable). Call MysticismEntityComponents.LATENT_POS.sync(player) after mutating. */
    public Vec384f get() { return v; }

    /** Replaces the vector. */
    public void set(Vec384f value) { this.v = (value != null ? value.clone() : Vec384f.ZERO()); }

    /* ------------------- Serialization (CCA 7+) ------------------- */
    @Override
    public void readData(ReadView readView) {
        // Stored as raw float bits to avoid precision or NBT type shenanigans.
        v = readView.getOptionalIntArray("v").map(Vec384f::fromBits).orElse(Vec384f.ZERO());
    }

    @Override
    public void writeData(WriteView writeView) {
        writeView.putIntArray("v", this.v.toBits());
    }
}
