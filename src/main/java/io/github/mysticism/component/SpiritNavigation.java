package io.github.mysticism.component;

import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.ladysnake.cca.api.v3.component.ComponentV3;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/** Per-player navigation, not a shared world frame. Target coordinates and vectors are snapshots. */
public final class SpiritNavigation implements ComponentV3, AutoSyncedComponent {
    private boolean active, deep, semanticReady, landingApproach, supportApproach;
    private boolean modelCompatible = true;
    private long motionEpoch;
    private String dimension = "", landmark = "";
    private Vec3d position = Vec3d.ZERO;
    private String targetDimension = "", targetLandmark = "";
    private BlockPos target = BlockPos.ORIGIN;
    private Vec3d targetPosition = Vec3d.ZERO;
    private Basis384f targetBasis = new Basis384f();
    private boolean savedAbilities, allowFlying, flying, noGravity;

    public boolean active() { return active; }
    public boolean deep() { return deep; }
    public boolean semanticReady() { return semanticReady; }
    public boolean modelCompatible() { return modelCompatible; }
    public boolean landingApproach() { return landingApproach; }
    public boolean supportApproach() { return supportApproach; }
    public long motionEpoch() { return motionEpoch; }
    private void resetPrediction() { motionEpoch = motionEpoch == Long.MAX_VALUE ? 0 : motionEpoch + 1; }
    public void setSemanticReady(boolean ready) {
        if (semanticReady != ready) resetPrediction();
        semanticReady = ready; if (ready) modelCompatible = true;
    }
    public void setLandingApproach(boolean approach) {
        if (landingApproach != approach) resetPrediction();
        landingApproach = approach;
    }
    public void setSupportApproach(boolean approach) {
        if (supportApproach != approach) resetPrediction();
        supportApproach = approach;
    }
    public String sourceDimension() { return dimension; }
    public String landmarkId() { return landmark; }
    public Vec3d sourcePosition() { return position; }
    public boolean hasShallowTarget() { return !targetDimension.isEmpty() && !targetLandmark.isEmpty(); }
    public String targetDimension() { return targetDimension; }
    public String targetLandmarkId() { return targetLandmark; }
    public BlockPos targetBlock() { return target; }
    public Vec3d targetPosition() { return targetPosition; }
    public Basis384f targetBasis() { return targetBasis.clone(); }
    public void enterDeep() {
        if (!active || !deep) resetPrediction();
        active = true; deep = true;
    }
    public void shallow(String dimension, String id, Vec3d pos) {
        check(dimension, id, pos);
        if (!active || deep || !this.dimension.equals(dimension) || !landmark.equals(id)) resetPrediction();
        this.dimension = dimension; landmark = id; position = pos; active = true; deep = false; supportApproach = false;
    }
    public void setActive(boolean active) {
        if (this.active != active) resetPrediction();
        this.active = active;
        if (!active) { deep = false; semanticReady = false; landingApproach = false; supportApproach = false; }
    }
    public void target(String dimension, String id, BlockPos pos) { target(dimension, id, pos, new Basis384f()); }
    public void target(String dimension, String id, BlockPos pos, Basis384f basis) {
        target(dimension, id, new Vec3d(pos.getX() + .5, pos.getY(), pos.getZ() + .5), basis);
    }
    public void target(String dimension, String id, Vec3d pos, Basis384f basis) {
        check(dimension, id, pos);
        if (id.isBlank() || basis == null) throw new IllegalArgumentException("Target requires real landmark and source basis");
        EmbeddingSpace.requireCurrent(basis.i); EmbeddingSpace.requireCurrent(basis.j); EmbeddingSpace.requireCurrent(basis.k);
        if (Math.abs(basis.i.length()-1) > .001 || Math.abs(basis.j.length()-1) > .001
                || Math.abs(basis.k.length()-1) > .001 || Math.abs(basis.i.dot(basis.j)) > .001
                || Math.abs(basis.i.dot(basis.k)) > .001 || Math.abs(basis.j.dot(basis.k)) > .001)
            throw new IllegalArgumentException("Target requires real landmark and orthonormal source basis");
        targetDimension = dimension; targetLandmark = id; targetPosition = pos; target = BlockPos.ofFloored(pos); targetBasis = basis.clone();
    }
    public void clearTarget() { targetDimension = ""; targetLandmark = ""; target = BlockPos.ORIGIN; targetPosition = Vec3d.ZERO; targetBasis = new Basis384f(); }

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
        active = false; deep = false; semanticReady = false; landingApproach = false; supportApproach = false;
        dimension = ""; landmark = ""; position = Vec3d.ZERO; clearTarget();
        savedAbilities = tag.getBoolean("savedAbilities");
        allowFlying = tag.getBoolean("allowFlying"); flying = tag.getBoolean("flying"); noGravity = tag.getBoolean("noGravity");
        modelCompatible = EmbeddingNbt.compatible(tag);
        motionEpoch = Math.max(0, tag.getLong("motionEpoch"));
        // Physical source pose/mode/permissions are NOT embeddings. Keep them across a model reset.
        // Corrupt generated targets/bindings cannot destroy otherwise valid source coordinates either.
        try {
            String source = tag.getString("sourceDimension");
            if (!source.isEmpty() && (!tag.contains("x", NbtElement.NUMBER_TYPE)
                    || !tag.contains("y", NbtElement.NUMBER_TYPE) || !tag.contains("z", NbtElement.NUMBER_TYPE)))
                throw new IllegalArgumentException("Missing physical source coordinates");
            Vec3d pose = new Vec3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
            if (!source.isEmpty()) { check(source, "", pose); dimension = source; position = pose; }
            if (tag.getBoolean("active")) { check(source, "", pose); active = true; deep = tag.getBoolean("deep"); }
        } catch (IllegalArgumentException invalidPose) { active = false; deep = false; dimension = ""; position = Vec3d.ZERO; }
        if (!modelCompatible) { resetPrediction(); return; } // Discard generated semantic data ONLY.
        try { String id = tag.getString("landmark"); check(dimension, id, position); landmark = id; }
        catch (IllegalArgumentException invalidBinding) { landmark = ""; }
        semanticReady = active && tag.getBoolean("semanticReady");
        landingApproach = active && deep && semanticReady && tag.getBoolean("landingApproach");
        supportApproach = active && deep && semanticReady && tag.getBoolean("supportApproach");
        try {
            if (tag.getBoolean("hasTarget")) {
                Vec3d foot;
                if (tag.contains("tfx") || tag.contains("tfy") || tag.contains("tfz")) {
                    if (!tag.contains("tfx", NbtElement.NUMBER_TYPE) || !tag.contains("tfy", NbtElement.NUMBER_TYPE)
                            || !tag.contains("tfz", NbtElement.NUMBER_TYPE)) throw new IllegalArgumentException("Invalid captured source foot");
                    foot = new Vec3d(tag.getDouble("tfx"), tag.getDouble("tfy"), tag.getDouble("tfz"));
                } else foot = new Vec3d(tag.getInt("tx") + .5, tag.getInt("ty"), tag.getInt("tz") + .5);
                target(tag.getString("targetDimension"), tag.getString("targetLandmark"), foot, Basis384f.fromBits(tag.getIntArray("targetBasis")));
            }
        } catch (IllegalArgumentException invalidTarget) { clearTarget(); }
    }
    @Override public void writeToNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
        EmbeddingNbt.stamp(tag);
        tag.putBoolean("active", active); tag.putBoolean("deep", deep);
        tag.putBoolean("semanticReady", semanticReady); tag.putBoolean("landingApproach", landingApproach);
        tag.putBoolean("supportApproach", supportApproach);
        tag.putLong("motionEpoch", motionEpoch);
        tag.putString("sourceDimension", dimension); tag.putString("landmark", landmark);
        tag.putDouble("x", position.x); tag.putDouble("y", position.y); tag.putDouble("z", position.z);
        tag.putBoolean("hasTarget", hasShallowTarget());
        tag.putString("targetDimension", targetDimension); tag.putString("targetLandmark", targetLandmark);
        tag.putInt("tx", target.getX()); tag.putInt("ty", target.getY()); tag.putInt("tz", target.getZ());
        tag.putDouble("tfx", targetPosition.x); tag.putDouble("tfy", targetPosition.y); tag.putDouble("tfz", targetPosition.z);
        if (hasShallowTarget()) tag.putIntArray("targetBasis", targetBasis.toBits());
        else tag.remove("targetBasis");
        tag.putBoolean("savedAbilities", savedAbilities); tag.putBoolean("allowFlying", allowFlying);
        tag.putBoolean("flying", flying); tag.putBoolean("noGravity", noGravity);
    }
}
