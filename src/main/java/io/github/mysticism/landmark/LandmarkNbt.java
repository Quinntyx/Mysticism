package io.github.mysticism.landmark;

import io.github.mysticism.vector.Vec384f;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Function;

/** Versioned strict NBT codecs. Corrupt/new-schema data fails loudly; it is never replaced by an empty world. */
public final class LandmarkNbt {
    public static final int SCHEMA=1;
    public static final int MAX_GEOMETRY_REFS=4096, MAX_FRONTIERS=4096, MAX_CLAIMS=256;
    private LandmarkNbt() {}
    private static void require(NbtCompound n,String key,int type) {
        if(!n.contains(key,type)) throw new IllegalArgumentException("missing/wrong NBT field: "+key);
    }
    private static String string(NbtCompound n,String key) { require(n,key,NbtElement.STRING_TYPE); return n.getString(key); }
    private static long number(NbtCompound n,String key) { require(n,key,NbtElement.LONG_TYPE); return n.getLong(key); }
    private static double real(NbtCompound n,String key) { require(n,key,NbtElement.DOUBLE_TYPE); return n.getDouble(key); }
    private static NbtCompound compound(NbtCompound n,String key) { require(n,key,NbtElement.COMPOUND_TYPE); return n.getCompound(key); }
    private static NbtList list(NbtCompound n,String key,int childType,int max) {
        require(n,key,NbtElement.LIST_TYPE); NbtList raw=(NbtList)n.get(key);
        if(raw.size()>max || !raw.isEmpty() && raw.getHeldType()!=childType) throw new IllegalArgumentException("NBT list limit/type: "+key);
        return raw;
    }
    private static void version(NbtCompound n) {
        require(n,"schema",NbtElement.INT_TYPE); if(n.getInt("schema")!=SCHEMA) throw new IllegalArgumentException("unsupported landmark schema");
    }
    private static NbtCompound fresh() { NbtCompound n=new NbtCompound(); n.putInt("schema",SCHEMA); return n; }
    public static NbtCompound encodeProfile(EmbeddingProfile p) {
        NbtCompound n=fresh(); n.putString("model",p.model()); n.putString("revision",p.revision()); n.putString("tokenizer",p.tokenizer());
        n.putString("prefix",p.prefixPolicy()); n.putInt("dimensions",p.dimensions()); n.putString("normalization",p.normalization().name()); n.putString("descriptor",p.descriptorSchema()); return n;
    }
    public static EmbeddingProfile decodeProfile(NbtCompound n) {
        version(n); require(n,"dimensions",NbtElement.INT_TYPE);
        return new EmbeddingProfile(string(n,"model"),string(n,"revision"),string(n,"tokenizer"),string(n,"prefix"),n.getInt("dimensions"),
                EmbeddingProfile.Normalization.valueOf(string(n,"normalization")),string(n,"descriptor"));
    }
    public static void putBounds(NbtCompound n,String key,Bounds b) { n.putLongArray(key,new long[]{b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()}); }
    public static Bounds getBounds(NbtCompound n,String key) {
        require(n,key,NbtElement.LONG_ARRAY_TYPE); long[] b=n.getLongArray(key); if(b.length!=6) throw new IllegalArgumentException("bounds length");
        return new Bounds(b[0],b[1],b[2],b[3],b[4],b[5]);
    }
    public static NbtCompound encodeGeometry(GeometryPage p) {
        NbtCompound n=fresh(); n.putString("id",p.id()); n.putLong("revision",p.revision()); putBounds(n,"bounds",p.bounds());
        putBounds(n,"root",p.cells().rootBounds()); n.putInt("resolution",p.cells().resolution()); n.putLong("maxSide",p.cells().maxSide());
        NbtList palette=new NbtList();
        for(BlockPalette.State state:p.palette().states()) {
            NbtCompound entry=new NbtCompound(); entry.putString("block",state.blockId()); NbtCompound props=new NbtCompound();
            state.properties().forEach(props::putString); entry.put("properties",props); palette.add(entry);
        }
        n.put("palette",palette); NbtList leaves=new NbtList();
        for(var cell:p.knownCells()) { NbtCompound entry=new NbtCompound(); putBounds(entry,"bounds",cell.bounds()); entry.putString("occupancy",cell.value().occupancy().name()); entry.putInt("palette",cell.value().paletteIndex()); leaves.add(entry); }
        n.put("leaves",leaves); return n;
    }
    public static GeometryPage decodeGeometry(NbtCompound n) {
        version(n); require(n,"resolution",NbtElement.INT_TYPE);
        List<BlockPalette.State> palette=new ArrayList<>();
        for(NbtElement element:list(n,"palette",NbtElement.COMPOUND_TYPE,4096)) {
            NbtCompound entry=(NbtCompound)element,props=compound(entry,"properties"); Map<String,String> properties=new TreeMap<>();
            for(String key:props.getKeys()) properties.put(key,string(props,key));
            palette.add(new BlockPalette.State(string(entry,"block"),properties));
        }
        SparseOctree<BlockSample> tree=SparseOctree.empty(getBounds(n,"root"),n.getInt("resolution"),number(n,"maxSide"));
        for(NbtElement element:list(n,"leaves",NbtElement.COMPOUND_TYPE,GeometryPage.MAX_LEAVES)) {
            NbtCompound entry=(NbtCompound)element; Bounds b=getBounds(entry,"bounds"); require(entry,"palette",NbtElement.INT_TYPE);
            if(!tree.rootBounds().contains(b) || !tree.query(b,1).isEmpty()) throw new IllegalArgumentException("overlapping/outside geometry leaves");
            tree=tree.with(b,new BlockSample(BlockSample.Occupancy.valueOf(string(entry,"occupancy")),entry.getInt("palette")),8192);
        }
        return new GeometryPage(string(n,"id"),number(n,"revision"),getBounds(n,"bounds"),new BlockPalette(palette),tree);
    }
    public static NbtCompound encodeLandmark(Landmark l,List<String> geometryKeys) {
        if(geometryKeys.size()!=l.geometry().pages().size() || geometryKeys.size()>MAX_GEOMETRY_REFS
                || l.geometry().frontiers().size()>MAX_FRONTIERS || l.ownership().claims().size()>MAX_CLAIMS) throw new IllegalArgumentException("metadata page limit; partition observations/claims first");
        NbtCompound n=fresh(); n.putString("id",l.id()); n.putString("dimension",l.dimension()); n.putString("algorithm",l.algorithmVersion()); n.putString("kind",l.kind().name()); n.putString("biome",l.biome());
        n.putLongArray("anchor",new long[]{l.anchor().x(),l.anchor().y(),l.anchor().z()}); putBounds(n,"bounds",l.bounds());
        n.put("profile",encodeProfile(l.baseEmbedding().profile())); n.putIntArray("embedding",l.baseEmbedding().vector().toBits());
        n.putDouble("importance",l.baseImportance()); n.putDouble("activity",l.activity().level()); n.putLong("activityTick",l.activity().evaluatedTick()); n.putLong("revision",l.revision()); n.putString("provenance",l.provenance());
        NbtList refs=new NbtList(); geometryKeys.forEach(key->refs.add(NbtString.of(key))); n.put("geometry",refs);
        NbtList claims=new NbtList(); for(Ownership.Claim c:l.ownership().claims()) { NbtCompound e=new NbtCompound(); e.putString("player",c.player().toString()); e.putLong("tick",c.claimTick()); claims.add(e); } n.put("claims",claims);
        NbtList frontiers=new NbtList(); for(FrontierFace f:l.geometry().frontiers()) {
            NbtCompound e=new NbtCompound(); e.putString("dimension",f.dimension()); putBounds(e,"missing",f.missingBounds()); e.putString("direction",f.direction().name()); e.putLong("revision",f.sourceRevision()); e.putString("cursor",f.cursor()); frontiers.add(e);
        } n.put("frontiers",frontiers); return n;
    }
    public static Landmark decodeLandmark(NbtCompound n,Function<String,GeometryPage> geometryLoader) {
        version(n); require(n,"anchor",NbtElement.LONG_ARRAY_TYPE); long[] a=n.getLongArray("anchor"); if(a.length!=3) throw new IllegalArgumentException("anchor length");
        EmbeddingProfile profile=decodeProfile(compound(n,"profile")); require(n,"embedding",NbtElement.INT_ARRAY_TYPE); int[] bits=n.getIntArray("embedding");
        if(bits.length!=profile.dimensions()) throw new IllegalArgumentException("embedding dimension");
        float[] values=new float[bits.length]; for(int i=0;i<bits.length;i++) values[i]=Float.intBitsToFloat(bits[i]);
        List<GeometryPage> pages=new ArrayList<>(); for(NbtElement ref:list(n,"geometry",NbtElement.STRING_TYPE,MAX_GEOMETRY_REFS)) pages.add(geometryLoader.apply(ref.asString()));
        List<Ownership.Claim> claims=new ArrayList<>(); for(NbtElement e:list(n,"claims",NbtElement.COMPOUND_TYPE,MAX_CLAIMS)) { NbtCompound c=(NbtCompound)e; claims.add(new Ownership.Claim(UUID.fromString(string(c,"player")),number(c,"tick"))); }
        List<FrontierFace> frontiers=new ArrayList<>(); for(NbtElement e:list(n,"frontiers",NbtElement.COMPOUND_TYPE,MAX_FRONTIERS)) {
            NbtCompound f=(NbtCompound)e; frontiers.add(new FrontierFace(string(f,"dimension"),getBounds(f,"missing"),FrontierFace.Direction.valueOf(string(f,"direction")),number(f,"revision"),string(f,"cursor")));
        }
        return new Landmark(string(n,"id"),string(n,"dimension"),string(n,"algorithm"),Landmark.Kind.valueOf(string(n,"kind")),string(n,"biome"),new BlockPoint(a[0],a[1],a[2]),getBounds(n,"bounds"),
                new LandmarkEmbedding(profile,new Vec384f(values)),real(n,"importance"),new ActivityMetadata(real(n,"activity"),number(n,"activityTick")),new Ownership(claims),new SourceGeometry(pages,frontiers),number(n,"revision"),string(n,"provenance"));
    }
}
