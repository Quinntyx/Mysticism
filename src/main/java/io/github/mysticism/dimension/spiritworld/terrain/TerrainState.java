package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentState;
import java.util.*;

/** Spirit-world-local overlay manifest and frozen frame. Incompatible saves fail closed. */
public final class TerrainState extends PersistentState {
    public static final String KEY="mysticism.spirit_terrain.v1";
    public static final Type<TerrainState> TYPE=new Type<>(TerrainState::new,TerrainState::fromNbt,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private ProjectionFrame frame;
    private final Map<String,Placement> placements=new TreeMap<>();
    public Placement placement(LandmarkMetadata landmark) {
        if(frame==null)throw new IllegalStateException("missing frame");
        frame.semanticOrigin().profile().requireCompatible(landmark.header().baseEmbedding().profile());
        var old=placements.get(landmark.id());
        if(old!=null) {
            if(!old.sourceAnchor().equals(landmark.header().anchor()))throw new IllegalArgumentException("changed source anchor");
            return old;
        }
        if(placements.size()>=256)throw new IllegalStateException("frozen placement catalog budget reached");
        var next=frame.place(landmark.header(),1); placements.put(landmark.id(),next); markDirty(); return next;
    }
    final OverlayLedger<BlockPalette.State> ledger=new OverlayLedger<>(65536);
    public ProjectionFrame frame() { return frame; }
    public void initialize(ProjectionFrame frame) {
        if(this.frame!=null)throw new IllegalStateException("frozen terrain frame already initialized");
        this.frame=Objects.requireNonNull(frame); markDirty();
    }
    static NbtCompound material(BlockPalette.State state) {
        NbtCompound n=new NbtCompound(),p=new NbtCompound(); n.putString("block",state.blockId());
        state.properties().forEach(p::putString); n.put("properties",p); return n;
    }
    static BlockPalette.State material(NbtCompound n) {
        if(!n.contains("block",NbtElement.STRING_TYPE) || !n.contains("properties",NbtElement.COMPOUND_TYPE))throw new IllegalArgumentException("material fields");
        Map<String,String> p=new TreeMap<>(); NbtCompound properties=n.getCompound("properties");
        if(properties.getKeys().size()>64)throw new IllegalArgumentException("palette properties");
        for(String key:properties.getKeys()) {
            if(!properties.contains(key,NbtElement.STRING_TYPE))throw new IllegalArgumentException("property type");
            p.put(key,properties.getString(key));
        }
        return new BlockPalette.State(n.getString("block"),p);
    }
    private static Point3 point(NbtCompound n,String key) {
        NbtList p=n.getList(key,NbtElement.DOUBLE_TYPE); if(p.size()!=3)throw new IllegalArgumentException("point");
        return new Point3(p.getDouble(0),p.getDouble(1),p.getDouble(2));
    }
    private static void point(NbtCompound n,String key,Point3 p) {
        NbtList values=new NbtList(); values.add(NbtDouble.of(p.x())); values.add(NbtDouble.of(p.y())); values.add(NbtDouble.of(p.z())); n.put(key,values);
    }
    public static TerrainState fromNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup) {
        if(!n.contains("schema",NbtElement.INT_TYPE) || n.getInt("schema")!=1
                || !n.contains("entries",NbtElement.LIST_TYPE) || !n.contains("regions",NbtElement.LIST_TYPE)
                || !n.contains("placements",NbtElement.LIST_TYPE))throw new IllegalArgumentException("terrain schema/fields");
        NbtList rawEntries=(NbtList)n.get("entries"),rawRegions=(NbtList)n.get("regions");
        if(!rawEntries.isEmpty() && rawEntries.getHeldType()!=NbtElement.COMPOUND_TYPE
                || !rawRegions.isEmpty() && rawRegions.getHeldType()!=NbtElement.INT_ARRAY_TYPE)throw new IllegalArgumentException("terrain list types");
        if(n.contains("frame") && !n.contains("frame",NbtElement.COMPOUND_TYPE))throw new IllegalArgumentException("terrain frame type");
        TerrainState s=new TerrainState();
        if(n.contains("frame",NbtElement.COMPOUND_TYPE)) {
            NbtCompound f=n.getCompound("frame");
            var profile=LandmarkNbt.decodeProfile(f.getCompound("profile"));
            var origin=new LandmarkEmbedding(profile,Vec384f.fromBits(f.getIntArray("semanticOrigin")));
            s.frame=new ProjectionFrame(f.getLong("epoch"),f.getLong("seed"),origin,point(f,"realmOrigin"),
                    Vec384f.fromBits(f.getIntArray("x")),Vec384f.fromBits(f.getIntArray("y")),Vec384f.fromBits(f.getIntArray("z")),f.getDouble("scale"));
        }
        NbtList locations=n.getList("placements",NbtElement.COMPOUND_TYPE);
        NbtList rawLocations=(NbtList)n.get("placements");
        if(locations.size()>256 || !rawLocations.isEmpty() && rawLocations.getHeldType()!=NbtElement.COMPOUND_TYPE)throw new IllegalArgumentException("placement budget/type");
        for(int i=0;i<locations.size();i++) {
            NbtCompound v=locations.getCompound(i); String id=v.getString("id"); long[] a=v.getLongArray("anchor");
            if(s.frame==null || !id.matches("lm-[0-9a-f]{64}") || a.length!=3)throw new IllegalArgumentException("placement fields/frame");
            var placement=new Placement(id,s.frame.epoch(),point(v,"realm"),new BlockPoint(a[0],a[1],a[2]),1);
            if(s.placements.put(id,placement)!=null)throw new IllegalArgumentException("duplicate placement");
        }
        NbtList list=n.getList("entries",NbtElement.COMPOUND_TYPE),regions=n.getList("regions",NbtElement.INT_ARRAY_TYPE);
        if(list.size()>65536 || regions.size()>128 || s.frame==null && (!list.isEmpty() || !regions.isEmpty()))
            throw new IllegalArgumentException("terrain state budget/frame");
        Map<OverlayLedger.Pos,OverlayLedger.Entry<BlockPalette.State>> saved=new HashMap<>();
        for(int i=0;i<list.size();i++) {
            NbtCompound e=list.getCompound(i);
            if(!e.contains("pos",NbtElement.INT_ARRAY_TYPE) || !e.contains("owner",NbtElement.STRING_TYPE)
                    || !e.contains("protected",NbtElement.BYTE_TYPE) || !e.contains("original",NbtElement.COMPOUND_TYPE)
                    || !e.contains("generated",NbtElement.COMPOUND_TYPE))throw new IllegalArgumentException("entry fields");
            int[] p=e.getIntArray("pos");
            if(p.length!=3 || Math.abs((long)p[0])>29999984 || Math.abs((long)p[2])>29999984 || p[1]<-2048 || p[1]>2048)
                throw new IllegalArgumentException("terrain position");
            String owner=e.getString("owner"); if(!owner.matches("lm-[0-9a-f]{64}"))throw new IllegalArgumentException("terrain owner");
            var pos=new OverlayLedger.Pos(p[0],p[1],p[2]);
            if(saved.put(pos,new OverlayLedger.Entry<>(material(e.getCompound("original")),material(e.getCompound("generated")),owner,e.getBoolean("protected")))!=null)
                throw new IllegalArgumentException("duplicate terrain position");
        }
        Set<OverlayLedger.Region> rs=new TreeSet<>();
        for(int i=0;i<regions.size();i++) {
            int[] r=((NbtIntArray)regions.get(i)).getIntArray(); if(r.length!=3)throw new IllegalArgumentException("region");
            var region=new OverlayLedger.Region(r[0],r[1],r[2]); var o=region.origin();
            if(Math.abs((long)o.x())>30000000 || Math.abs((long)o.z())>30000000 || o.y()<-2048 || o.y()>2048 || !rs.add(region))throw new IllegalArgumentException("region bounds/duplicate");
        }
        for(var p:saved.keySet())if(!rs.contains(p.region()))throw new IllegalArgumentException("entry outside owned region");
        s.ledger.restore(saved,rs); return s;
    }
    @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup) {
        n.putInt("schema",1);
        if(frame!=null) {
            NbtCompound f=new NbtCompound(); f.put("profile",LandmarkNbt.encodeProfile(frame.semanticOrigin().profile()));
            f.putIntArray("semanticOrigin",frame.semanticOrigin().vector().toBits()); point(f,"realmOrigin",frame.realmOrigin());
            f.putIntArray("x",frame.axisX().toBits()); f.putIntArray("y",frame.axisY().toBits()); f.putIntArray("z",frame.axisZ().toBits());
            f.putLong("seed",frame.seed()); f.putLong("epoch",frame.epoch()); f.putDouble("scale",frame.blocksPerSemanticUnit()); n.put("frame",f);
        }
        NbtList entries=new NbtList(),regions=new NbtList();
        ledger.entries().entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<OverlayLedger.Pos,OverlayLedger.Entry<BlockPalette.State>> e)->e.getKey().x())
                .thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z())).forEach(e->{
            NbtCompound v=new NbtCompound(); var p=e.getKey(); var entry=e.getValue(); v.putIntArray("pos",new int[]{p.x(),p.y(),p.z()});
            v.put("original",material(entry.original())); v.put("generated",material(entry.generated())); v.putString("owner",entry.owner()); v.putBoolean("protected",entry.protectedEdit()); entries.add(v);
        });
        for(var r:ledger.regions())regions.add(new NbtIntArray(new int[]{r.x(),r.y(),r.z()}));
        NbtList locations=new NbtList();
        placements.forEach((id,p)->{
            NbtCompound v=new NbtCompound(); v.putString("id",id); v.putLongArray("anchor",new long[]{p.sourceAnchor().x(),p.sourceAnchor().y(),p.sourceAnchor().z()});
            point(v,"realm",p.realmAnchor()); locations.add(v);
        });
        n.put("placements",locations); n.put("entries",entries); n.put("regions",regions); return n;
    }
}
