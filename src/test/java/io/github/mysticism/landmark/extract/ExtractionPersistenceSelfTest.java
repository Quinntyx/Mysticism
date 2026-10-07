package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Production graph -> real foundation streaming mutations -> compressed disk -> cold reads. */
public final class ExtractionPersistenceSelfTest {
    private static int checks;
    private static final String DIM="minecraft:overworld", BIOME="minecraft:lush_caves";
    private static final Bounds ROOT=Bounds.cube(8,-32,8,32);
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static LandmarkStore open(PersistentStateManager states,Path dir){
        // Foundation deliberately exposes world access, not its disk fixture factory, publicly.
        // Reflection here is ONLY a test fixture; production calls LandmarkStore.forWorld.
        try{var factory=LandmarkStore.class.getDeclaredMethod("open",PersistentStateManager.class,Path.class);factory.setAccessible(true);return (LandmarkStore)factory.invoke(null,states,dir);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static PersistentStateManager manager(Path dir,RegistryWrapper.WrapperLookup lookup){
        DataFixer identity=new DataFixer(){
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to){return input;}
            @Override public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(dir.toFile(),identity,lookup);
    }
    private static List<ExtractionGraph.Feature> graph(ExtractionGraph.Observation[] cells,List<Landmark> parents,long version){
        return graph(cells,parents,version,Set.of());
    }
    private static List<ExtractionGraph.Feature> graph(ExtractionGraph.Observation[] cells,List<Landmark> parents,long version,Set<String> seen){
        var seeds=parents.stream().map(p->new ExtractionGraph.Seed(p.id(),p.kind(),p.biome(),p.anchor(),p.geometry(),p.algorithmVersion())).toList();
        var g=new ExtractionGraph(DIM,ROOT,cells,seeds,version,seen);while(!g.complete())check(g.advance(256)<=256,"graph expansion budget");return g.finish();
    }
    private static Landmark value(ExtractionGraph.Feature f,long revision,Ownership owners){
        float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;
        Bounds bounds=Bounds.cube(f.anchor().x(),f.anchor().y(),f.anchor().z(),1);for(var p:f.geometry().pages())bounds=bounds.union(p.bounds());
        return new Landmark(f.id(),DIM,f.algorithmVersion(),f.kind(),f.biome(),f.anchor(),bounds,LandmarkProfiles.wrap(new Vec384f(v)),f.importance(),new ActivityMetadata(.2,10),owners,f.geometry(),revision,"actual extraction fixture");
    }
    private static void commit(LandmarkStore.PendingMutation mutation){int steps=0;while(!mutation.complete()){check(mutation.advance(1,8)<=1,"one page / eight leaves mutation budget");check(++steps<20000,"bounded staging convergence");}}
    private static Landmark read(LandmarkStore store,String id){
        var r=store.beginGeometryRead(id);List<GeometryPage> pages=new ArrayList<>();int steps=0;
        while(!r.complete()){check(r.advance(1,8)<=8,"cold geometry leaf streaming budget");var drained=r.drain();check(drained.size()<=1,"cold geometry page streaming budget");pages.addAll(drained);check(++steps<20000,"cold read convergence");}
        return LandmarkNbt.hydrate(r.metadata(),pages);
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.createGameVersion();var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());Path dir=Files.createTempDirectory("mysticism-extraction-");
        var states=manager(dir,lookup);var store=open(states,dir);
        ExtractionJournal journal=states.getOrCreate(ExtractionJournal.TYPE,"mysticism.extraction.v1");
        var entry=journal.entry(new ExtractionJournal.Region(DIM,8,-32,8));long first=journal.reserve(entry);
        var rock=new ExtractionGraph.Observation(new BlockPalette.State("minecraft:stone",Map.of()),BIOME,"minecraft:stone",false,false,100,63);
        var air=new ExtractionGraph.Observation(new BlockPalette.State("minecraft:cave_air",Map.of()),BIOME,"minecraft:air",true,false,100,63);
        var cells=new ExtractionGraph.Observation[ExtractionGraph.MAX_CELLS];Arrays.fill(cells,rock);
        cells[ExtractionGraph.index(6,10,10)]=air;cells[ExtractionGraph.index(10,10,10)]=air;
        List<Landmark> initial=new ArrayList<>();UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000007");
        for(var feature:graph(cells,List.of(),first)){Landmark l=value(feature,0,new Ownership(List.of(new Ownership.Claim(owner,4))));commit(store.stagePut(l,-1));initial.add(l);}
        check(initial.size()==2,"actual disconnected cave identities published");journal.publish(entry,initial.stream().map(Landmark::id).toList());journal.completed(entry,ObservationFingerprints.of(cells));states.save();
        var loadedStates=manager(dir,lookup);var loaded=open(loadedStates,dir);var loadedJournal=loadedStates.getOrCreate(ExtractionJournal.TYPE,"mysticism.extraction.v1");
        var loadedEntry=loadedJournal.entries().iterator().next();check(loadedEntry.ids().equals(entry.ids()) && loadedEntry.version()==first,"persisted scheduling lineage and immutable-page reservation");
        check(loadedEntry.fingerprint().equals(ObservationFingerprints.of(cells)),"unchanged snapshot fingerprint persisted; retries do not allocate duplicate immutable geometry");
        List<Landmark> parents=loadedEntry.ids().stream().map(id->read(loaded,id)).toList();
        check(parents.getFirst().baseEmbedding().profile().equals(LandmarkProfiles.current()),"correct actual model fingerprint survives disk");
        long cancelled=loadedJournal.reserve(loadedEntry),next=loadedJournal.reserve(loadedEntry);check(next==cancelled+1,"cancelled observations never reuse immutable page versions");
        var unchanged=graph(cells,parents,next);check(unchanged.stream().map(ExtractionGraph.Feature::id).sorted().toList().equals(loadedEntry.ids()),"cold geometry seeds preserve IDs and positions");
        for(int x=7;x<10;x++)cells[ExtractionGraph.index(x,10,10)]=air;
        var connected=graph(cells,parents,next).getFirst();check(connected.parents().size()==2,"current physical connectivity proof across vanilla chunk edge");
        var seeds=parents.stream().map(p->new ExtractionGraph.Seed(p.id(),p.kind(),p.biome(),p.anchor(),p.geometry(),p.algorithmVersion())).toList();
        var edited=ExtractionGraph.refreshParents(DIM,connected,seeds,next);
        Set<BlockPoint> occupied=new HashSet<>();for(var geometry:edited.values())for(var p:geometry.pages())for(var cell:p.knownCells()){
            var b=cell.bounds();for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++)check(occupied.add(new BlockPoint(x,y,z)),"refreshed parent masks partition current observations");
        }
        for(var parent:parents){var geometry=edited.get(parent.id());Bounds bounds=parent.bounds();for(var p:geometry.pages())bounds=bounds.union(p.bounds());
            var revised=new Landmark(parent.id(),DIM,parent.algorithmVersion(),parent.kind(),parent.biome(),parent.anchor(),bounds,parent.baseEmbedding(),parent.baseImportance(),parent.activity(),parent.ownership(),geometry,1,"real source edit before merge");commit(loaded.stagePut(revised,0));}
        var refs=parents.stream().map(p->new LandmarkRepository.RevisionRef(p.id(),1)).toList();
        commit(loaded.stageMerge(new LandmarkRepository.VerifiedConnectivity(refs,"production graph six-neighbour exact-biome connectivity"),20,new ImportancePolicy(1,12000,.001,.5),connected.geometry()));
        String canonical=parents.stream().map(Landmark::id).min(String::compareTo).orElseThrow();String alias=parents.stream().map(Landmark::id).filter(id->!id.equals(canonical)).findFirst().orElseThrow();
        loadedJournal.publish(loadedEntry,List.of(canonical));check(loadedEntry.fingerprint().isEmpty(),"partial topology publication invalidates prior observation fingerprint");loadedJournal.completed(loadedEntry,ObservationFingerprints.of(cells));loadedStates.save();var mergedStates=manager(dir,lookup);var mergedStore=open(mergedStates,dir);
        var merged=read(mergedStore,canonical);check(mergedStore.resolve(alias).equals(canonical),"merge alias persisted transactionally");check(!merged.ownership().claims().isEmpty(),"merge retained ownership");
        check(merged.geometry().sample(16,-22,18).occupancy()==BlockSample.Occupancy.AIR,"real revised opened wall saved/reloaded as air");
        cells[ExtractionGraph.index(8,10,10)]=rock;long splitVersion=next+1;var fragments=graph(cells,List.of(merged),splitVersion,loadedEntry.seen());
        check(fragments.size()==2,"source wall edit creates two physical masks");check(fragments.stream().noneMatch(f->f.id().equals(alias)),"split does not resurrect retired merge alias");List<Landmark> children=fragments.stream().map(f->value(f,merged.revision()+1,merged.ownership())).toList();
        commit(mergedStore.stageSplit(new LandmarkRepository.RevisionRef(canonical,merged.revision()),children));mergedStates.save();var splitStore=open(manager(dir,lookup),dir);
        check(splitStore.lineage().get(canonical).equals(children.stream().map(Landmark::id).sorted().toList()),"actual persistent split lineage");
        for(var child:children){Landmark restored=read(splitStore,child.id());check(restored.anchor().equals(child.anchor()),"split source seed reload");var sample=restored.geometry().sample(16,-22,18);check(sample==null || sample.occupancy()!=BlockSample.Occupancy.AIR,"split geometry reload rejects removed air");}
        // Public journal caps are enforced before a malformed NBT list can grow work unboundedly.
        ExtractionJournal bounded=new ExtractionJournal();for(int i=0;i<ExtractionJournal.MAX_REGIONS;i++)check(bounded.entry(new ExtractionJournal.Region(DIM,8+i*32,-32,8))!=null,"bounded region acceptance");
        check(bounded.entry(new ExtractionJournal.Region(DIM,-24,-32,8))==null,"explicit persistence cap, no unbounded scheduler growth");
        NbtCompound encoded=loadedJournal.writeNbt(new NbtCompound(),lookup);check(ExtractionJournal.fromNbt(encoded,lookup).writeNbt(new NbtCompound(),lookup).equals(encoded),"journal deterministic NBT roundtrip");
        System.out.println("ExtractionPersistenceSelfTest: "+checks+" checks passed; scratch "+dir);
    }
}
