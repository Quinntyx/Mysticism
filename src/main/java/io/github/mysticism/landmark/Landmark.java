package io.github.mysticism.landmark;

import java.util.Objects;

/** Immutable extraction/core contract. Base embedding never mutates in response to activity.
 * Kind is an extractor assertion, not inferred by this package. No physical terrain is generated here.
 */
public record Landmark(String id, String dimension, String algorithmVersion, Kind kind, String biome,
                       BlockPoint anchor, Bounds bounds, LandmarkEmbedding baseEmbedding, double baseImportance,
                       ActivityMetadata activity, Ownership ownership, SourceGeometry geometry,
                       long revision, String provenance) {
    public enum Kind { BIOME, CAVE, MOUNTAIN }
    public Landmark {
        Objects.requireNonNull(bounds); Objects.requireNonNull(baseEmbedding); Objects.requireNonNull(activity);
        Objects.requireNonNull(ownership); Objects.requireNonNull(geometry); Objects.requireNonNull(provenance);
        if (!Objects.equals(id,LandmarkIds.seed(dimension,algorithmVersion,kind,biome,anchor))) throw new IllegalArgumentException("noncanonical seed ID");
        if (revision<0 || !Double.isFinite(baseImportance) || baseImportance<0 || baseImportance>1 || !bounds.contains(anchor.x(),anchor.y(),anchor.z()))
            throw new IllegalArgumentException("landmark metadata");
        for(GeometryPage page:geometry.pages()) if(!bounds.contains(page.bounds())) throw new IllegalArgumentException("page outside landmark bounds");
        for(FrontierFace face:geometry.frontiers()) if(!dimension.equals(face.dimension())) throw new IllegalArgumentException("frontier dimension");
    }
    public boolean provisional() { return !geometry.frontierClosed(); }
    public Landmark withActivity(ActivityMetadata next, Ownership claims) {
        return new Landmark(id,dimension,algorithmVersion,kind,biome,anchor,bounds,baseEmbedding,baseImportance,next,claims,geometry,Math.addExact(revision,1),provenance);
    }
}
