package io.github.mysticism.landmark;

import java.util.List;

/** Immutable metadata and opaque geometry-version references; never hydrates geometry. */
public record LandmarkMetadata(Landmark header,List<String> geometryKeys) {
    public LandmarkMetadata {
        if(!header.geometry().pages().isEmpty()) throw new IllegalArgumentException("metadata contains geometry");
        geometryKeys=List.copyOf(geometryKeys);
        if(geometryKeys.size()>4096 || header.geometry().frontiers().size()>4096 || header.ownership().claims().size()>256)
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
