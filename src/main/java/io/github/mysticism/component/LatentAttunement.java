// src/main/java/io/github/mysticism/component/Attunement.java
package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
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

    /* ------------------- Serialization (CCA < 7) ------------------- */

    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        // Stored as raw float bits to avoid precision or NBT type shenanigans.
        // We manually check for the key's existence since NbtCompound doesn't use Optional.
        if (tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            this.v = Vec384f.fromBits(tag.getIntArray("v"));
        } else {
            this.v = Vec384f.ZERO();
        }
    }

    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        tag.putIntArray("v", this.v.toBits());
    }
}