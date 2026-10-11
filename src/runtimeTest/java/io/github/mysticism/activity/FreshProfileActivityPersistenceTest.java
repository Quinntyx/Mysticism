package io.github.mysticism.activity;

import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Activity topology must compare the store's exact fresh-profile storage references,
 * not manufacture bare geometry keys. Deterministic source masks; no inference/game boot. */
public final class FreshProfileActivityPersistenceTest {
    private static final String DIM="minecraft:overworld", BIOME="minecraft:plains";
    private static int checks;
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static PersistentStateManager manager(Path path) {
        DataFixer identity=new DataFixer() {
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) { return input; }
            @Override public Schema getSchema(int version) { throw new UnsupportedOperationException(); }
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    private static LandmarkStore open(PersistentStateManager manager,Path path) throws Exception {
        var method=LandmarkStore.class.getDeclaredMethod("open",PersistentStateManager.class,Path.class);
        method.setAccessible(true); return (LandmarkStore)method.invoke(null,manager,path);
    }
    private static Landmark landmark(BlockPoint anchor,Bounds root,List<Bounds> mask,long revision) {
        String algorithm="source-octree-v2";
        String id=LandmarkIds.seed(DIM,algorithm,Landmark.Kind.CAVE,BIOME,anchor);
        var palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of())));
        var tree=SparseOctree.<BlockSample>empty(root,1,512);
        for(Bounds cube:mask) tree=tree.with(cube,new BlockSample(BlockSample.Occupancy.AIR,0),256);
        String pageId=LandmarkIds.geometryPage(DIM,id,new BlockPoint(root.minX(),root.minY(),root.minZ()),(int)(root.maxX()-root.minX()));
        var geometry=new SourceGeometry(List.of(new GeometryPage(pageId,revision,root,palette,tree)),List.of());
        float[] vector=new float[768]; Arrays.fill(vector,1);
        return new Landmark(id,DIM,algorithm,Landmark.Kind.CAVE,BIOME,anchor,root,
                LandmarkProfiles.wrap(new Vec384f(new Vec384f(vector).norm())),.08,
                new ActivityMetadata(0,0),new Ownership(List.of()),geometry,revision,"known-air topology test observation");
    }
    private static void publish(LandmarkStore.PendingMutation mutation) {
        for(int i=0;!mutation.complete();i++) { check(i<100,"bounded core publication"); mutation.advance(1,1); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        Path directory=Files.createTempDirectory(Path.of("build"),"mysticism-native-activity-restart-");
        var manager=manager(directory); var store=open(manager,directory);
        store.clearGeneratedIfIncompatible(LandmarkProfiles.current());
        Bounds root=Bounds.cube(0,0,0,4),leftMask=Bounds.cube(0,0,0,2),rightMask=Bounds.cube(2,0,0,2);
        Landmark parent=landmark(new BlockPoint(0,0,0),root,List.of(leftMask,rightMask),0);
        Landmark left=landmark(parent.anchor(),root,List.of(leftMask),1);
        Landmark right=landmark(new BlockPoint(2,0,0),root,List.of(rightMask),1);
        publish(store.stagePut(parent,-1));
        var state=new LandmarkActivityState();
        state.publish(parent.id(),new LandmarkActivityState.Influence(parent.baseEmbedding().vector(),.2,20));
        manager.set("mysticism.landmark_activity.source-v2",state);
        var ref=new LandmarkRepository.RevisionRef(parent.id(),0);
        var plan=LandmarkActivityTopology.prepareSplit(store,state,ref,List.of(left,right),20);
        publish(store.stageSplit(ref,List.of(left,right))); plan.commit();
        check(state.entries.size()==2,"namespaced core split commits activity without bare-key mismatch");
        check(Math.abs(state.entries.values().stream().mapToDouble(v->v.level).sum()-.2)<1e-12,"native influence mass retained exactly once");
        check(state.entries.values().stream().allMatch(v->v.vector.toBits().length==768&&v.vector.toBits()[767]!=0),"native tail retained in split influence");
        check(store.cacheStats().reconstructedLeaves()==0,"activity split performs no cold geometry reads");
        List<String> lineage=List.of(left.id(),right.id()).stream().sorted().toList();
        manager.save(); manager=manager(directory); store=open(manager,directory);
        store.clearGeneratedIfIncompatible(LandmarkProfiles.current());
        state=manager.get(LandmarkActivityState.TYPE,"mysticism.landmark_activity.source-v2");
        check(state!=null&&state.entries.size()==2,"native split histories survive actual PersistentState restart");
        check(store.lineageChildren(parent.id(),2).equals(lineage),"saved source lineage retained");
        for(Landmark child:List.of(left,right)) {
            var metadata=store.metadata(child.id()).orElseThrow();
            check(metadata.geometryKeys().equals(store.geometryReferences(child.geometry())),"persisted namespace available after restart");
            check(metadata.geometryKeys().getFirst().contains(".e-"),"fresh discoveries use isolated immutable storage keys");
        }
        System.out.println("Fresh profile activity persistence: "+checks+" checks passed (disk/topology; no game/model launch)");
    }
}
