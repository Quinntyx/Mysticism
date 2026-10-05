package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.embedding.EmbeddingProfile;
import io.github.mysticism.vector.*;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Exercises production CPU selection/layout (no renderer/model/world fixture). */
public final class SpiritGlyphSelectionTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static Vec384f axis(int index) { float[] a = new float[EmbeddingSpace.DIMENSIONS]; a[index] = 1; return new Vec384f(a); }
    private static void rejects(Runnable action) { try { action.run(); throw new AssertionError("Expected rejection"); } catch (IllegalArgumentException expected) { checks++; } }
    public static void main(String[] args) {
        var profile = EmbeddingProfile.current();
        var data = new TreeMap<String, Vec384f>();
        // Dense cluster cannot bury sparse, semantically distinct in-radius clusters.
        for (int i = 0; i < 1000; i++) data.put("dense-" + i, axis(0));
        for (int i = 1; i <= 12; i++) data.put("sparse-" + i, axis(i));
        data.put("outside", axis(0).mul(5));
        var catalogue = new SpiritGlyphSelection(data, profile);
        var current = axis(30).mul(.1f);
        var chosen = catalogue.select(current, profile, 128, List.of());
        check(chosen.stream().filter(g -> g.id().startsWith("sparse")).count() == 12, "Radius-wide sparse diversity lost");
        check(chosen.size() <= 128, "Visibility bound");
        check(chosen.stream().noneMatch(g -> g.id().equals("outside")), "Outside radius leaked");
        var outliers = new TreeMap<>(data);
        for (int i = 40; i < 80; i++) outliers.put("outside-" + i, axis(i).mul(5));
        check(new SpiritGlyphSelection(outliers, profile).select(current, profile, 128, List.of()).stream()
                .filter(g -> g.id().startsWith("sparse")).count() == 12, "Outside outliers stole in-radius cluster budget");
        var identity = new HashMap<String, SpiritGlyphSelection.Glyph>();
        chosen.forEach(g -> { check(identity.put(g.id(), g) == null, "Duplicate ID"); });
        var slots = new HashSet<String>();
        chosen.forEach(g -> check(slots.add(g.clusterId() + ":" + g.slot()), "Duplicate cluster slot"));
        var perturbed = current.clone().add(axis(31).mul(.01f));
        var stable = catalogue.select(perturbed, profile, 128, chosen);
        for (var g : stable) {
            var old = identity.get(g.id());
            check(old != null && old.slot() == g.slot() && old.clusterId().equals(g.clusterId()), "Membership/slot drift without gate change");
        }
        // Insertion order, density and repeated calls cannot rerandomize phases/IDs.
        var reversed = new LinkedHashMap<String, Vec384f>();
        new ArrayList<>(data.descendingKeySet()).forEach(id -> reversed.put(id, data.get(id)));
        var reordered = new SpiritGlyphSelection(reversed, profile).select(current, profile, 128, List.of());
        check(chosen.stream().map(g -> g.id() + ":" + g.slot()).toList()
                .equals(reordered.stream().map(g -> g.id() + ":" + g.slot()).toList()), "Insertion-order instability");
        for (int i = 0; i < chosen.size(); i++) check(SpiritGlyphSelection.position(chosen.get(i), current, new Basis384f(), Vec3d.ZERO, 8)
                .equals(SpiritGlyphSelection.position(reordered.get(i), current, new Basis384f(), Vec3d.ZERO, 8)),
                "Deterministic rebuild/reload positions changed");
        check(catalogue.select(Vec384f.ZERO(), profile, 128, chosen).isEmpty(), "Zero current not safe");
        check(catalogue.select(null, profile, 128, chosen).isEmpty(), "Null current not safe");
        check(catalogue.select(new Vec384f(current.data(), "foreign"), profile, 128, chosen).isEmpty(), "Foreign vector leaked");
        var wrong = new EmbeddingProfile(profile.model(), "wrong", profile.dimensions(), profile.semantics(), "wrong");
        check(catalogue.select(current, wrong, 128, chosen).isEmpty(), "Profile mismatch leaked");
        rejects(() -> new SpiritGlyphSelection(data, wrong));
        rejects(() -> new SpiritGlyphSelection(Map.of("bad", new Vec384f(current.data(), "foreign")), profile));
        check(new SpiritGlyphSelection(Map.of("zero", Vec384f.ZERO()), profile)
                .select(current, profile, 128, chosen).isEmpty(), "Zero candidate not excluded");
        check(new SpiritGlyphSelection(Map.of(), profile).select(current, profile, 128, chosen).isEmpty(), "Empty catalogue unsafe");
        check(catalogue.select(current, profile, 0, chosen).isEmpty(), "Zero budget");
        check(catalogue.select(current, profile, 50000, chosen).size() <= 128, "Unbounded caller budget");
        var many = new HashMap<String, Vec384f>();
        for (int i = 0; i <= SpiritGlyphSelection.MAX_CANDIDATES; i++) many.put("id" + i, axis(0));
        rejects(() -> new SpiritGlyphSelection(many, profile));
        many.remove("id" + SpiritGlyphSelection.MAX_CANDIDATES);
        var full = new SpiritGlyphSelection(many, profile);
        long start = System.nanoTime();
        check(full.select(current, profile, 128, List.of()).size() == 16, "Dense collapse not bounded at 4096 candidates");
        System.out.println("4096-candidate dense selection: " + ((System.nanoTime() - start) / 1_000_000) + " ms");
        for (int i = 0; i < SpiritGlyphSelection.MAX_CANDIDATES; i++) many.put("id" + i, axis(i % 32));
        var varied = new SpiritGlyphSelection(many, profile);
        start = System.nanoTime();
        var bounded = varied.select(current, profile, 128, List.of());
        check(bounded.size() == 128 && bounded.stream().map(SpiritGlyphSelection.Glyph::clusterId).distinct().count() == 32,
                "Maximum-budget window lost diversity/bounds");
        System.out.println("4096-candidate/32-cluster cold selection: " + ((System.nanoTime() - start) / 1_000_000) + " ms");
        start = System.nanoTime();
        var warm = varied.select(current, profile, 128, bounded);
        check(warm.stream().map(g -> g.id() + ":" + g.slot()).toList()
                .equals(bounded.stream().map(g -> g.id() + ":" + g.slot()).toList()), "Warm distance cache changed selection");
        System.out.println("4096-candidate/32-cluster warm selection: " + ((System.nanoTime() - start) / 1_000_000) + " ms");
        var head = new Vec3d(100, 71.6, -500); var moved = head.add(5, -2, 3); var basis = new Basis384f();
        for (var g : chosen) {
            Vec3d position = SpiritGlyphSelection.position(g, current, basis, head, 8);
            check(position.squaredDistanceTo(head) < 64, "Position too far from head");
            check(position.squaredDistanceTo(SpiritGlyphSelection.position(g, current, basis, head, 8)) == 0, "Random phase");
            check(SpiritGlyphSelection.position(g, current, basis, moved, 8).subtract(position)
                    .squaredDistanceTo(new Vec3d(5, -2, 3)) < 1e-20, "Not head-relative");
            check(position.squaredDistanceTo(SpiritGlyphSelection.position(g, perturbed, basis, head, 8)) < .1, "Ring discontinuity for small motion");
            Vec3d direction = Projection384f.projectToWorld(g.embedding(), current, basis, Vec3d.ZERO, 1);
            if (direction.lengthSquared() > 1e-12) check(position.subtract(head).dotProduct(direction) > 0, "Semantic direction reversed");
            g.embedding().mul(0); check(g.embedding().length() > 0, "Glyph vector not cloned");
        }
        // Sparse rings must already be spread at two/four members, without changing old slots.
        var tight = new SpiritGlyphSelection(Map.of("a", axis(0), "b", axis(0), "c", axis(0), "d", axis(0)), profile);
        var pair = tight.select(current, profile, 2, List.of());
        check(pair.get(0).slot() == 0 && pair.get(1).slot() == 8, "Sparse pair not opposite");
        var four = tight.select(current, profile, 4, pair);
        check(four.stream().map(SpiritGlyphSelection.Glyph::slot).toList().equals(List.of(0, 8, 4, 12)), "Quadrants not evenly spread");
        var emptyProjection = new SpiritGlyphSelection(Map.of("one", axis(10), "two", axis(11)), profile)
                .select(current, profile, 128, List.of());
        check(SpiritGlyphSelection.position(emptyProjection.get(0), current, basis, head, 8)
                .squaredDistanceTo(SpiritGlyphSelection.position(emptyProjection.get(1), current, basis, head, 8)) > 1,
                "Projection-null clusters collapsed");
        var first = chosen.getFirst();
        check(SpiritGlyphSelection.position(first, current, basis, head, Double.NaN).equals(head), "Invalid radius");
        check(SpiritGlyphSelection.position(first, new Vec384f(current.data(), "foreign"), basis, head, 8).equals(head), "Invalid layout profile");
        // Tight gates remove old members; previous selections cannot pin outside-radius identities.
        var changed = catalogue.select(axis(0), profile, 128, chosen);
        check(changed.stream().allMatch(g -> g.embedding().squareDistance(axis(0)) < 1.25 * 1.25), "Retained ID bypassed membership");
        var boundary = new SpiritGlyphSelection(Map.of("edge", axis(0).mul(1.25f)), profile);
        check(boundary.select(axis(30).mul(.001f), profile, 128, List.of()).isEmpty(), "Strict radius boundary");
        System.out.println("SpiritGlyphSelectionTest passed: " + checks + " checks");
    }
}
