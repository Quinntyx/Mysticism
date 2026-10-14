package io.github.mysticism.landmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
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
    /** Content identity for a generated page. CAS revision is deliberately NOT part of this
     * digest: cancelled attempts may retry the same revision with different terrain. The input
     * ID namespaces source coordinates/fragment ownership; landmark seed/anchor never change.
     * Existing stored coordinate-only page IDs remain readable without migration. */
    static String geometryPageVersion(GeometryPage page) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash));
            out.writeUTF(page.id());
            writeBounds(out,page.bounds());writeBounds(out,page.cells().rootBounds());
            out.writeInt(page.cells().resolution());out.writeLong(page.cells().maxSide());
            out.writeInt(page.palette().states().size());
            for(var state:page.palette().states()) {
                out.writeUTF(state.blockId());out.writeInt(state.properties().size());
                for(var property:state.properties().entrySet()) {out.writeUTF(property.getKey());out.writeUTF(property.getValue());}
            }
            var leaves=page.knownCells();out.writeInt(leaves.size());
            for(var leaf:leaves) {writeBounds(out,leaf.bounds());out.writeUTF(leaf.value().occupancy().name());out.writeInt(leaf.value().paletteIndex());}
            return "lm-"+HexFormat.of().formatHex(hash.digest());
        } catch(IOException | NoSuchAlgorithmException e) {throw new IllegalStateException("geometry digest",e);}
    }
    private static void writeBounds(DataOutput out,Bounds bounds) throws IOException {
        out.writeLong(bounds.minX());out.writeLong(bounds.minY());out.writeLong(bounds.minZ());
        out.writeLong(bounds.maxX());out.writeLong(bounds.maxY());out.writeLong(bounds.maxZ());
    }
    /** Fragment seed isolates disconnected masks occupying the same observation cube. */
    public static String geometryPage(String dimension, String fragmentSeedId, BlockPoint anchor, int side) {
        if(side<=0 || side>GeometryPage.MAX_SIDE || (side&(side-1))!=0 || fragmentSeedId.isBlank()) throw new IllegalArgumentException("page identity");
        return seed(dimension,"geometry-page-"+side,Landmark.Kind.BIOME,fragmentSeedId,anchor);
    }
}
