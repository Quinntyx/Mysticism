package io.github.mysticism.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/**
 * Minimal “floating label” entity that represents a latent concept.
 * - Stores a single String id (concept key)
 * - Always renders that id as nameplate in-world
 * - Is invulnerable and has no interactions for now
 */
public class LatentGlyphEntity extends Entity {
    // Tracked data for concept id (synced to clients)
    private static final TrackedData<String> CONCEPT_ID =
            DataTracker.registerData(LatentGlyphEntity.class, TrackedDataHandlerRegistry.STRING);

    public LatentGlyphEntity(EntityType<? extends LatentGlyphEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.setCustomNameVisible(true); // always show the floating text
    }

    /** Convenience factory if you like: new LatentGlyphEntity(MY_TYPE, world).setConceptId(id) */
    public LatentGlyphEntity setConceptId(String id) {
        getDataTracker().set(CONCEPT_ID, id == null ? "" : id);
        this.setCustomName(Text.literal(getConceptId()));
        return this;
    }

    public String getConceptId() {
        return getDataTracker().get(CONCEPT_ID);
    }

    /* ---------------- DataTracker ---------------- */

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(CONCEPT_ID, "");
    }

    /* ---------------- Combat/interaction ---------------- */

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        // No-op: glyphs aren't damaged (return false = not handled / no damage applied)
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    /* ---------------- Serialization (entity save / sync) ---------------- */

    @Override
    protected void readCustomData(ReadView view) {
        String id = view.getString("id", "");
        getDataTracker().set(CONCEPT_ID, id);
        this.setCustomName(Text.literal(id));
        this.setCustomNameVisible(true);
    }

    @Override
    protected void writeCustomData(WriteView view) {
        view.putString("id", getConceptId());
    }

    /* ---------------- Rendering helpers ---------------- */

    @Override
    public boolean shouldRenderName() {
        // Always render the floating label (we also set customNameVisible=true in ctor)
        return true;
    }
}
