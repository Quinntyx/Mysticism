package io.github.mysticism.client.spiritworld;

import io.github.mysticism.landmark.*;
import io.github.mysticism.net.SpiritProjectionState;
import io.github.mysticism.vector.*;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Only server-authoritative absolute positions; never reads camera, predictor or initial CCA sync. */
final class SpiritGlyphFrame {
    private final ProjectionFrame frame;
    private final Map<String,Vec3d> positions=new HashMap<>();
    SpiritGlyphFrame(ProjectionFrame frame) { SpiritProjectionState.encodeFrame(frame);this.frame=frame; }
    /** Old source callers compile, but cannot manufacture an authenticated placement. */
    @Deprecated SpiritGlyphFrame(Basis384f basis,Vec384f origin,Vec3d anchor) { throw new IllegalArgumentException("Legacy local glyph frame is unauthenticated"); }
    void place(String id,Point3 position) {
        SpiritProjectionState.validatePosition(position);
        if(!positions.containsKey(id) && positions.size()>=128)throw new IllegalArgumentException("Glyph placement budget");
        positions.put(id,new Vec3d(position.x(),position.y(),position.z()));
    }
    Vec3d position(String id,Vec384f embedding) {
        EmbeddingSpace.requireCurrent(embedding); var position=positions.get(id);
        if(position==null)throw new IllegalArgumentException("Missing authoritative glyph placement");return position;
    }
    float scale(Vec384f embedding) { EmbeddingSpace.requireCurrent(embedding);return (float)Math.max(.25,Math.min(1.5,1/Math.sqrt(Math.max(.01,embedding.squareDistance(frame.semanticOrigin().vector()))))); }
    void retain(Set<String> ids) { if(ids.size()>128)throw new IllegalArgumentException("Glyph visibility budget");positions.keySet().removeIf(id->!ids.contains(id)); }
    int size() { return positions.size(); }
}
