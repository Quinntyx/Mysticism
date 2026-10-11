package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Contiguous render-distance survey debt, using actual persisted parents, production
 * local preparation/patch publication, cold reload and exact source ownership. No game
 * or inference is bootstrapped; the explicit fixture embedding is only store test data. */
public final class GenerationSurveyContiguousGrowthTest {
    private static final String DIM="minecraft:overworld",BIOME="minecraft:plains";
    private static final BlockPalette.State STONE=new BlockPalette.State("minecraft:stone",Map.of());
    private static final BlockPalette.State AIR=new BlockPalette.State("minecraft:air",Map.of());
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    private static LandmarkEmbedding fixtureEmbedding(){
        float[] unit=new float[EmbeddingSpace.DIMENSIONS];unit[0]=1;
        return LandmarkProfiles.wrap(new Vec384f(unit));
    }
    private static Bounds observation(BlockPos pos){
        return new Bounds(pos.getX()-12L,pos.getY()-12L,pos.getZ()-12L,pos.getX()+13L,pos.getY()+13L,pos.getZ()+13L);
    }
    private static SourceLandmarks.Region flatTerrain(BlockPos pos){
        Bounds b=observation(pos);var cells=new ArrayList<SourceLandmarks.Cell>();
        for(long x=b.minX();x<b.maxX();x++)for(long y=b.minY();y<b.maxY();y++)for(long z=b.minZ();z<b.maxZ();z++)
            cells.add(new SourceLandmarks.Cell(new BlockPoint(x,y,z),y<=64?STONE:AIR,BIOME,y>64));
        return new SourceLandmarks.Region(DIM,b,cells,true);
    }
    private static List<GeometryPage> read(LandmarkStore store,String id,Bounds range){
        var read=store.beginGeometryRead(id,range);var pages=new ArrayList<GeometryPage>();int steps=0;
        while(!read.complete()){
            read.advance(1,512);pages.addAll(read.drain());
            check(++steps<20000,"bounded geometry traversal drains even beyond 512 total refs");
        }
        return List.copyOf(pages);
    }
    private static List<Landmark> localParents(LandmarkStore store,Bounds observed){
        Bounds window=SourceLandmarks.ownershipWindow(observed);String cursor=null;
        var parents=new ArrayList<Landmark>();boolean end=false;
        while(!end){
            var page=store.sourceRangePage(DIM,window,cursor,4,4);cursor=page.nextId();end=page.end();
            for(var metadata:page.landmarks()){
                var pages=read(store,metadata.id(),window);
                check(pages.size()<=125,"only whole local 8-block pages are decoded, not the growing landmark");
                parents.add(SourceLandmarks.localParent(metadata,pages));
            }
        }
        return parents;
    }
    private static void commit(LandmarkStore.PendingMutation mutation){
        int steps=0;
        while(!mutation.complete()){mutation.advance(1,512);check(++steps<1000,"local page validation/publication drains");}
    }
    private static Landmark publish(LandmarkStore store,SourceLandmarks.Prepared p){
        var old=p.prior();long revision=old==null?0:old.revision()+1;
        Bounds bounds=OwnershipGeometry.bounds(p.anchor(),p.geometry());if(old!=null)bounds=bounds.union(old.bounds());
        var value=new Landmark(p.id(),DIM,SourceLandmarks.ALGORITHM,p.kind(),p.biome(),p.anchor(),bounds,fixtureEmbedding(),.08,
            new ActivityMetadata(0,0),new Ownership(List.of()),p.geometry(),revision,"contiguous generation regression");
        var mutation=old==null?store.stagePut(value,-1):store.stageSourcePatch(value,old.revision(),
            old.geometry().pages().stream().map(g->"mysticism.landmark.geometry."+g.id()+"."+g.revision()).toList());
        commit(mutation);return value;
    }
    private static SourceOwnership.Region owners(LandmarkStore store,Bounds bounds){
        var future=new CompletableFuture<SourceOwnership.Region>();var request=new SourceOwnership.Request(store,DIM,bounds,future);int steps=0;
        while(!request.done){request.advance();check(++steps<20000,"exact ownership lookup converges on a large landmark");}
        return future.join();
    }
    private static long volume(Bounds b){return (b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ());}
    private static void contiguousRenderDistance()throws Exception{
        Path path=Files.createTempDirectory("generation-contiguous-");var states=manager(path);var store=LandmarkStore.open(states,path);
        store.clearGeneratedIfIncompatible(LandmarkProfiles.current());
        var lifecycle=new GenerationSurveyLifecycle<Object>();Object server=new Object();
        var columns=new GenerationSurvey.ColumnView(){
            public int height(int x,int z){return 64;}
            public String biome(int x,int z,int y){return BIOME;}
        };
        // Radius four: 9x9 ADJACENT generated chunks, not distant disjoint windows.
        for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)lifecycle.loaded(server,DIM,x,z,-64,320,columns);
        var work=lifecycle.started(server);check(work.pending()==81,"one retained flat-terrain probe per adjacent chunk");
        check(work.next(false).isEmpty(),"unready embeddings do not fake generation publication");
        var positions=new ArrayList<BlockPos>();String canonical=null;int attempts=0;boolean restarted=false;
        Set<String> untouched=Set.of();
        while(work.pending()>0){
            var attempt=work.next(true).orElseThrow();var pos=attempt.position();var region=flatTerrain(pos);
            var parents=localParents(store,region.bounds());
            if(canonical!=null){
                check(parents.size()==1,"same-biome contiguous growth retains one canonical source identity");
                var oldKeys=new HashSet<>(store.metadata(canonical).orElseThrow().geometryKeys());
                for(var p:parents.getFirst().geometry().pages())oldKeys.remove("mysticism.landmark.geometry."+p.id()+"."+p.revision());
                untouched=Set.copyOf(oldKeys);
            }
            var prepared=SourceLandmarks.prepareLocal(region,parents,pos,null,false,0,63,Map.of());
            check(prepared!=null,"a contiguous generation window must prepare, not permanently fail/requeue");
            var value=publish(store,prepared);
            if(canonical==null)canonical=value.id();check(canonical.equals(value.id()),"source seed identity survives octree growth");
            check(store.metadata(canonical).orElseThrow().geometryKeys().containsAll(untouched),"unread remote page versions survive publication verbatim");
            var actual=owners(store,new Bounds(pos.getX(),64,pos.getZ(),pos.getX()+1L,67,pos.getZ()+1L));
            boolean published=actual.ownerAt(pos).orElse("").equals(canonical);
            check(published&&actual.ownerAt(pos.down()).orElseThrow().equals(canonical),"retire a probe only after actual persisted air/floor ownership commits");
            work.completed(attempt,published);positions.add(pos);check(++attempts<=81,"no probe permanently fails at old cell/page budgets");
            // Cold parents are read by later surveys after the old 131072-cell ceiling.
            if(attempts==45){
                states.save();states=manager(path);store=LandmarkStore.open(states,path);restarted=true;
                check(store.usesNativeSourceProfile(LandmarkProfiles.current()),"native v2 store survives the mid-growth restart");
            }
        }
        check(restarted&&attempts==81&&work.chunks()==0,"all contiguous generation debt drains with published ownership");
        var metadata=store.metadata(canonical).orElseThrow();
        check(metadata.geometryKeys().size()>512,"fixture crosses the old full-parent 512-page ceiling");
        states.save();var cold=LandmarkStore.open(manager(path),path);var pages=read(cold,canonical,null);
        long owned=pages.stream().flatMap(p->p.knownCells().stream()).mapToLong(c->volume(c.bounds())).sum();
        check(owned>OwnershipGeometry.CELL_BUDGET,"fixture crosses the old 131072 expanded-cell ceiling: "+owned);
        check(owned==153L*153*18,"all overlapping survey windows persist their exact ownership union without truncation: "+owned);
        check(cold.ids().equals(List.of(canonical)),"no chunk-shaped identities or duplicate competing ownership are introduced");
        for(var pos:positions){
            var actual=owners(cold,new Bounds(pos.getX(),59,pos.getZ(),pos.getX()+1L,79,pos.getZ()+1L));
            for(int y=60;y<78;y++)check(actual.ownerAt(new BlockPos(pos.getX(),y,pos.getZ())).orElseThrow().equals(canonical),"earlier and later source masks survive cold growth");
            check(actual.ownerAt(new BlockPos(pos.getX(),59,pos.getZ())).isEmpty(),"unknown below observed surface-band remains unowned");
            check(actual.ownerAt(new BlockPos(pos.getX(),78,pos.getZ())).isEmpty(),"unknown above the observation remains unowned");
        }
        System.out.println("Contiguous generation persisted fixture: "+path+" ("+owned+" cells, "+pages.size()+" pages)");
    }
    private static void patchRejectsOwnershipLoss()throws Exception{
        Path path=Files.createTempDirectory("generation-patch-");var store=LandmarkStore.open(manager(path),path);
        store.clearGeneratedIfIncompatible(LandmarkProfiles.current());var pos=new BlockPos(7,65,7);var region=flatTerrain(pos);
        var first=publish(store,SourceLandmarks.prepareLocal(region,List.of(),pos,null,false,0,63,Map.of()));
        var prior=localParents(store,region.bounds()).getFirst();
        // Drop a whole known page while retaining a plausible bounding box. This must not
        // silently publish partial ownership or satisfy a generation probe.
        var truncated=new SourceGeometry(prior.geometry().pages().subList(1,prior.geometry().pages().size()),List.of());
        var bad=new Landmark(first.id(),DIM,SourceLandmarks.ALGORITHM,first.kind(),BIOME,first.anchor(),first.bounds(),fixtureEmbedding(),.08,
            first.activity(),first.ownership(),truncated,1,"invalid partial source patch");
        var keys=prior.geometry().pages().stream().map(p->"mysticism.landmark.geometry."+p.id()+"."+p.revision()).toList();
        var mutation=store.stageSourcePatch(bad,0,keys);
        try{commit(mutation);throw new AssertionError("source patch discarded old known ownership");}
        catch(IllegalArgumentException expected){checks++;mutation.cancel();}
        check(store.metadata(first.id()).orElseThrow().revision()==0,"invalid patch does not publish a header or revision");
        try{store.stageSourcePatch(bad,1,keys);throw new AssertionError("stale source patch accepted");}
        catch(IllegalStateException expected){checks++;}
        try{store.stageSourcePatch(bad,0,List.of());throw new AssertionError("unread source page overwrite accepted");}
        catch(IllegalArgumentException expected){checks++;}
        check(owners(store,new Bounds(7,64,7,8,67,8)).ownerAt(pos).orElseThrow().equals(first.id()),"failed patch preserves prior exact source ownership");
        // Ownership conservation must not freeze actual source materials. A later source
        // observation may turn the old floor into air; its source-coordinate owner remains.
        var changedCells=region.cells().stream().map(c->c.position().equals(new BlockPoint(7,64,7))
            ?new SourceLandmarks.Cell(c.position(),AIR,BIOME,true):c).toList();
        var changed=new SourceLandmarks.Region(DIM,region.bounds(),changedCells,true);
        var refinement=SourceLandmarks.prepareLocal(changed,localParents(store,changed.bounds()),pos,null,false,1,63,Map.of());
        check(refinement!=null,"a source edit can refine an existing local mask");
        var refined=publish(store,refinement);
        check(refined.revision()==1&&refined.geometry().sample(7,64,7).occupancy()==BlockSample.Occupancy.AIR,
            "ownership-preserving patch publishes observed air instead of retaining the stale solid floor");
        var saved=read(store,first.id(),SourceLandmarks.ownershipWindow(region.bounds()));
        check(saved.stream().filter(p->p.bounds().contains(7,64,7)).anyMatch(p->p.cells().sample(7,64,7).occupancy()==BlockSample.Occupancy.AIR),
            "actual persisted geometry contains the material refinement");
        check(owners(store,new Bounds(7,64,7,8,67,8)).ownerAt(pos.down()).orElseThrow().equals(first.id()),
            "material changes do not drop exact source ownership");
    }
    private static void localConnectivityAndCompetingOwners()throws Exception{
        Path path=Files.createTempDirectory("generation-connectivity-");var store=LandmarkStore.open(manager(path),path);
        store.clearGeneratedIfIncompatible(LandmarkProfiles.current());
        // Globally connected U-shaped source mask: the bridge at x=0 is outside the
        // x=56..88 local read. A local component count must not reject valid growth.
        var mask=new HashMap<BlockPoint,OwnershipGeometry.Material>();
        for(int x=0;x<81;x++)for(int z:new int[]{0,7})mask.put(new BlockPoint(x,64,z),new OwnershipGeometry.Material(STONE,false));
        for(int z=0;z<8;z++)mask.put(new BlockPoint(0,64,z),new OwnershipGeometry.Material(STONE,false));
        var anchor=new BlockPoint(0,64,0);String id=LandmarkIds.seed(DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,BIOME,anchor);
        var geometry=OwnershipGeometry.geometry(DIM,id,0,mask,List.of(),1);
        var first=new Landmark(id,DIM,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,BIOME,anchor,OwnershipGeometry.bounds(anchor,geometry),fixtureEmbedding(),.08,
            new ActivityMetadata(0,0),new Ownership(List.of()),geometry,0,"connected U fixture");
        commit(store.stagePut(first,-1));
        var pos=new BlockPos(70,65,3);var region=flatTerrain(pos);var parents=localParents(store,region.bounds());
        var localMask=OwnershipGeometry.expand(parents.getFirst().geometry());
        check(OwnershipGeometry.components(localMask.keySet()).size()==2,"persisted globally connected parent has two local arms");
        var growth=SourceLandmarks.prepareLocal(region,parents,pos,null,false,0,63,Map.of());
        check(growth!=null&&growth.prior().id().equals(id),"local disconnected arms do not block a connected flood joining the actual parent");
        publish(store,growth);
        check(owners(store,new Bounds(0,64,0,1,65,8)).owners().size()==8,"remote connecting bridge remains exactly owned");
        var islandPos=new BlockPos(140,65,3);var island=flatTerrain(islandPos);
        var header=store.metadata(id).orElseThrow();
        check(SourceLandmarks.prepareLocal(island,List.of(SourceLandmarks.localParent(header,List.of())),islandPos,id,true,0,63,Map.of())==null,
            "preferred activity cannot turn a distant unowned island into the parent's source mask");
        // Adjacent observation with a new biome keeps its own exact cells. Intersecting
        // existing source masks must be excluded, not stolen by the same page's AABB.
        var otherPos=new BlockPos(94,65,3);var other=flatTerrain(otherPos);
        var desertCells=other.cells().stream().map(c->new SourceLandmarks.Cell(c.position(),c.material(),"minecraft:desert",c.sky())).toList();
        other=new SourceLandmarks.Region(DIM,other.bounds(),desertCells,true);
        var competing=SourceLandmarks.prepareLocal(other,localParents(store,other.bounds()),otherPos,null,false,0,63,Map.of());
        check(competing!=null&&competing.prior()==null,"different actual biome starts a separate source seed");
        var second=publish(store,competing);
        var ownership=owners(store,new Bounds(81,64,3,95,66,4));
        check(ownership.ownerAt(new BlockPos(82,65,3)).orElseThrow().equals(id),"new biome observation cannot steal old exact air ownership");
        check(ownership.ownerAt(otherPos).orElseThrow().equals(second.id()),"new biome probe gets its exclusive source identity");
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.createGameVersion();patchRejectsOwnershipLoss();localConnectivityAndCompetingOwners();contiguousRenderDistance();
        System.out.println("GenerationSurveyContiguousGrowthTest passed: "+checks+" checks");
    }
}
