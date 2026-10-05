package io.github.mysticism.landmark;

/** Continuous rigid translation/uniform scale contract; source geometry is not generated here. */
public record Placement(String landmarkId,long projectionEpoch,Point3 realmAnchor,BlockPoint sourceAnchor,double blocksPerSourceBlock) {
    public Placement {
        if(landmarkId==null || landmarkId.isBlank() || projectionEpoch<0 || !Double.isFinite(blocksPerSourceBlock) || blocksPerSourceBlock<=0)
            throw new IllegalArgumentException("placement");
        java.util.Objects.requireNonNull(realmAnchor); java.util.Objects.requireNonNull(sourceAnchor);
    }
    public Point3 projectSource(Point3 sourcePoint) {
        return realmAnchor.add(new Point3(sourcePoint.x()-sourceAnchor.x(),sourcePoint.y()-sourceAnchor.y(),sourcePoint.z()-sourceAnchor.z()).scale(blocksPerSourceBlock));
    }
    public RealmBounds projectedBounds(Bounds source) {
        return new RealmBounds(projectSource(new Point3(source.minX(),source.minY(),source.minZ())),projectSource(new Point3(source.maxX(),source.maxY(),source.maxZ())));
    }
    /** Smoothstep translation between layouts. Pinned near-player collision placements stay frozen.
     * Scale/source anchor changes require explicit topology replacement, not unsafe interpolation.
     */
    public Placement transitionTo(Placement next,double progress,boolean collisionPinned) {
        if(!landmarkId.equals(next.landmarkId) || !sourceAnchor.equals(next.sourceAnchor) || blocksPerSourceBlock!=next.blocksPerSourceBlock)
            throw new IllegalArgumentException("different placement identity/topology");
        if(!Double.isFinite(progress)) throw new IllegalArgumentException("progress");
        if(collisionPinned) return this;
        double t=BorderDither.smoothstep(progress);
        return new Placement(landmarkId,t<1?projectionEpoch:next.projectionEpoch,realmAnchor.interpolate(next.realmAnchor,t),sourceAnchor,blocksPerSourceBlock);
    }
}
