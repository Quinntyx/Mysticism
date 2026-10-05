// src/main/java/io/github/mysticism/component/Attunement.java
package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public final class LatentAttunement implements ComponentV3, AutoSyncedComponent {
    private NbtCompound archive;
    private Vec384f v = Vec384f.ZERO();

    public LatentAttunement() { }
    public LatentAttunement(Vec384f initial) { set(initial); }

    /** Returns the live vector (mutable). Call MysticismEntityComponents.LATENT_POS.sync(player) after mutating. */
    public Vec384f get() { return v; }

    /** Replaces the vector. */
    public void set(Vec384f value) { if (value != null) io.github.mysticism.vector.EmbeddingSpace.requireCurrent(value); this.v = (value != null ? value.clone() : Vec384f.ZERO()); }

    /* ------------------- Serialization (CCA < 7) ------------------- */

    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        archive = tag.contains("embeddingArchive") ? tag.getCompound("embeddingArchive").copy() : null;
        if (EmbeddingNbt.compatible(tag) && tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            try { this.v = Vec384f.fromBits(tag.getIntArray("v")); return; }
            catch (IllegalArgumentException incompatible) { /* Preserve corrupt payload below. */ }
        }
        archive = tag.copy();
        this.v = Vec384f.ZERO();
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", this.v.toBits());
        if (archive != null) tag.put("embeddingArchive", archive.copy());
    }
}