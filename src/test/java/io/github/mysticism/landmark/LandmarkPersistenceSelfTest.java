package io.github.mysticism.landmark;

import net.minecraft.SharedConstants;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Real Yarn NBT and PersistentStateManager; only existing Minecraft runtime dependencies. */
public final class LandmarkPersistenceSelfTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void fails(Class<? extends Throwable> type,Runnable call,String message) {
        checks++; try { call.run(); } catch(Throwable e) { if(type.isInstance(e)) return; throw new AssertionError(message,e); }
        throw new AssertionError(message+" did not fail");
    }
    private static Landmark withGeometry(long x) {
        BlockPoint anchor=new BlockPoint(x,0,0); Bounds bounds=Bounds.cube(x,0,0,4);
        BlockPalette palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        SparseOctree<BlockSample> cells=SparseOctree.<BlockSample>empty(bounds,1,4)
                .with(Bounds.cube(x,0,0,1),new BlockSample(BlockSample.Occupancy.SOLID,1),32)
                .with(Bounds.cube(x+1,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32);
        GeometryPage page=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",anchor,4),0,bounds,palette,cells);
        return LandmarkCoreSelfTest.landmark(anchor,bounds,LandmarkCoreSelfTest.embedding(0.25,0,0,0),0.7,
                new SourceGeometry(List.of(page),List.of(new FrontierFace("minecraft:overworld",Bounds.cube(x+4,0,0,1),FrontierFace.Direction.EAST,0,"resume:"+x))),0,
                new Ownership(List.of(new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000001"),1))));
    }
    private static void roundtripAndMalformed() {
        Landmark original=withGeometry(-4); GeometryPage page=original.geometry().pages().getFirst();
        NbtCompound geometry=LandmarkNbt.encodeGeometry(page);
        GeometryPage decoded=LandmarkNbt.decodeGeometry(geometry);
        check(decoded.id().equals(page.id()) && decoded.revision()==0 && decoded.knownCells().equals(page.knownCells()),"sparse geometry NBT roundtrip");
        check(decoded.cells().sample(-2,0,0)==null,"NBT unknown remains unobserved");
        check(decoded.cells().sample(-3,0,0).occupancy()==BlockSample.Occupancy.AIR,"NBT known air retained");
        String key="mysticism.landmark.geometry."+page.id()+".0";
        NbtCompound n=LandmarkNbt.encodeLandmark(original,List.of(key));
        Landmark restored=LandmarkNbt.decodeLandmark(n,k->{ check(k.equals(key),"geometry reference exact"); return decoded; });
        check(restored.id().equals(original.id()) && restored.baseEmbedding().equals(original.baseEmbedding()) && restored.geometry().frontiers().equals(original.geometry().frontiers()),"immutable landmark metadata NBT");
        check(restored.ownership().equals(original.ownership()) && restored.geometry().queryMaterials(original.bounds(),10).equals(original.geometry().queryMaterials(original.bounds(),10)),"palette and ownership roundtrip");
        check(restored.equals(original) && restored.hashCode()==original.hashCode(),"complete immutable value equality after NBT roundtrip");
        NbtCompound corrupt=n.copy(); corrupt.putIntArray("embedding",new int[]{0});
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(corrupt,k->decoded),"reject wrong vector length before geometry load");
        NbtCompound newer=n.copy(); newer.putInt("schema",LandmarkNbt.SCHEMA+1);
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(newer,k->decoded),"future schema never empty fallback");
        NbtCompound overlap=geometry.copy(); NbtList leaves=overlap.getList("leaves",NbtElement.COMPOUND_TYPE); leaves.add(leaves.getCompound(0).copy());
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeGeometry(overlap),"overlapping encoded leaves rejected");
        NbtCompound brokenType=n.copy(); brokenType.put("geometry",new NbtList()); brokenType.put("claims",new NbtList()); brokenType.putString("frontiers","unknown");
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(brokenType,k->decoded),"strict NBT field types");
    }
    private static PersistentStateManager manager(Path path,RegistryWrapper.WrapperLookup lookup) {
        // Identity DFU is a deterministic test dependency, not a replacement for the server's fixer.
        DataFixer identity=new DataFixer() {
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) { return input; }
            @Override public Schema getSchema(int version) { throw new UnsupportedOperationException("schema unnecessary for current-version fixture"); }
        };
        return new PersistentStateManager(path.toFile(),identity,lookup);
    }
    private static void pagingAliasesRestart() throws Exception {
        SharedConstants.createGameVersion();
        RegistryWrapper.WrapperLookup lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        Path dir=Files.createTempDirectory("landmark-persistent-state-");
        PersistentStateManager manager=manager(dir,lookup); LandmarkStore store=LandmarkStore.open(manager);
        Landmark a=withGeometry(-4),b=withGeometry(4);
        var put=store.stagePut(a,-1);
        check(put.remainingPages()==3 && !put.complete(),"geometry + metadata + catalog page budget");
        check(put.advance(1)==1 && put.remainingPages()==2,"one page of incremental work");
        check(store.find(a.id()).isEmpty(),"geometry staged but invisible");
        manager.save();
        check(LandmarkStore.open(manager(dir,lookup)).ids().isEmpty(),"restart cannot see partially published catalog");
        check(put.advance(1)==1 && store.find(a.id()).isEmpty(),"metadata staged but invisible");
        check(put.advance(1)==1 && put.complete() && put.advance(1)==0,"single bounded final publication");
        check(store.find(a.id()).orElseThrow().geometry().sample(-3,0,0).occupancy()==BlockSample.Occupancy.AIR,"published usable geometry");
        store.stagePut(b,-1).advance(8);
        manager.save();
        PersistentStateManager reloadedManager=manager(dir,lookup);
        LandmarkStore loaded=LandmarkStore.open(reloadedManager);
        check(loaded.ids().size()==2 && loaded.find(a.id()).orElseThrow().baseEmbedding().equals(a.baseEmbedding()),"real compressed PersistentState disk reload");
        LandmarkRepository.VerifiedConnectivity proof=new LandmarkRepository.VerifiedConnectivity(List.of(new LandmarkRepository.RevisionRef(a.id(),0),new LandmarkRepository.RevisionRef(b.id(),0)),"loaded-six-neighbour-proof");
        var merge=loaded.stageMerge(proof,1,new ImportancePolicy(0.8,20,0.01,1));
        check(merge.advance(1)==1 && loaded.ids().size()==2,"merge aliases unpublished while paging");
        while(!merge.complete()) check(merge.advance(1)==1,"bounded merge dirty pages");
        String canonical=List.of(a.id(),b.id()).stream().min(String::compareTo).orElseThrow();
        String alias=canonical.equals(a.id())?b.id():a.id();
        check(loaded.resolve(alias).equals(canonical) && loaded.find(alias).orElseThrow().id().equals(canonical),"published stable alias");
        reloadedManager.save();
        LandmarkStore aliasRestart=LandmarkStore.open(manager(dir,lookup));
        check(aliasRestart.resolve(alias).equals(canonical) && aliasRestart.find(alias).orElseThrow().revision()==1,"alias topology actual disk restart");
        NbtCompound manifest=loaded.writeNbt(new NbtCompound(),lookup);
        LandmarkStore decodedManifest=LandmarkStore.fromNbt(manifest,lookup);
        check(decodedManifest.writeNbt(new NbtCompound(),lookup).equals(manifest),"manifest aliases deterministic NBT roundtrip");
        NbtCompound cycle=manifest.copy(); NbtCompound aliases=cycle.getCompound("aliases"); aliases.putString("cycle-a","cycle-b"); aliases.putString("cycle-b","cycle-a");
        fails(IllegalArgumentException.class,()->LandmarkStore.fromNbt(cycle,lookup),"persistent alias cycle rejected");
        fails(IllegalArgumentException.class,()->loaded.stagePut(canonical.equals(a.id())?b:a,-1),"alias never reused as live ID");
        var cancelled=loaded.stagePut(loaded.find(canonical).orElseThrow().withActivity(new ActivityMetadata(0.6,2),new Ownership(List.of())),1);
        check(cancelled.advance(1)==1,"unchanged geometry versions bounded scan"); cancelled.cancel();
        check(loaded.find(canonical).orElseThrow().revision()==1,"cancel preserves prior revision");
        fails(IllegalStateException.class,()->cancelled.advance(1),"cancel cannot publish");
        AtomicReference<Throwable> threadFailure=new AtomicReference<>(); Thread thread=new Thread(()->{try { loaded.ids(); } catch(Throwable e) { threadFailure.set(e); }});
        thread.start(); thread.join(); check(threadFailure.get() instanceof IllegalStateException,"off-server-thread store access rejected");
        Landmark parent=loaded.find(canonical).orElseThrow();
        Landmark left=LandmarkCoreSelfTest.landmark(new BlockPoint(-3,0,0),Bounds.cube(-3,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        Landmark right=LandmarkCoreSelfTest.landmark(new BlockPoint(5,0,0),Bounds.cube(5,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        var split=loaded.stageSplit(new LandmarkRepository.RevisionRef(alias,1),List.of(right,left));
        while(!split.complete()) split.advance(1);
        check(loaded.find(alias).isEmpty() && loaded.find(canonical).isEmpty() && loaded.tombstones().containsKey(canonical),"split aliases resolve tombstone, not arbitrary child");
        check(loaded.lineage().get(canonical).equals(List.of(left.id(),right.id()).stream().sorted().toList()),"persisted deterministic one-to-many split lineage");
        reloadedManager.save(); LandmarkStore splitRestart=LandmarkStore.open(manager(dir,lookup));
        check(splitRestart.find(alias).isEmpty() && splitRestart.ids().size()==2 && splitRestart.lineage().equals(loaded.lineage()),"split tombstones and children actual disk restart");
        fails(IllegalStateException.class,()->loaded.stageDelete(new LandmarkRepository.RevisionRef(left.id(),1)),"stale store revision rejected");
        var delete=loaded.stageDelete(new LandmarkRepository.RevisionRef(left.id(),2)); check(delete.advance(1)==1 && loaded.find(left.id()).isEmpty(),"delete single manifest page");
        check(LandmarkStore.fromNbt(loaded.writeNbt(new NbtCompound(),lookup),lookup).writeNbt(new NbtCompound(),lookup).equals(loaded.writeNbt(new NbtCompound(),lookup)),"tombstones/lineage manifest reload");
        System.out.println("PersistentState scratch directory: "+dir);
    }
    public static void main(String[] args) throws Exception {
        roundtripAndMalformed(); pagingAliasesRestart();
        System.out.println("LandmarkPersistenceSelfTest PASS ("+checks+" explicit checks)");
    }
}
