package io.github.mysticism.landmark;

/** Unobserved neighbour face, persisted separately from known geometry. Bounds describe the
 * one-cell-thick unavailable neighbour slab in source blocks, not inferred air or solid.
 */
public record FrontierFace(String dimension, Bounds missingBounds, Direction direction,
                           long sourceRevision, String cursor) implements Comparable<FrontierFace> {
    public enum Direction { WEST, EAST, DOWN, UP, NORTH, SOUTH }
    public FrontierFace {
        if(dimension==null || dimension.isBlank() || sourceRevision<0 || cursor==null) throw new IllegalArgumentException("frontier");
        java.util.Objects.requireNonNull(missingBounds); java.util.Objects.requireNonNull(direction);
    }
    @Override public int compareTo(FrontierFace b) {
        int c=dimension.compareTo(b.dimension); if(c==0) c=direction.compareTo(b.direction);
        if(c==0) c=new BlockPoint(missingBounds.minX(),missingBounds.minY(),missingBounds.minZ()).compareTo(new BlockPoint(b.missingBounds.minX(),b.missingBounds.minY(),b.missingBounds.minZ()));
        if(c==0) c=new BlockPoint(missingBounds.maxX(),missingBounds.maxY(),missingBounds.maxZ()).compareTo(new BlockPoint(b.missingBounds.maxX(),b.missingBounds.maxY(),b.missingBounds.maxZ()));
        if(c==0) c=Long.compare(sourceRevision,b.sourceRevision); return c==0?cursor.compareTo(b.cursor):c;
    }
}
