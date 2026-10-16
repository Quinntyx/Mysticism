package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.VerticalSeeds;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Runs through runtimeSelfTest's main-based discovery, not the retired test source set. */
public final class VerticalDiscoveryTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            check(type.isInstance(failure), "wrong failure: " + failure);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    public static void main(String[] args) {
        selection();
        resumableColumns();
        limits();
        actualExtraction();
        System.out.println("VerticalDiscoveryTest: " + checks + " checks passed");
    }

    private static void selection() {
        check(VerticalSeeds.band(-1) == -1 && VerticalSeeds.band(-33) == -2,
                "absolute negative Y uses floor division");
        List<VerticalSeeds.Probe> probes = new ArrayList<>(List.of(
                new VerticalSeeds.Probe(new BlockPoint(2, -41, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(14, -55, 14), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(10, 5, 14), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(10, 5, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(2, 40, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(2, 150, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(2, 196, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(2, 250, 2), true, 200),
                new VerticalSeeds.Probe(new BlockPoint(2, -60, 2), false, 200)));
        List<BlockPoint> expected = List.of(new BlockPoint(14, -55, 14),
                new BlockPoint(10, 5, 2), new BlockPoint(2, 40, 2), new BlockPoint(2, 150, 2));
        check(VerticalSeeds.seeds(probes, 100).equals(expected), "all bands, lowest Y before X, stable X/Z ties");
        for (int i = 0; i < 50; i++) {
            Collections.shuffle(probes, new Random(i));
            check(VerticalSeeds.seeds(probes, 100).equals(expected), "independent of observation order");
        }
        check(VerticalSeeds.seeds(probes, 2).equals(expected.subList(0, 2)), "explicit limit respected");
        check(VerticalSeeds.seeds(probes, 0).isEmpty(), "zero limit");
        rejects(IllegalArgumentException.class, () -> VerticalSeeds.seeds(probes, -1));
        rejects(UnsupportedOperationException.class, () -> VerticalSeeds.seeds(probes, 100).clear());
    }

    private static void resumableColumns() {
        List<VerticalSeeds.Column> columns = new ArrayList<>();
        for (int x : VerticalSeeds.LATTICE) for (int z : VerticalSeeds.LATTICE)
            columns.add(new VerticalSeeds.Column(-32 + x, 48 + z, 200));
        var scan = new VerticalSeeds.Scan(columns, -64, 320);
        columns.clear(); // snapshot coordinates; no live chunk/collection retention
        Set<BlockPoint> observed = new HashSet<>();
        Set<Long> airLevels = Set.of(-55L, -19L, 5L, 40L, 73L, 105L, 137L, 170L);
        rejects(IllegalStateException.class, scan::pollSeed);
        while (!scan.complete()) {
            int prior = observed.size();
            scan.advance(17, p -> {
                check(observed.add(p), "no duplicate reads across ticks");
                check(p.y() > -64 && p.y() < 196, "floor/sky/margin excluded");
                return airLevels.contains(p.y()); // single-block cave layers: all stride residues
            });
            check(scan.lastSampled() <= 17 && scan.lastSampled() > 0, "strict per-tick read budget");
            check(observed.size() - prior == scan.lastSampled(), "accurate read accounting");
        }
        check(observed.size() == 16 * 259, "every relevant Y at all sixteen columns");
        check(scan.seedCount() == airLevels.size(), "upper/middle/deep caves are retained, not just two deepest");
        // Simulate a full hint queue for many ticks: no reads or candidate loss after completion.
        for (int i = 0; i < 20; i++) {
            check(scan.advance(17, p -> { throw new AssertionError("read after complete"); }), "completed scan");
            check(scan.lastSampled() == 0 && scan.seedCount() == airLevels.size(), "backpressure retains all bands");
        }
        List<BlockPoint> seeds = new ArrayList<>();
        while (scan.seedCount() > 0) seeds.add(scan.pollSeed().orElseThrow());
        check(seeds.stream().map(BlockPoint::y).toList().equals(airLevels.stream().sorted().toList()),
                "drained seeds span all observed vertical levels in order");
        check(seeds.stream().allMatch(p -> p.x() == -30 && p.z() == 50), "deterministic horizontal tie break");
        check(scan.pollSeed().isEmpty(), "drain terminates without repeated seeds");
    }

    private static void limits() {
        var solid = new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(0, 0, 100)), -64, 80);
        Set<BlockPoint> observed = new HashSet<>();
        while (!solid.advance(9, p -> { observed.add(p); return false; })) {}
        check(observed.size() == 143 && observed.stream().allMatch(p -> p.y() < 80), "world top clamps high heightmap");
        check(solid.pollSeed().isEmpty(), "solid column does not invent cave air");
        var empty = new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(0, 0, -64)), -64, 320);
        check(empty.complete() && empty.pollSeed().isEmpty(), "empty generated column");
        check(new VerticalSeeds.Scan(List.of(), -64, 320).complete(), "no columns");
        var uneven = new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(2, 2, 20),
                new VerticalSeeds.Column(6, 2, 200)), -64, 320);
        Set<BlockPoint> unevenReads = new HashSet<>();
        while (!uneven.advance(7, p -> { unevenReads.add(p); return p.y() == 73; })) {}
        check(unevenReads.stream().filter(p -> p.x() == 2).allMatch(p -> p.y() < 16),
                "low column does not probe surface/sky");
        check(uneven.pollSeed().orElseThrow().equals(new BlockPoint(6, 73, 2)),
                "high terrain uses its own heightmap, not the chunk center height");
        rejects(IllegalArgumentException.class, () -> solid.advance(0, p -> true));
        rejects(IllegalArgumentException.class, () -> solid.advance(-1, p -> true));
        rejects(IllegalArgumentException.class, () -> new VerticalSeeds.Scan(List.of(), 0, 0));
        rejects(IllegalArgumentException.class, () -> new VerticalSeeds.Scan(
                Collections.nCopies(17, new VerticalSeeds.Column(0, 0, 0)), -64, 320));
    }

    private static void actualExtraction() {
        var scan = new VerticalSeeds.Scan(List.of(new VerticalSeeds.Column(2, 2, 200)), -64, 320);
        Set<Long> levels = Set.of(-55L, 40L, 150L);
        while (!scan.advance(31, p -> levels.contains(p.y()))) {}
        while (scan.seedCount() > 0) {
            BlockPoint seed = scan.pollSeed().orElseThrow();
            var bounds = new Bounds(seed.x()-1, seed.y()-1, seed.z()-1, seed.x()+2, seed.y()+2, seed.z()+2);
            List<SourceLandmarks.Cell> cells = new ArrayList<>();
            for (long x=bounds.minX(); x<bounds.maxX(); x++)
                for (long y=bounds.minY(); y<bounds.maxY(); y++)
                    for (long z=bounds.minZ(); z<bounds.maxZ(); z++) {
                        var p = new BlockPoint(x,y,z);
                        cells.add(new SourceLandmarks.Cell(p, new BlockPalette.State(
                                p.equals(seed)?"minecraft:cave_air":"minecraft:stone", Map.of()),
                                "minecraft:plains", false));
                    }
            var prepared = SourceLandmarks.prepare(new SourceLandmarks.Region("minecraft:overworld", bounds, cells, true),
                    List.of(), new BlockPos((int)seed.x(), (int)seed.y(), (int)seed.z()), null, false, 0, 63, Map.of());
            check(prepared != null && prepared.kind() == Landmark.Kind.CAVE, "real Ensure preparation discovers vertical cave");
            check(prepared.anchor().equals(seed), "source coordinate anchor, not ground/chunk anchor");
            var mask = OwnershipGeometry.expand(prepared.geometry());
            check(mask.size() == 7 && mask.get(seed).air(), "exact connected air and six solid walls");
            check(mask.keySet().stream().allMatch(p -> bounds.contains(p.x(),p.y(),p.z())), "no fabricated column ownership");
        }
    }
}
