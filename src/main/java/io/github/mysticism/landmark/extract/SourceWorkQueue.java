package io.github.mysticism.landmark.extract;

import java.util.*;

/** The production pending/stitch queue, not a parallel test model. Exclusion precedes callbacks
 * and capacity accounting, so generated edits cannot invalidate work or monopolize its budget. */
final class SourceWorkQueue extends AbstractSet<ExtractionJournal.Region> {
    private final int capacity;
    private final LinkedHashSet<ExtractionJournal.Region> regions = new LinkedHashSet<>();
    SourceWorkQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("source queue capacity");
        this.capacity = capacity;
    }
    /** Controller's real edit adapter: invalidate/enqueue only an admitted source edit. */
    boolean edited(ExtractionJournal.Region region, Runnable acceptedEdit) {
        if (!SourceDimensions.isSource(region.dimension())) return false;
        acceptedEdit.run();
        return true;
    }
    @Override public boolean add(ExtractionJournal.Region region) {
        if (!SourceDimensions.isSource(region.dimension())) return false;
        if (regions.size() >= capacity && !regions.contains(region)) return false;
        return regions.add(region);
    }
    @Override public int size() { return regions.size(); }
    @Override public boolean contains(Object region) { return regions.contains(region); }
    @Override public boolean remove(Object region) { return regions.remove(region); }
    @Override public void clear() { regions.clear(); }
    @Override public Iterator<ExtractionJournal.Region> iterator() { return regions.iterator(); }
}
