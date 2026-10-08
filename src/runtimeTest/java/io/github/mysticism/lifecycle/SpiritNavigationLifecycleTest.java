package io.github.mysticism.component;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Regression for repeated entry / mode change / reconnect coherence of the synced navigation
 * component: every lifecycle transition must bump motionEpoch (the signal both movement sides
 * use to re-anchor), approach flags must never survive deactivation or shallow re-binding, and
 * persisted state must restore coherently (or discard cleanly on a model mismatch) so a
 * reconnect cannot resume a stale approach or a stale semantic anchor.
 */
public final class SpiritNavigationLifecycleTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static final Vec3d POS = new Vec3d(1.5, 64, -3.25);

    private static Vec384f vector(double value) {
        float[] data = new float[EmbeddingSpace.DIMENSIONS];
        data[0] = (float) value;
        return new Vec384f(data);
    }

    /** Reach steady shallow state, apply the mutation, and require that motionEpoch did (not) bump. */
    private static void epoch(String action, java.util.function.Consumer<SpiritNavigation> mutation, boolean mustBump) {
        SpiritNavigation nav = new SpiritNavigation();
        nav.enterDeep();
        nav.shallow("minecraft:overworld", "origin", POS);
        long before = nav.motionEpoch();
        mutation.accept(nav);
        check(mustBump == (nav.motionEpoch() != before), "motionEpoch contract violated: " + action);
    }

    public static void main(String[] args) {
        transitions();
        flags();
        persistence();
        abilities();
        System.out.println("SpiritNavigationLifecycleTest passed: " + checks + " checks");
    }

    private static void transitions() {
        SpiritNavigation fresh = new SpiritNavigation();
        long before = fresh.motionEpoch();
        fresh.enterDeep();
        check(fresh.motionEpoch() != before, "first activation via enterDeep bumps motionEpoch");
        check(fresh.active() && fresh.deep(), "enterDeep activates deep free flight");

        epoch("shallow binding from deep", nav -> nav.shallow("minecraft:overworld", "other", POS), true);
        epoch("re-binding to a different landmark", nav -> nav.shallow("minecraft:overworld", "elsewhere", POS), true);
        epoch("semantic anchor gained", nav -> nav.setSemanticReady(true), true);
        epoch("semantic anchor lost", nav -> nav.setSemanticReady(false), false);
        epoch("support approach starts", nav -> nav.setSupportApproach(true), true);
        epoch("landing approach starts", nav -> nav.setLandingApproach(true), true);
        epoch("landing approach ends", nav -> nav.setLandingApproach(false), false);
        epoch("deactivation", nav -> nav.setActive(false), true);
        epoch("redundant shallow rebind of the same source", nav -> nav.shallow("minecraft:overworld", "origin", POS), false);
        epoch("no-op landing approach end", nav -> nav.setLandingApproach(false), false);
        epoch("target capture", nav -> nav.target("minecraft:overworld", "lm", BlockPos.ORIGIN, new Basis384f()), false);

        // Idempotent re-writes never reset movement prediction.
        SpiritNavigation stable = new SpiritNavigation();
        stable.enterDeep();
        stable.setSemanticReady(true);
        long anchor = stable.motionEpoch();
        stable.setSemanticReady(true);
        check(stable.motionEpoch() == anchor, "idempotent semanticReady(true) does not bump");

        // A shallow binding clears a stale support approach and keeps the source pose.
        SpiritNavigation nav = new SpiritNavigation();
        nav.enterDeep();
        nav.setSupportApproach(true);
        nav.shallow("minecraft:overworld", "lm", POS);
        check(!nav.supportApproach() && nav.active() && !nav.deep(), "shallow binding clears support approach");
        check(nav.sourceDimension().equals("minecraft:overworld") && nav.sourcePosition().equals(POS), "source pose retained");
        // Deactivation clears every mode/approach flag so nothing survives into a later visit.
        nav.setLandingApproach(true);
        nav.setActive(false);
        check(!nav.active() && !nav.deep() && !nav.semanticReady() && !nav.landingApproach() && !nav.supportApproach(),
                "deactivation clears mode and approach flags");
    }

    private static void flags() {
        SpiritNavigation nav = new SpiritNavigation();
        nav.enterDeep();
        nav.setSemanticReady(true);
        // Validation rejects impossible bindings/targets instead of storing them.
        rejects(() -> nav.shallow("not a dimension", "lm", POS));
        rejects(() -> nav.shallow("minecraft:overworld", " ", POS));
        rejects(() -> nav.shallow("minecraft:overworld", "lm", new Vec3d(Double.NaN, 0, 0)));
        rejects(() -> nav.target("minecraft:overworld", "", BlockPos.ORIGIN, new Basis384f()));
        rejects(() -> nav.target("minecraft:overworld", "lm", BlockPos.ORIGIN, null));
        Basis384f identity = new Basis384f();
        rejects(() -> nav.target("minecraft:overworld", "lm", BlockPos.ORIGIN,
                new Basis384f(vector(2), identity.j, identity.k))); // non-unit axis
        rejects(() -> nav.target("minecraft:overworld", "lm", BlockPos.ORIGIN,
                new Basis384f(identity.i, identity.j, identity.i))); // degenerate parallel axis
        // A valid capture round-trips exactly.
        nav.target("minecraft:overworld", "lm", BlockPos.ORIGIN, identity);
        check(nav.hasShallowTarget() && nav.targetBasis().i.dot(identity.i) == 1f, "valid target stored");
        nav.clearTarget();
        check(!nav.hasShallowTarget(), "target cleared");
    }

    private static void persistence() {
        // Full deep state round-trips.
        SpiritNavigation saved = new SpiritNavigation();
        saved.rememberAbilities(false, false, false);
        saved.enterDeep();
        saved.shallow("minecraft:overworld", "origin", POS);
        saved.enterDeep();
        saved.setSemanticReady(true);
        saved.setLandingApproach(true);
        saved.target("minecraft:overworld", "lm", new BlockPos(1, 2, 3), new Basis384f());
        NbtCompound tag = new NbtCompound();
        saved.writeToNbt(tag, null);
        SpiritNavigation restored = new SpiritNavigation();
        restored.readFromNbt(tag, null);
        check(restored.active() && restored.deep() && restored.semanticReady(), "deep semantic state round-trips");
        check(restored.sourceDimension().equals("minecraft:overworld") && restored.sourcePosition().equals(POS), "source pose round-trips");
        check(restored.landmarkId().equals("origin"), "retained landmark round-trips");
        check(restored.hasShallowTarget() && restored.targetPosition().equals(new Vec3d(1.5, 2, 3.5)), "captured target round-trips");
        check(restored.landingApproach(), "deep+semantic landing approach round-trips");
        check(restored.modelCompatible(), "stamped save is model-compatible");

        // A shallow component never restores a deep-only approach flag.
        SpiritNavigation shallowSaved = new SpiritNavigation();
        shallowSaved.enterDeep();
        shallowSaved.setSemanticReady(true);
        shallowSaved.setSupportApproach(true);
        shallowSaved.shallow("minecraft:overworld", "origin", POS);
        NbtCompound shallowTag = new NbtCompound();
        shallowSaved.writeToNbt(shallowTag, null);
        SpiritNavigation shallowRestored = new SpiritNavigation();
        shallowRestored.readFromNbt(shallowTag, null);
        check(shallowRestored.active() && !shallowRestored.deep() && !shallowRestored.supportApproach() && !shallowRestored.landingApproach(),
                "approach flags never survive as shallow state");

        // Untagged (model-incompatible) semantic data is discarded but the physical source pose,
        // mode and saved abilities survive so a reconnect stays physically coherent.
        NbtCompound untagged = new NbtCompound();
        saved.writeToNbt(untagged, null);
        untagged.remove("embeddingFingerprint");
        SpiritNavigation incompatible = new SpiritNavigation();
        incompatible.readFromNbt(untagged, null);
        check(!incompatible.modelCompatible(), "untagged save is model-incompatible");
        check(incompatible.active() && incompatible.deep() && incompatible.sourcePosition().equals(POS),
                "physical pose/mode survives a model reset");
        check(!incompatible.semanticReady() && !incompatible.hasShallowTarget() && incompatible.landmarkId().isEmpty(),
                "semantic anchor/target/binding discarded on model reset");
        check(incompatible.hasSavedAbilities(), "saved abilities survive a model reset");
        check(incompatible.motionEpoch() >= 0, "epoch stays non-negative after a model reset");

        // Corrupt saved source coordinates cannot poison the component.
        NbtCompound corrupt = new NbtCompound();
        corrupt.putString("sourceDimension", "minecraft:overworld");
        corrupt.putBoolean("active", true); // x/y/z missing
        SpiritNavigation corruptRestored = new SpiritNavigation();
        corruptRestored.readFromNbt(corrupt, null);
        check(!corruptRestored.active() && corruptRestored.sourcePosition().equals(Vec3d.ZERO),
                "corrupt physical pose deactivates instead of restoring garbage");
    }

    private static void abilities() {
        SpiritNavigation nav = new SpiritNavigation();
        check(!nav.hasSavedAbilities(), "fresh component has no saved abilities");
        nav.rememberAbilities(true, false, true);
        // rememberAbilities is first-write-wins: a later mode's abilities never overwrite the
        // pre-entry snapshot that deactivation must restore.
        nav.rememberAbilities(false, true, false);
        check(nav.hasSavedAbilities() && nav.savedAllowFlying() && !nav.savedFlying() && nav.savedNoGravity(),
                "pre-entry ability snapshot is first-write-wins");
        nav.clearSavedAbilities();
        check(!nav.hasSavedAbilities(), "deactivation restores and clears the snapshot");
    }
}
