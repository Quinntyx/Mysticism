package io.github.mysticism.landmark;

import java.util.List;

/** Immutable metadata and opaque geometry-version references; never hydrates geometry. */
public record LandmarkMetadata(Landmark header,List<String> geometryKeys) {
    public LandmarkMetadata {
        if(!header.geometry().pages().isEmpty()) throw new IllegalArgumentException("metadata contains geometry");
        geometryKeys=List.copyOf(geometryKeys);
        // Aggregate geometry refs are paged on disk; only per-header fields have a cap.
        if(header.geometry().frontiers().size()>LandmarkNbt.MAX_FRONTIERS || header.ownership().claims().size()>LandmarkNbt.MAX_CLAIMS)
            throw new IllegalArgumentException("metadata limits");
        java.util.Set<String> pageIds=new java.util.HashSet<>();
        for(String key:geometryKeys) {
            if(!key.matches("mysticism[.]landmark[.]geometry[.]lm-[0-9a-f]{64}[.][0-9]+")
                    || !pageIds.add(key.substring(0,key.lastIndexOf('.')))) throw new IllegalArgumentException("geometry references");
        }
    }
    public String id() { return header.id(); }
    public long revision() { return header.revision(); }
}
