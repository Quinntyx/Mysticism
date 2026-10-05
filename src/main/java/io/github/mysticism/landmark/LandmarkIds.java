package io.github.mysticism.landmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Seed IDs depend only on source identity, not arrival order, octree root, ownership, or projection. */
public final class LandmarkIds {
    private LandmarkIds() {}
    public static String seed(String dimension, String algorithmVersion, Landmark.Kind kind, String biome, BlockPoint anchor) {
        if (dimension.isBlank() || algorithmVersion.isBlank() || biome.isBlank()) throw new IllegalArgumentException("identity");
        String[] fields={dimension,algorithmVersion,kind.name(),biome,Long.toString(anchor.x()),Long.toString(anchor.y()),Long.toString(anchor.z())};
        StringBuilder text=new StringBuilder(); for(String s:fields) text.append(s.length()).append(':').append(s);
        try { return "lm-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String geometryPage(String dimension, BlockPoint anchor, int side) {
        return geometryPage(dimension,"shared-observation",anchor,side);
    }
    /** Fragment seed isolates disconnected masks occupying the same observation cube. */
    public static String geometryPage(String dimension, String fragmentSeedId, BlockPoint anchor, int side) {
        if(side<=0 || side>GeometryPage.MAX_SIDE || (side&(side-1))!=0 || fragmentSeedId.isBlank()) throw new IllegalArgumentException("page identity");
        return seed(dimension,"geometry-page-"+side,Landmark.Kind.BIOME,fragmentSeedId,anchor);
    }
}
