package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.component.LatentPos;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;

/** Merge regression: independently persisted navigation permission cannot substitute for
 * a valid native coordinate. No server, GPU, weights or fabricated runtime inference. */
public final class SourceAnchorRestoreTest {
    private static int checks;
    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }
    private static SpiritNavigation savedReadyNavigation() {
        var original = new SpiritNavigation();
        original.shallow("minecraft:overworld", "owner", new Vec3d(.25, 64.5, -.75));
        original.enterDeep();
        original.anchored(SpiritNavigation.AnchorOrigin.SOURCE);
        var tag = new NbtCompound(); original.writeToNbt(tag, null);
        var restored = new SpiritNavigation(); restored.readFromNbt(tag, null);
        return restored; // NAV's restoreSession callback deliberately has not run yet.
    }
    private static boolean delivered(SpiritNavigation nav, LatentPos position) {
        return SourceAnchorDelivery.canRestoreAnchor(nav.semanticReady(), position.hasCoordinate());
    }
    private static Vec384f tail(float value) {
        float[] components = new float[EmbeddingSpace.NATIVE_DIMENSIONS];
        components[700] = value;
        return new Vec384f(components);
    }
    public static void main(String[] args) {
        check(!SourceAnchorDelivery.canRestoreAnchor(false, false), "Neither payload grants delivery");
        check(!SourceAnchorDelivery.canRestoreAnchor(false, true), "Coordinate alone is not source permission");
        check(!SourceAnchorDelivery.canRestoreAnchor(true, false), "Saved permission alone is not a coordinate");
        check(SourceAnchorDelivery.canRestoreAnchor(true, true), "Both compatible payloads permit restore");

        var nav = savedReadyNavigation();
        var missing = new LatentPos();
        check(nav.semanticReady() && !delivered(nav, missing),
                "Terrain restoring first must not acknowledge an independently missing LatentPos");
        Vec384f carrier = tail(6), sourceCoordinate = tail(8);
        Vec384f restored = SourceAnchorDelivery.restoreCoordinate(delivered(nav, missing), carrier, sourceCoordinate);
        check(restored.squareDistance(carrier) == 0, "Missing q must not preapply the metadata source offset");
        // Once the real source callback arrives, apply its offset once, retaining carrier-minus-q.
        Vec384f before = Vec384f.ZERO();
        Vec384f rebased = SourceAnchorDelivery.acceptedCoordinate(nav, "minecraft:overworld", "owner",
                restored, before, sourceCoordinate).orElseThrow();
        check(rebased.clone().sub(sourceCoordinate).squareDistance(carrier.clone().sub(before)) == 0,
                "Late source delivery preserves physical projection instead of double-applying restore offset");
        check(carrier.data()[700] == 6 && sourceCoordinate.data()[700] == 8,
                "Carrier and source snapshots, including the native tail, remain owned by their callers");
        check(nav.deep(), "Late anchoring does not acquire shallow support or disable flight");

        var coordinateTag = new NbtCompound();
        var coordinate = new LatentPos(); coordinate.set(tail(2)); coordinate.writeToNbt(coordinateTag, null);
        var loaded = new LatentPos(); loaded.readFromNbt(coordinateTag, null);
        check(delivered(nav, loaded), "Valid independently persisted native q restores permission");
        check(SourceAnchorDelivery.restoreCoordinate(delivered(nav, loaded), carrier, sourceCoordinate)
                .squareDistance(sourceCoordinate) == 0, "Ready source restore uses the real source coordinate");

        int[] corrupt = new int[EmbeddingSpace.NATIVE_DIMENSIONS];
        corrupt[700] = Float.floatToIntBits(Float.NaN);
        coordinateTag.putIntArray("v", corrupt); loaded.readFromNbt(coordinateTag, null);
        check(nav.semanticReady() && !delivered(nav, loaded), "Malformed native tail revokes terrain delivery before NAV runs");
        coordinateTag.putIntArray("v", new int[384]); loaded.readFromNbt(coordinateTag, null);
        check(!delivered(nav, loaded), "Legacy/reduced vectors never grant restore through resizing or migration");
        // An unstamped native array is still incompatible, not a fallback coordinate.
        var unstamped = new NbtCompound(); unstamped.putIntArray("v", new int[EmbeddingSpace.NATIVE_DIMENSIONS]);
        loaded.readFromNbt(unstamped, null);
        check(!delivered(nav, loaded), "Unstamped native-length array is not proof of compatible q");

        coordinate.set(Vec384f.ZERO()); coordinate.writeToNbt(coordinateTag, null);
        loaded.readFromNbt(coordinateTag, null);
        check(EmbeddingNbt.compatible(coordinateTag) && delivered(nav, loaded),
                "Explicit initialized native ZERO is valid, unlike the reset/default ZERO");
        nav.restoreSession(missing.hasCoordinate());
        check(!nav.semanticReady() && nav.deep(), "NAV's later callback agrees without changing physical mode");
        check(!delivered(nav, loaded), "Even valid ZERO cannot resurrect revoked navigation permission");
        var second = savedReadyNavigation();
        check(delivered(second, loaded), "One player's revoked state does not leak to another observer");
        second.anchored(SpiritNavigation.AnchorOrigin.CONCEPT);
        check(SourceAnchorDelivery.acceptedCoordinate(second, "minecraft:overworld", "owner",
                carrier, before, sourceCoordinate).isEmpty(), "Concept readiness is not late source-delivery permission");
        restored.mul(0);
        check(carrier.data()[700] == 6, "Restore coordinate is a detached snapshot");
        System.out.println("Source anchor restore: " + checks + " checks passed (no game/model inference)");
    }
}
