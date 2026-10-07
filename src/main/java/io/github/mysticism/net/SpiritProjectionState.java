package io.github.mysticism.net;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentState;
import java.util.*;

/** One bounded persistent catalog per player; coordinates are ABSOLUTE realm blocks. */
public final class SpiritProjectionState extends PersistentState {
    public static final int SCHEMA=1, MAX_PLACEMENTS=4096;
    public static final String SEMANTICS="absolute-realm-blocks|frozen-landmark-frame|first-id-placement-v1";
    public static final Type<SpiritProjectionState> TYPE=new Type<>(SpiritProjectionState::new,SpiritProjectionState::fromNbt,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private ProjectionFrame frame;
    private final Map<String,Point3> placements=new TreeMap<>();
    public static String key(UUID player) { return "mysticism.spirit_projection.v1."+player; }
    public ProjectionFrame frame() { return frame; }
    public void initialize(ProjectionFrame source) {
        Objects.requireNonNull(source);
        if(frame!=null) {
            if(!encodeFrame(frame).equals(encodeFrame(source)))throw new IllegalArgumentException("Saved glyph frame differs from frozen terrain frame; explicit migration required");
            return;
        }
        validateProfile(source); frame=source; markDirty();
    }
    public Point3 position(String id,Vec384f vector) {
        SpiritDeltaPayload.validateId(id); EmbeddingSpace.requireCurrent(vector);
        if(frame==null)throw new IllegalStateException("No authoritative terrain frame");
        Point3 old=placements.get(id); if(old!=null)return old;
        if(placements.size()>=MAX_PLACEMENTS)throw new IllegalStateException("Persistent glyph placement budget reached");
        Point3 next=frame.project(new LandmarkEmbedding(frame.semanticOrigin().profile(),vector)); validatePosition(next);
        placements.put(id,next); markDirty(); return next;
    }
    public int size() { return placements.size(); }
    public static void validatePosition(Point3 p) {
        if(p==null || Math.abs(p.x())>30_000_000 || Math.abs(p.z())>30_000_000 || Math.abs(p.y())>4096)
            throw new IllegalArgumentException("Glyph position outside supported realm coordinates");
    }
    private static void validateProfile(ProjectionFrame f) {
        var p=f.semanticOrigin().profile();
        for(String value:List.of(p.model(),p.revision(),p.tokenizer(),p.prefixPolicy(),p.descriptorSchema()))
            if(value.length()>1024)throw new IllegalArgumentException("Oversized projection profile field");
        if(!p.model().equals(EmbeddingSpace.MODEL) || !p.revision().equals(EmbeddingSpace.REVISION) || p.dimensions()!=EmbeddingSpace.DIMENSIONS)
            throw new IllegalArgumentException("Unpinned glyph model/profile");
        EmbeddingSpace.requireCurrent(f.semanticOrigin().vector()); validatePosition(f.realmOrigin());
    }
    private static void point(NbtCompound n,String key,Point3 p) {
        validatePosition(p); NbtList l=new NbtList(); l.add(NbtDouble.of(p.x())); l.add(NbtDouble.of(p.y())); l.add(NbtDouble.of(p.z())); n.put(key,l);
    }
    private static Point3 point(NbtCompound n,String key) {
        var l=n.getList(key,NbtElement.DOUBLE_TYPE); if(l.size()!=3)throw new IllegalArgumentException("Invalid point");
        Point3 p=new Point3(l.getDouble(0),l.getDouble(1),l.getDouble(2)); validatePosition(p); return p;
    }
    public static NbtCompound encodeFrame(ProjectionFrame f) {
        validateProfile(f); NbtCompound n=new NbtCompound(); n.put("profile",LandmarkNbt.encodeProfile(f.semanticOrigin().profile()));
        n.putString("fingerprint",EmbeddingSpace.FINGERPRINT); n.putString("semantics",SEMANTICS); n.putInt("schema",SCHEMA);
        n.putLong("epoch",f.epoch()); n.putLong("seed",f.seed()); n.putDouble("scale",f.blocksPerSemanticUnit()); point(n,"realm",f.realmOrigin());
        n.putIntArray("origin",f.semanticOrigin().vector().toBits()); n.putIntArray("x",f.axisX().toBits()); n.putIntArray("y",f.axisY().toBits()); n.putIntArray("z",f.axisZ().toBits()); return n;
    }
    public static ProjectionFrame decodeFrame(NbtCompound n) {
        if(!n.contains("schema",NbtElement.INT_TYPE) || n.getInt("schema")!=SCHEMA || !SEMANTICS.equals(n.getString("semantics")) || !EmbeddingSpace.FINGERPRINT.equals(n.getString("fingerprint")))throw new IllegalArgumentException("Incompatible glyph frame");
        for(String k:List.of("epoch","seed"))if(!n.contains(k,NbtElement.LONG_TYPE))throw new IllegalArgumentException("Missing frame "+k);
        if(!n.contains("scale",NbtElement.DOUBLE_TYPE) || !n.contains("profile",NbtElement.COMPOUND_TYPE))throw new IllegalArgumentException("Missing frame metadata");
        ProjectionFrame f=new ProjectionFrame(n.getLong("epoch"),n.getLong("seed"),new LandmarkEmbedding(LandmarkNbt.decodeProfile(n.getCompound("profile")),Vec384f.fromBits(n.getIntArray("origin"))),point(n,"realm"),Vec384f.fromBits(n.getIntArray("x")),Vec384f.fromBits(n.getIntArray("y")),Vec384f.fromBits(n.getIntArray("z")),n.getDouble("scale")); validateProfile(f); return f;
    }
    public static SpiritProjectionState fromNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup) {
        if(!n.contains("schema",NbtElement.INT_TYPE) || n.getInt("schema")!=SCHEMA || !n.contains("frame",NbtElement.COMPOUND_TYPE) || !n.contains("placements",NbtElement.LIST_TYPE))throw new IllegalArgumentException("Invalid projection save");
        SpiritProjectionState s=new SpiritProjectionState(); s.frame=decodeFrame(n.getCompound("frame"));
        NbtList l=(NbtList)n.get("placements");
        if(l.size()>MAX_PLACEMENTS || !l.isEmpty() && l.getHeldType()!=NbtElement.COMPOUND_TYPE)throw new IllegalArgumentException("Placement save budget/type");
        for(int i=0;i<l.size();i++) { var e=l.getCompound(i); String id=e.getString("id"); SpiritDeltaPayload.validateId(id); if(s.placements.put(id,point(e,"position"))!=null)throw new IllegalArgumentException("Duplicate placement"); }
        return s;
    }
    @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup) {
        if(frame==null)throw new IllegalStateException("Uninitialized projection save"); n.putInt("schema",SCHEMA); n.put("frame",encodeFrame(frame)); NbtList l=new NbtList();
        placements.forEach((id,p)->{var e=new NbtCompound(); e.putString("id",id); point(e,"position",p); l.add(e);}); n.put("placements",l); return n;
    }
}
