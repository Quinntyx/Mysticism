package io.github.mysticism.component;

import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/** Per-player navigation, not a shared world frame. Target coordinates and vectors are snapshots. */
public final class SpiritNavigation implements ComponentV3, AutoSyncedComponent {
    private boolean active, deep;
    private String dimension = "", landmark = "";
    private Vec3d position = Vec3d.ZERO;
    private String targetDimension = "", targetLandmark = "";
    private BlockPos target = BlockPos.ORIGIN;
    private Basis384f targetBasis = new Basis384f();
    private boolean savedAbilities, allowFlying, flying, noGravity;

    public boolean active() { return active; }
    public boolean deep() { return deep; }
    public String sourceDimension() { return dimension; }
    public String landmarkId() { return landmark; }
    public Vec3d sourcePosition() { return position; }
    public boolean hasShallowTarget() { return !targetDimension.isEmpty() && !targetLandmark.isEmpty(); }
    public String targetDimension() { return targetDimension; }
    public String targetLandmarkId() { return targetLandmark; }
    public BlockPos targetBlock() { return target; }
    public Basis384f targetBasis() { return targetBasis.clone(); }
    public void enterDeep() { active = true; deep = true; }
    public void shallow(String dimension, String id, Vec3d pos) {
        check(dimension, id, pos);
        this.dimension = dimension; landmark = id; position = pos; active = true; deep = false;
    }
    public void setActive(boolean active) { this.active = active; if (!active) deep = false; }
    public void target(String dimension, String id, BlockPos pos) { target(dimension, id, pos, new Basis384f()); }
    public void target(String dimension, String id, BlockPos pos, Basis384f basis) {
        check(dimension, id, Vec3d.of(pos));
        if (id.isBlank() || basis == null) throw new IllegalArgumentException("Target requires real landmark and source basis");
        EmbeddingSpace.requireCurrent(basis.i); EmbeddingSpace.requireCurrent(basis.j); EmbeddingSpace.requireCurrent(basis.k);
        if (Math.abs(basis.i.length()-1) > .001 || Math.abs(basis.j.length()-1) > .001
                || Math.abs(basis.k.length()-1) > .001 || Math.abs(basis.i.dot(basis.j)) > .001
                || Math.abs(basis.i.dot(basis.k)) > .001 || Math.abs(basis.j.dot(basis.k)) > .001)
            throw new IllegalArgumentException("Target requires real landmark and orthonormal source basis");
        targetDimension = dimension; targetLandmark = id; target = pos.toImmutable(); targetBasis = basis.clone();
    }
    public void clearTarget() { targetDimension = ""; targetLandmark = ""; target = BlockPos.ORIGIN; targetBasis = new Basis384f(); }

    // Persist the pre-entry flight state so logout/restart does not grant permanent survival flight.
    public void rememberAbilities(boolean allowFlying, boolean flying, boolean noGravity) {
        if (savedAbilities) return;
        this.allowFlying = allowFlying; this.flying = flying; this.noGravity = noGravity; savedAbilities = true;
    }
    public boolean hasSavedAbilities() { return savedAbilities; }
    public boolean savedAllowFlying() { return allowFlying; }
    public boolean savedFlying() { return flying; }
    public boolean savedNoGravity() { return noGravity; }
    public void clearSavedAbilities() { savedAbilities = false; }

    private static void check(String dimension, String id, Vec3d pos) {
        if (dimension == null || dimension.length() > 256 || Identifier.tryParse(dimension) == null || id == null || id.length() > 256
                || (!id.isEmpty() && id.isBlank()) || pos == null || !Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z))
            throw new IllegalArgumentException("Invalid source navigation location");
    }
    @Override public void readFromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
        active = false; deep = false; dimension = ""; landmark = ""; position = Vec3d.ZERO; clearTarget();
        savedAbilities = tag.getBoolean("savedAbilities");
        allowFlying = tag.getBoolean("allowFlying"); flying = tag.getBoolean("flying"); noGravity = tag.getBoolean("noGravity");
        if (!EmbeddingNbt.compatible(tag)) return; // No archives or old semantic-space migrations.
        try {
            if (tag.getBoolean("active")) {
                shallow(tag.getString("sourceDimension"), tag.getString("landmark"),
                        new Vec3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")));
                deep = tag.getBoolean("deep");
            }
            if (tag.getBoolean("hasTarget")) target(tag.getString("targetDimension"), tag.getString("targetLandmark"),
                    new BlockPos(tag.getInt("tx"), tag.getInt("ty"), tag.getInt("tz")), Basis384f.fromBits(tag.getIntArray("targetBasis")));
        } catch (IllegalArgumentException invalid) { active = false; deep = false; clearTarget(); }
    }
    @Override public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
        EmbeddingNbt.stamp(tag);
        tag.putBoolean("active", active); tag.putBoolean("deep", deep);
        tag.putString("sourceDimension", dimension); tag.putString("landmark", landmark);
        tag.putDouble("x", position.x); tag.putDouble("y", position.y); tag.putDouble("z", position.z);
        tag.putBoolean("hasTarget", hasShallowTarget());
        tag.putString("targetDimension", targetDimension); tag.putString("targetLandmark", targetLandmark);
        tag.putInt("tx", target.getX()); tag.putInt("ty", target.getY()); tag.putInt("tz", target.getZ());
        if (hasShallowTarget()) tag.putIntArray("targetBasis", targetBasis.toBits());
        else tag.remove("targetBasis");
        tag.putBoolean("savedAbilities", savedAbilities); tag.putBoolean("allowFlying", allowFlying);
        tag.putBoolean("flying", flying); tag.putBoolean("noGravity", noGravity);
    }
}
