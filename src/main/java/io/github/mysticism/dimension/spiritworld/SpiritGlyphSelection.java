package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.embedding.EmbeddingProfile;
import io.github.mysticism.vector.*;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** CPU-only immutable catalogue. Build and select on a bounded worker, never a tick thread. */
public final class SpiritGlyphSelection {
    public static final int MAX_CANDIDATES = 4096, MAX_CLUSTERS = 32, PER_CLUSTER = 16, MAX_VISIBLE = 128;
    public static final double SEMANTIC_RADIUS = 1.25;
    public record Glyph(String id, String clusterId, int clusterSlot, int slot, Vec384f embedding) {
        public Glyph { embedding = embedding.clone(); }
        @Override public Vec384f embedding() { return embedding.clone(); }
    }
    private record Entry(String id, Vec384f vector, int index) {}
    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    // At most 32*4096 cached scalar distances (<=512KiB), reused across moving windows.
    private final Map<String, float[]> distanceCache = new LinkedHashMap<>(32, .75f, true);
    private final EmbeddingProfile profile;

    public SpiritGlyphSelection(Map<String, Vec384f> input, EmbeddingProfile profile) {
        if (!EmbeddingProfile.current().equals(profile)) throw new IllegalArgumentException("Glyph profile mismatch");
        if (input.size() > MAX_CANDIDATES) throw new IllegalArgumentException("Glyph catalogue exceeds candidate budget");
        this.profile = profile;
        var vectors = new TreeMap<String, Vec384f>();
        input.forEach((id, v) -> {
            Objects.requireNonNull(id); EmbeddingSpace.requireCurrent(v);
            if (v.length() > 0) vectors.put(id, v.clone());
        });
        var indexed = new TreeMap<String, Entry>();
        vectors.forEach((id, vector) -> indexed.put(id, new Entry(id, vector, indexed.size())));
        byId = Collections.unmodifiableMap(indexed);
        entries = List.copyOf(indexed.values());
    }

    /** Gate ALL members before clustering; outside-radius outliers must not steal cluster budget.
     * <=4096 radius checks, <=32 farthest-coverage passes, <=32 assignment checks/member.
     * Existing semantic seeds and representatives persist while they remain in the window. */
    public List<Glyph> select(Vec384f current, EmbeddingProfile currentProfile, int limit, List<Glyph> previous) {
        if (!profile.equals(currentProfile)) return List.of();
        try { EmbeddingSpace.requireCurrent(current); } catch (RuntimeException invalid) { return List.of(); }
        if (current.length() == 0 || limit <= 0) return List.of();
        limit = Math.min(limit, MAX_VISIBLE);
        var window = new TreeMap<String, Entry>();
        for (Entry entry : entries) if (entry.vector.squareDistance(current) < SEMANTIC_RADIUS * SEMANTIC_RADIUS)
            window.put(entry.id, entry);
        if (window.isEmpty()) return List.of();
        Map<String, Glyph> retained = new HashMap<>();
        Map<String, Integer> oldClusterSlots = new HashMap<>();
        var seeds = new ArrayList<String>();
        if (previous != null) for (int i = 0; i < Math.min(previous.size(), MAX_VISIBLE); i++) {
            Glyph g = previous.get(i);
            if (g == null) continue;
            retained.put(g.id, g);
            oldClusterSlots.putIfAbsent(g.clusterId, g.clusterSlot);
            if (window.containsKey(g.clusterId) && !seeds.contains(g.clusterId) && seeds.size() < MAX_CLUSTERS)
                seeds.add(g.clusterId);
        }
        if (seeds.isEmpty()) seeds.add(window.firstKey());
        var distances = new TreeMap<String, Double>();
        window.keySet().forEach(id -> distances.put(id, Double.POSITIVE_INFINITY));
        for (String seed : seeds) updateDistances(window, distances, seed);
        // Deterministic farthest coverage, not random K-means or density-weighted nearest K.
        while (seeds.size() < MAX_CLUSTERS) {
            String next = null; double farthest = .25 * .25;
            for (var e : distances.entrySet()) if (e.getValue() > farthest) {
                farthest = e.getValue(); next = e.getKey();
            }
            if (next == null) break;
            seeds.add(next); updateDistances(window, distances, next);
        }
        int[] clusterSlots = new int[seeds.size()];
        Arrays.fill(clusterSlots, -1);
        boolean[] clusterOccupied = new boolean[MAX_CLUSTERS];
        for (int c = 0; c < seeds.size(); c++) {
            int old = oldClusterSlots.getOrDefault(seeds.get(c), -1);
            if (old >= 0 && old < MAX_CLUSTERS && !clusterOccupied[old]) {
                clusterSlots[c] = old; clusterOccupied[old] = true;
            }
        }
        for (int c = 0; c < seeds.size(); c++) if (clusterSlots[c] < 0) {
            int slot = 0; while (clusterOccupied[slot]) slot++;
            clusterSlots[c] = slot; clusterOccupied[slot] = true;
        }
        var groups = new ArrayList<List<Entry>>();
        for (int i = 0; i < seeds.size(); i++) groups.add(new ArrayList<>());
        float[][] seedDistances = new float[seeds.size()][];
        for (int c = 0; c < seeds.size(); c++) seedDistances[c] = distancesFor(seeds.get(c));
        for (Entry entry : window.values()) {
            int cluster = 0; double best = Double.POSITIVE_INFINITY;
            for (int c = 0; c < seeds.size(); c++) {
                double d = seedDistances[c][entry.index];
                if (d < best) { best = d; cluster = c; }
            }
            groups.get(cluster).add(entry);
        }
        for (List<Entry> group : groups) group.sort(Comparator
                .<Entry>comparingInt(e -> retained.containsKey(e.id) ? 0 : 1).thenComparing(e -> e.id));
        var result = new ArrayList<Glyph>();
        boolean[][] occupied = new boolean[seeds.size()][PER_CLUSTER];
        // Allocate one representative per cluster before a second, independent of concentration.
        for (int round = 0; round < PER_CLUSTER && result.size() < limit; round++) {
            for (int c = 0; c < groups.size() && result.size() < limit; c++) {
                if (groups.get(c).size() <= round) continue;
                Entry entry = groups.get(c).get(round);
                Glyph old = retained.get(entry.id);
                int slot = old != null && old.clusterId.equals(seeds.get(c)) && old.slot >= 0
                        && old.slot < PER_CLUSTER && !occupied[c][old.slot] ? old.slot : -1;
                if (slot < 0) {
                    // Progressive angular coverage: 2 members opposite, 4 quadrants, 8 octants.
                    for (int i = 0; i < PER_CLUSTER; i++) {
                        int candidate = Integer.reverse(i) >>> 28;
                        if (!occupied[c][candidate]) { slot = candidate; break; }
                    }
                }
                occupied[c][slot] = true;
                result.add(new Glyph(entry.id, seeds.get(c), clusterSlots[c], slot, entry.vector));
            }
        }
        return List.copyOf(result);
    }

