package io.github.mysticism.navigation;

import io.github.mysticism.component.*;
import io.github.mysticism.dimension.spiritworld.terrain.*;
import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.vector.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Actual component serialization and policy regressions; no game bootstrap or inference. */
public final class SpiritNavigationReadinessTest {
    private static int checks;
    private static void check(boolean condition, String message) { ++checks; if (!condition) throw new AssertionError(message); }
    private static SpiritNavigation restore(SpiritNavigation original) {
        var tag = new NbtCompound(); original.writeToNbt(tag, null);
        var copy = new SpiritNavigation(); copy.readFromNbt(tag, null); return copy;
    }
    private static void readiness() {
        var unbound = new SpiritNavigation(); unbound.enterDeep(); boolean rejected = false;
        try { unbound.anchored(SpiritNavigation.AnchorOrigin.SOURCE); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected && !unbound.semanticReady(), "Source provenance cannot be fabricated for an unbound arrival");
        var nav = new SpiritNavigation(); nav.shallow("minecraft:overworld", "known-landmark", new Vec3d(1.25, 64.5, -3.75));
        nav.restoreSession(true);
        check(!nav.semanticReady(), "Known metadata/native coordinate alone must not fabricate an anchor");
        nav.enterDeep(); nav.restoreSession(true);
        check(!nav.semanticReady(), "Deep mode/nonzero payload must not infer readiness");
        nav.anchored(SpiritNavigation.AnchorOrigin.SOURCE);
        check(nav.sourceAnchorMatches("minecraft:overworld", "known-landmark"), "Exact accepted source match");
        check(!nav.sourceAnchorMatches("minecraft:the_nether", "known-landmark"), "Foreign dimension cannot acknowledge delivery");
        check(!nav.sourceAnchorMatches("minecraft:overworld", "other-landmark"), "Foreign owner cannot acknowledge delivery");
        var copy = restore(nav); copy.setLandingApproach(true); copy.setSupportApproach(true); copy.restoreSession(true);
        check(copy.semanticReady() && copy.deep(), "Explicit compatible ready source restores deep mode");
        check(!copy.landingApproach() && !copy.supportApproach(), "Ephemeral approaches cannot resume across sessions");
        copy.restoreSession(false);
        check(!copy.semanticReady() && copy.anchorOrigin() == SpiritNavigation.AnchorOrigin.NONE, "Missing/corrupt q revokes saved permission");
        check(copy.active() && copy.deep() && copy.landmarkId().equals("known-landmark"), "q reset preserves physical mode and source pose");
        nav.anchored(SpiritNavigation.AnchorOrigin.CONCEPT);
        check(!nav.sourceAnchorMatches("minecraft:overworld", "known-landmark"), "A retained source ID on a concept anchor is NOT source delivery");
        check(restore(nav).anchorOrigin() == SpiritNavigation.AnchorOrigin.CONCEPT, "Concept provenance roundtrip");
        var oldTag = new NbtCompound(); nav.writeToNbt(oldTag, null); oldTag.remove("anchorOrigin");
        var old = new SpiritNavigation(); old.readFromNbt(oldTag, null);
        check(old.semanticReady() && old.anchorOrigin() == SpiritNavigation.AnchorOrigin.RETAINED, "Existing ready native saves keep permission without guessing provenance");
        oldTag.putString("sourceDimension", ""); oldTag.putString("anchorOrigin", "SOURCE"); old.readFromNbt(oldTag, null);
        check(old.semanticReady() && old.anchorOrigin() == SpiritNavigation.AnchorOrigin.RETAINED,
                "Missing source binding on a native saved coordinate must not claim verified source provenance");
        // A payload without the current-native stamp is incompatible; no v1 vector migration.
        var incompatible = new NbtCompound(); incompatible.putBoolean("active", true); incompatible.putBoolean("deep", true);
        incompatible.putString("sourceDimension", "minecraft:overworld"); incompatible.putDouble("x", 1.25);
        incompatible.putDouble("y", 64.5); incompatible.putDouble("z", -3.75); incompatible.putBoolean("semanticReady", true);
        var legacy = new SpiritNavigation(); legacy.readFromNbt(incompatible, null); legacy.restoreSession(true);
        check(legacy.active() && legacy.deep() && !legacy.semanticReady(), "Incompatible embeddings never authorize travel; native physical pose survives");
    }
    private static void zeroAndCorruptCoordinates() {
        var position = new LatentPos(); check(!position.hasCoordinate(), "Reset/default ZERO is unavailable");
        position.set(Vec384f.ZERO()); check(position.hasCoordinate(), "Explicit semantic origin is valid even at ZERO");
        var tag = new NbtCompound(); position.writeToNbt(tag, null); var loaded = new LatentPos(); loaded.readFromNbt(tag, null);
        check(loaded.hasCoordinate() && loaded.get().length() == 0, "Initialized ZERO survives native-profile serialization");
        var nav = new SpiritNavigation(); nav.enterDeep(); nav.anchored(SpiritNavigation.AnchorOrigin.CONCEPT); nav = restore(nav);
        nav.restoreSession(loaded.hasCoordinate());
        check(nav.active() && nav.deep() && nav.semanticReady() && nav.sourceDimension().isEmpty(), "Source-less concept flight at ZERO survives restore");
        position.set(null); position.writeToNbt(tag, null); loaded.readFromNbt(tag, null);
        check(!loaded.hasCoordinate(), "Explicit unavailable payload remains unavailable");
        tag.remove("coordinateAvailable"); loaded.readFromNbt(tag, null);
        check(loaded.hasCoordinate(), "Older current-native profile arrays stay readable; navigation still requires its explicit permission");
        tag.putIntArray("v", new int[384]); loaded.readFromNbt(tag, null);
        check(!loaded.hasCoordinate() && loaded.get().data().length == EmbeddingSpace.NATIVE_DIMENSIONS, "Obsolete dimensions are reset, not migrated/truncated");
        EmbeddingNbt.stamp(tag); int[] malformed = new int[EmbeddingSpace.DIMENSIONS]; malformed[700] = Float.floatToIntBits(Float.NaN);
        tag.putIntArray("v", malformed); loaded.readFromNbt(tag, null);
        check(!loaded.hasCoordinate(), "Malformed native tail invalidates the coordinate");
        var att = new LatentAttunement(); check(!att.hasTarget(), "Default target must not be presented as a concept destination");
        att.set(Vec384f.ZERO()); check(att.hasTarget(), "Explicit ZERO target is distinct from default/unset");
    }
    private static void lateDiscoveryAndPhysicalContinuity() {
        var nav = new SpiritNavigation(); nav.shallow("minecraft:overworld", "", Vec3d.ZERO); nav.enterDeep(); nav.setSupportApproach(true);
        long epoch = nav.motionEpoch();
        nav.bindSource("minecraft:overworld", "late-owner", new Vec3d(.25, 64.5, -.75));
        check(nav.deep() && nav.supportApproach(), "Late ownership binding cannot transiently toggle shallow or lose pending walk intent");
        check(nav.motionEpoch() == epoch + 1, "One source-binding change, not artificial shallow/deep mode transitions");
        nav.anchored(SpiritNavigation.AnchorOrigin.SOURCE);
        check(nav.deep() && nav.supportApproach() && nav.semanticReady(), "Source readiness does not acquire support/disable flight");
        nav.shallow("minecraft:overworld", "late-owner", nav.sourcePosition());
        check(!nav.deep() && !nav.supportApproach() && !nav.landingApproach(), "Real shallow acquisition clears approach flags");
        long stable = nav.motionEpoch(); nav.shallow("minecraft:overworld", "late-owner", new Vec3d(.5, 64.5, -.75));
        check(nav.motionEpoch() == stable, "Normal shallow walking must not invalidate prediction each tick");
        check(ShallowContinuity.grounded(false, true, false, false), "Initial visible physical floor does not require a semantic source anchor");
        check(ShallowContinuity.grounded(false, true, true, true), "Pending topology proof cannot take away existing mesh-supported gait");
        check(!ShallowContinuity.grounded(false, true, true, false), "Verified ownership loss must not authorize previous shallow binding");
        check(!ShallowContinuity.grounded(false, false, false, true), "Pending discovery cannot invent a floor");
        check(ShallowContinuity.awaitMapping(true, true) && !ShallowContinuity.awaitMapping(true, false)
                && !ShallowContinuity.awaitMapping(false, true), "Only actual support plus pending proof can defer unavailable mapping");
        var second = new SpiritNavigation(); second.enterDeep();
        check(!second.semanticReady() && nav.semanticReady() && !nav.deep(), "Independent player modes/readiness remain independent");
    }
    private static void carrierRebase() {
        float[] values = new float[EmbeddingSpace.NATIVE_DIMENSIONS]; values[700] = 2;
        Vec384f before = new Vec384f(values), carrier = before.clone().mul(3), after = before.clone().mul(4);
        var nav = new SpiritNavigation(); nav.shallow("minecraft:overworld", "owner", Vec3d.ZERO); nav.enterDeep();
        check(SourceAnchorDelivery.acceptedCoordinate(nav, "minecraft:overworld", "owner", carrier, before, after).isEmpty(),
                "Rejected/uninitialized navigation must not mark delivery or rebase terrain");
        nav.anchored(SpiritNavigation.AnchorOrigin.CONCEPT);
        check(SourceAnchorDelivery.acceptedCoordinate(nav, "minecraft:overworld", "owner", carrier, before, after).isEmpty(),
                "Concept-ready navigation retains its coordinate rather than acknowledging a source callback");
        Vec384f unreadyRestore = SourceAnchorDelivery.restoreCoordinate(false, before, after);
        check(unreadyRestore.squareDistance(before) == 0, "Unready restore cannot preapply metadata and then double-apply its source offset");
        unreadyRestore.mul(0); check(before.data()[700] == 2, "Restore returns detached coordinate snapshots");
        check(SourceAnchorDelivery.restoreCoordinate(true, before, after).squareDistance(after) == 0,
                "Ready native restore uses the recovered real source coordinate");
        nav.anchored(SpiritNavigation.AnchorOrigin.SOURCE);
        Vec384f rebased = SourceAnchorDelivery.acceptedCoordinate(nav, "minecraft:overworld", "owner", carrier, before, after).orElseThrow();
        check(rebased.clone().sub(after).squareDistance(carrier.clone().sub(before)) == 0,
                "Accepted anchor preserves carrier-minus-q physical projection exactly, including native tail coordinates");
        check(carrier.data()[700] == 6 && before.data()[700] == 2 && after.data()[700] == 8,
                "Rebasing cannot mutate input snapshots or truncate native v2 vectors");
        check(nav.deep(), "Carrier rebasing does not acquire walking mode");
    }
    public static void main(String[] args) {
        readiness(); zeroAndCorruptCoordinates(); lateDiscoveryAndPhysicalContinuity(); carrierRebase();
        System.out.println("PASS navigation readiness: " + checks + " checks");
    }
}
