package io.github.mysticism.component;

import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper; // Re-add this import
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public final class LatentPos implements ComponentV3, AutoSyncedComponent {
    private Vec384f v = Vec384f.ZERO();

    public LatentPos() { }
    public LatentPos(Vec384f initial) { this.v = (initial != null ? initial.clone() : Vec384f.ZERO()); }

    public Vec384f get() { return v; }
    public void set(Vec384f value) { this.v = (value != null ? value.clone() : Vec384f.ZERO()); }

    /* ------------------- Persistence (Corrected) ------------------- */

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        if (tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            this.v = Vec384f.fromBits(tag.getIntArray("v"));
        } else {
            this.v = Vec384f.ZERO();
        }
    }

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        tag.putIntArray("v", this.v.toBits());
    }
}