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
    private boolean coordinateAvailable;

    public LatentPos() { }
    public LatentPos(Vec384f initial) { set(initial); }

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
        if (EmbeddingNbt.compatible(tag) && tag.contains("v", NbtElement.INT_ARRAY_TYPE)) {
            try {
                this.v = Vec384f.fromBits(tag.getIntArray("v"));
                // Current-profile older saves have a coordinate array but no availability marker.
                coordinateAvailable = !tag.contains("coordinateAvailable") || tag.getBoolean("coordinateAvailable"); return;
            }
            catch (IllegalArgumentException incompatible) { /* Discard obsolete/corrupt semantic data. */ }
        }
        this.v = Vec384f.ZERO(); coordinateAvailable = false;
    }

    // Restore the RegistryWrapper.WrapperLookup parameter
    @Override
    public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup wrapperLookup) {
        EmbeddingNbt.stamp(tag);
        tag.putIntArray("v", this.v.toBits());
        tag.putBoolean("coordinateAvailable", coordinateAvailable);
        tag.remove("embeddingArchive");
    }
}