    private void updateDistances(Map<String, Entry> window, Map<String, Double> distances, String seed) {
        float[] cached = distancesFor(seed);
        window.forEach((id, entry) -> distances.put(id, Math.min(distances.get(id), cached[entry.index])));
    }

    private synchronized float[] distancesFor(String seed) {
        float[] cached = distanceCache.get(seed);
        if (cached != null) return cached;
        Vec384f center = byId.get(seed).vector;
        cached = new float[entries.size()];
        for (Entry entry : entries) cached[entry.index] = entry.vector.squareDistance(center);
        distanceCache.put(seed, cached);
        while (distanceCache.size() > MAX_CLUSTERS) distanceCache.remove(distanceCache.keySet().iterator().next());
        return cached; // private, read-only after publication
    }

    /** Stable tangent rings centered relative to the interpolated HEAD, not a remote medoid.
     * Caller interpolates targets; no clock/random phase, model call or ID re-keying.
     * Ring slots remain on the semantic-facing hemisphere within the supplied block radius. */
    public static Vec3d position(Glyph glyph, Vec384f current, Basis384f basis, Vec3d head, double radius) {
        if (glyph == null || head == null || basis == null || !Double.isFinite(radius) || radius <= 0)
            return head == null ? Vec3d.ZERO : head;
        Vec3d direction;
        try { direction = Projection384f.projectToWorld(glyph.embedding, current, basis, Vec3d.ZERO, 1); }
        catch (RuntimeException invalid) { return head; }
        if (direction.lengthSquared() < 1e-12) {
            // Distinct, progressively even directions for projection-null semantic clusters.
            int cluster = Math.floorMod(glyph.clusterSlot, MAX_CLUSTERS) + 1;
            double z = 1 - 2 * (Integer.toUnsignedLong(Integer.reverse(cluster)) / 4294967296.0);
            double azimuth = cluster * Math.PI * (3 - Math.sqrt(5));
            double radial = Math.sqrt(Math.max(0, 1 - z * z));
            direction = new Vec3d(radial * Math.cos(azimuth), z, radial * Math.sin(azimuth));
        } else direction = direction.normalize();
        Vec3d reference = Math.abs(direction.y) < .9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d tangent = direction.crossProduct(reference).normalize();
        Vec3d bitangent = direction.crossProduct(tangent);
        // Fixed 16-slot ring: changing count never changes phase or existing angles.
        int hash = glyph.clusterId.hashCode();
        hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15; hash *= 0x846ca68b; hash ^= hash >>> 16;
        double phase = Integer.toUnsignedLong(hash) * (2 * Math.PI / 4294967296.0);
        double angle = phase + Math.floorMod(glyph.slot, PER_CLUSTER) * (2 * Math.PI / PER_CLUSTER);
        return head.add(direction.multiply(radius * .65))
                .add(tangent.multiply(radius * .65 * Math.cos(angle)))
                .add(bitangent.multiply(radius * .65 * Math.sin(angle)));
    }
}
