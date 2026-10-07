package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Real core split/delete/alias transactions, immutable masks, production adapter and disk codecs. */
public final class ActivityTopologyTest {
    private static int checks;
    private static final UUID A=new UUID(0,1),B=new UUID(0,2),C=new UUID(0,3);
    private static final String DIM="minecraft:overworld",ALGORITHM="topology-test",BIOME="minecraft:plains";
    private static final BlockPalette AIR=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of())));
    private static final BlockSample AIR_SAMPLE=new BlockSample(BlockSample.Occupancy.AIR,0);
    private static final Ownership EMPTY=new Ownership(List.of());
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static void close(double a,double b,String name){check(Math.abs(a-b)<1e-12,name);}
    private static void fails(Runnable action,String name){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(name);}
    private static Vec384f vector(double y){float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;v[1]=(float)y;return new Vec384f(new Vec384f(v).norm());}
    private static LandmarkEmbedding embedding(){var p=io.github.mysticism.embedding.EmbeddingProfile.current();return new LandmarkEmbedding(new EmbeddingProfile(p.model(),p.revision(),"model-manifest:"+p.revision(),p.semantics(),p.dimensions(),EmbeddingProfile.Normalization.UNIT,p.fingerprint()),vector(0));}
    private static Ownership owners(UUID... players){return new Ownership(Arrays.stream(players).map(p->new Ownership.Claim(p,100)).toList());}
    private static Landmark landmark(BlockPoint anchor,Bounds bounds,SourceGeometry geometry,Ownership ownership,long revision){
        return new Landmark(LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor),DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor,bounds,embedding(),.9,new ActivityMetadata(0,0),ownership,geometry,revision,"observed disjoint fixture masks");
    }
    private static SourceGeometry geometry(String seed,Bounds root,List<Bounds> cubes,long revision){
        var tree=SparseOctree.<BlockSample>empty(root,1,512);for(var cube:cubes)tree=tree.with(cube,AIR_SAMPLE,4096);
        return new SourceGeometry(List.of(new GeometryPage(LandmarkIds.geometryPage(DIM,seed,new BlockPoint(root.minX(),root.minY(),root.minZ()),(int)(root.maxX()-root.minX())),revision,root,AIR,tree)),List.of());
    }
    private static List<Bounds> half(int x){List<Bounds> cubes=new ArrayList<>();for(int y=0;y<8;y+=4)for(int z=0;z<8;z+=4)cubes.add(Bounds.cube(x,y,z,4));return cubes;}
    private static Landmark geometry(Landmark old,SourceGeometry replacement){return new Landmark(old.id(),old.dimension(),old.algorithmVersion(),old.kind(),old.biome(),old.anchor(),old.bounds(),old.baseEmbedding(),old.baseImportance(),old.activity(),old.ownership(),replacement,old.revision(),old.provenance());}
    private static Landmark ownership(Landmark old,Ownership replacement){return new Landmark(old.id(),old.dimension(),old.algorithmVersion(),old.kind(),old.biome(),old.anchor(),old.bounds(),old.baseEmbedding(),old.baseImportance(),old.activity(),replacement,old.geometry(),old.revision(),old.provenance());}
    private static PersistentStateManager manager(Path path){DataFixer identity=new DataFixer(){
        @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
        @Override public Schema getSchema(int version){throw new UnsupportedOperationException();}
    };return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));}
    private static LandmarkStore open(PersistentStateManager manager,Path path)throws Exception{
        var factory=LandmarkStore.class.getDeclaredMethod("open",PersistentStateManager.class,Path.class);factory.setAccessible(true);return (LandmarkStore)factory.invoke(null,manager,path);
    }
    private static void publish(LandmarkStore.PendingMutation mutation){while(!mutation.complete())mutation.advance(1,256);}
    private static LandmarkRepository.RevisionRef ref(Landmark value){return new LandmarkRepository.RevisionRef(value.id(),value.revision());}
    private record Fixture(Path path,PersistentStateManager manager,LandmarkStore store,LandmarkActivityState state,Landmark parent,List<Landmark> children,LandmarkActivityState.Influence history){}
    private static Fixture fixture(boolean sameOwner,boolean unlocated)throws Exception{
        Bounds root=Bounds.cube(0,0,0,8);var anchor=new BlockPoint(0,0,0);String seed=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor);
        Ownership parentOwners=unlocated?owners(A,B,C):sameOwner?owners(A):owners(A,B);
        var parent=landmark(anchor,root,geometry(seed,root,List.of(root),0),parentOwners,0);
        var leftAnchor=anchor;var rightAnchor=new BlockPoint(4,0,0);String rightId=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,rightAnchor);
        var left=landmark(leftAnchor,root,geometry(seed,root,half(0),1),unlocated?owners(A,C):owners(A),1);
        var right=landmark(rightAnchor,root,geometry(rightId,root,half(4),1),sameOwner?owners(A):owners(B),1);
        var history=new LandmarkActivityState.Influence(vector(.2),.8,100);history.mergedBase=.3;
        history.claims=SparseOctree.<String>empty(root,1,512);
        if(sameOwner)history.claims=history.claims.with(root,A.toString(),256);
        else history.claims=history.claims.with(Bounds.cube(1,1,1,1),A.toString(),256).with(Bounds.cube(5,1,1,1),B.toString(),256);
        parentOwners.claims().forEach(c->history.owners.add(c.player().toString()));
        Path path=Files.createTempDirectory("activity-topology-");var pm=manager(path);var store=open(pm,path);publish(store.stagePut(parent,-1));var state=new LandmarkActivityState();state.publish(parent.id(),history);state.setDirty(false);
        return new Fixture(path,pm,store,state,parent,List.of(left,right),history);
    }
    private static LandmarkActivityTopology.Plan split(Fixture f){return LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),f.children,24100);}
    private static void unchanged(Fixture f,NbtCompound before,String name){check(before.equals(f.state.writeNbt(new NbtCompound(),null))&&!f.state.isDirty(),name);}
    private static void fill(Fixture f,int count,UUID owner){for(int i=0;i<count;i++){
        var history=new LandmarkActivityState.Influence(vector(0),0,0);if(owner!=null)history.owners.add(owner.toString());
        f.state.publish(LandmarkIds.seed(DIM,"quota-fixture",Landmark.Kind.CAVE,BIOME,new BlockPoint(1000+i,0,0)),history);
    }f.state.setDirty(false);}
    private static void conservationAndReload()throws Exception{
        var f=fixture(false,true);var before=f.state.writeNbt(new NbtCompound(),null);var cache=f.store.cacheStats();
        var cloned=f.children.stream().map(c->ownership(c,f.parent.ownership())).toList();
        var corrected=LandmarkActivityTopology.partitionSplitOwnership(f.store,f.state,ref(f.parent),cloned,24100);
        check(corrected.equals(f.children.stream().sorted(Comparator.comparing(Landmark::id)).toList()),"real extractor bridge repairs cloned child owners without changing source geometry/IDs/revisions");
        var reversed=LandmarkActivityTopology.partitionSplitOwnership(f.store,f.state,ref(f.parent),List.of(cloned.get(1),cloned.get(0)),24100);
        check(reversed.equals(corrected),"ownership partition deterministic for arbitrary child input order");
        unchanged(f,before,"ownership bridge has no persistence effects");var plan=LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),corrected,24100);
        unchanged(f,before,"preparation has no persistence effect");check(cache.equals(f.store.cacheStats()),"prepare never hydrates parent repository geometry");
        fails(plan::commit,"commit before core split rejected");unchanged(f,before,"early rejection is atomic");
        var mutation=f.store.stageSplit(ref(f.parent),corrected);mutation.advance(1,256);fails(plan::commit,"partial core publication rejected");unchanged(f,before,"partial rejection leaves all history");publish(mutation);plan.commit();
        check(f.state.entries.size()==2&&f.state.isDirty(),"core-completed split publishes exact two histories");double bases=0,levels=0;
        for(var child:f.children){var value=f.state.entries.get(child.id());bases+=value.mergedBase;levels+=value.level;
            close(value.mergedBase,.15,"share may be below child natural .9 importance");close(value.level,.2,"decayed activity distributed once");
            check(value.vector.squareDistance(f.history.vector)==0&&value.vector!=f.history.vector,"direction preserved with defensive vector copy");
            check(value.tick==24100,"decay time carried into each share");check(value.claims.cells(256).size()==1,"one actual claim per disjoint child, not bbox replication");
            check(f.store.metadata(child.id()).orElseThrow().header().anchor().equals(child.anchor()),"stable source anchor survives split");
        }
        close(bases,.3,"total merged base mass conserved");close(levels,.4,"total decayed activity mass conserved");
        var left=f.state.entries.get(f.parent.id());var right=f.state.entries.get(f.children.get(1).id());
        check(left.claims.sample(1,1,1).equals(A.toString())&&left.claims.sample(5,1,1)==null,"overlapping child bounds do not assign foreign spatial claim");
        check(right.claims.sample(5,1,1).equals(B.toString())&&right.claims.sample(1,1,1)==null,"right occupancy membership, not bounding box membership");
        check(left.owners.contains(C.toString())&&!right.owners.contains(C.toString())&&f.state.claimedBy(C)==1,"unlocated owner history retained once on original seed, no invented cell");
        check(f.history.claims.sample(1,1,1).equals(A.toString())&&f.history.claims.sample(5,1,1).equals(B.toString()),"captured immutable spatial tree not modified");
        f.state.setDirty(false);var once=f.state.writeNbt(new NbtCompound(),null);plan.commit();plan.cancel();plan.commit();unchanged(f,once,"successful commit idempotent; cancel after commit is not rollback");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{plan.commit();return false;}catch(IllegalStateException expected){return true;}}).get(5,java.util.concurrent.TimeUnit.SECONDS),"even terminal commit is thread confined");
        f.manager.save();Path activity=f.path.resolve("activity.dat");NbtIo.writeCompressed(once,activity);var loaded=LandmarkActivityState.read(NbtIo.readCompressed(activity,NbtSizeTracker.ofUnlimitedBytes()),null);
        var restoredManager=manager(f.path);var restored=open(restoredManager,f.path);for(var child:f.children){var value=loaded.entries.get(child.id());close(value.mergedBase,.15,"zero/nonnegative base override codec retained");check(value.claims.equals(f.state.entries.get(child.id()).claims),"absolute claim positions survive compressed reload");check(restored.metadata(child.id()).orElseThrow().revision()==1,"real split records survive cold disk reload");}
        check(loaded.claimedBy(A)==1&&loaded.claimedBy(B)==1&&loaded.claimedBy(C)==1,"split quotas survive reload");
        var remove=LandmarkActivityTopology.prepareRemove(restored,loaded,f.children.stream().map(ActivityTopologyTest::ref).toList());fails(remove::commit,"remove before core deletion rejected");publish(restored.stageDelete(ref(f.children.get(0))));fails(remove::commit,"partial multi-parent deletion does not prune any overlay");check(loaded.entries.size()==2,"partial delete retains both histories");publish(restored.stageDelete(ref(f.children.get(1))));remove.commit();remove.commit();
        check(loaded.entries.isEmpty()&&loaded.claimedBy(A)==0&&loaded.claimedBy(B)==0&&loaded.claimedBy(C)==0,"actual remove prunes entries and all quota accounting");
        restoredManager.save();NbtIo.writeCompressed(loaded.writeNbt(new NbtCompound(),null),activity);var removedReload=LandmarkActivityState.read(NbtIo.readCompressed(activity,NbtSizeTracker.ofUnlimitedBytes()),null);var removedCore=open(manager(f.path),f.path);
        check(removedReload.entries.isEmpty()&&removedReload.claimedBy(A)==0,"removed history/quota stays removed after compressed reload");
        for(var child:f.children)check(removedCore.metadata(child.id()).isEmpty()&&removedCore.tombstoneRevision(child.id()).orElseThrow()==2,"real delete retirement revision survives cold reload");
        System.out.println("Topology compressed/core fixtures retained at "+f.path);
    }
    private static void cancellationStaleAndBudgets()throws Exception{
        var f=fixture(false,false);var before=f.state.writeNbt(new NbtCompound(),null);var cancelled=split(f);cancelled.cancel();cancelled.cancel();fails(cancelled::commit,"cancelled split cannot publish");unchanged(f,before,"cancellation has no persistence effects");
        var removeCancelled=LandmarkActivityTopology.prepareRemove(f.store,f.state,List.of(ref(f.parent)));removeCancelled.cancel();fails(removeCancelled::commit,"cancelled remove cannot prune");unchanged(f,before,"remove cancellation changes no counts");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{split(f);return false;}catch(IllegalStateException expected){return true;}}).get(5,java.util.concurrent.TimeUnit.SECONDS),"real store rejects off-thread preparation");
        fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,new LandmarkRepository.RevisionRef(f.parent.id(),1),f.children,24100),"stale parent revision rejected before staging");
        fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),List.of(f.children.get(0),f.children.get(0)),24100),"duplicate children rejected");
        fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),f.children,99),"time reversal rejected");
        fails(()->LandmarkActivityTopology.prepareRemove(f.store,f.state,List.of(ref(f.parent),ref(f.parent))),"duplicate removal refs rejected");
        fails(()->LandmarkActivityTopology.prepareRemove(f.store,f.state,Collections.nCopies(9,ref(f.parent))),"remove parent budget bounded");
        var wrong=f.children.stream().map(c->ownership(c,f.parent.ownership())).toList();fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),wrong,24100),"cloned core ownership rejected before core staging");
        var overlap=geometry(f.children.get(1),geometry(f.children.get(1).id(),f.parent.bounds(),half(0),1));fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),List.of(f.children.get(0),overlap),24100),"actual cross-child occupied overlap rejected");
        var empty=geometry(f.children.get(1),new SourceGeometry(List.of(),List.of()));fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),List.of(f.children.get(0),empty),24100),"unknown/empty geometry cannot be replaced by bbox proof");
        var stale=split(f);publish(f.store.stageSplit(ref(f.parent),f.children));f.history.level+=.01;var edited=f.state.writeNbt(new NbtCompound(),null);fails(stale::commit,"in-place captured overlay edit detected");unchanged(f,edited,"stale overlay commit has no side effects");
        var replacementFixture=fixture(false,false);var replaced=split(replacementFixture);publish(replacementFixture.store.stageSplit(ref(replacementFixture.parent),replacementFixture.children));var replacement=new LandmarkActivityState.Influence(replacementFixture.history.vector,.8,100);replacement.mergedBase=.3;replacement.claims=replacementFixture.history.claims;replacement.owners.addAll(replacementFixture.history.owners);replacementFixture.state.publish(replacementFixture.parent.id(),replacement);replacementFixture.state.setDirty(false);edited=replacementFixture.state.writeNbt(new NbtCompound(),null);fails(replaced::commit,"replaced overlay detected even if content identical");unchanged(replacementFixture,edited,"replacement failure atomic");
        var capacity=fixture(false,false);fill(capacity,511,null);before=capacity.state.writeNbt(new NbtCompound(),null);fails(()->split(capacity),"entry growth rejected before core staging");unchanged(capacity,before,"early capacity failure preserves history");
        var late=fixture(false,false);var latePlan=split(late);fill(late,511,null);publish(late.store.stageSplit(ref(late.parent),late.children));before=late.state.writeNbt(new NbtCompound(),null);fails(latePlan::commit,"commit rechecks unrelated capacity changes");unchanged(late,before,"late capacity rejection preserves every record");
        var quota=fixture(true,false);fill(quota,7,A);before=quota.state.writeNbt(new NbtCompound(),null);fails(()->split(quota),"genuine spatial claim across two children cannot exceed eight player landmarks");unchanged(quota,before,"quota rejection does not truncate owner history");
        var gap=fixture(true,false);var sparseLeft=geometry(gap.children.get(0),geometry(gap.parent.id(),gap.parent.bounds(),List.of(Bounds.cube(0,0,0,4)),1));fails(()->LandmarkActivityTopology.prepareSplit(gap.store,gap.state,ref(gap.parent),List.of(sparseLeft,gap.children.get(1)),24100),"claim over removed/unknown source cells is unrepresentable, not silently erased");
        var small=fixture(true,false);var p=LandmarkActivityTopology.prepareSplit(small.store,small.state,ref(small.parent),small.children,24100);publish(small.store.stageSplit(ref(small.parent),small.children));p.commit();check(small.state.claimedBy(A)==2,"same owner legitimately spans two actual child geometries");
        long volume=0;for(var value:small.state.entries.values())for(var cell:value.claims.cells(256)){var b=cell.bounds();volume+=(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ());}check(volume==512,"coarse spatial claim partition conserves full covered volume without double allocation");
        var revised=fixture(false,false);var revPlan=split(revised);publish(revised.store.stageSplit(ref(revised.parent),revised.children));var child=revised.children.get(0);publish(revised.store.stageActivity(ref(child),new ActivityMetadata(.1,24100),child.ownership()));before=revised.state.writeNbt(new NbtCompound(),null);fails(revPlan::commit,"post-split child revision edit rejected");unchanged(revised,before,"revision failure does not publish stale split history");
        var geo=fixture(false,false);var geoPlan=split(geo);var old=geo.children.get(0);var changedPage=geometry(old,geometry(old.id(),old.bounds(),half(0),2));publish(geo.store.stageSplit(ref(geo.parent),List.of(changedPage,geo.children.get(1))));before=geo.state.writeNbt(new NbtCompound(),null);fails(geoPlan::commit,"same-header split with different immutable geometry revision rejected");unchanged(geo,before,"exact source geometry guard precedes overlay mutation");
        var retiredChild=fixture(false,false);var gone=retiredChild.children.get(1);publish(retiredChild.store.stagePut(gone,-1));publish(retiredChild.store.stageDelete(ref(gone)));fails(()->split(retiredChild),"retired child seed rejected BEFORE staging, not reused as a new object");
        var tomb=fixture(false,false);var tombPlan=LandmarkActivityTopology.prepareRemove(tomb.store,tomb.state,List.of(ref(tomb.parent)));publish(tomb.store.stageActivity(ref(tomb.parent),new ActivityMetadata(.1,100),tomb.parent.ownership()));publish(tomb.store.stageDelete(new LandmarkRepository.RevisionRef(tomb.parent.id(),1)));before=tomb.state.writeNbt(new NbtCompound(),null);fails(tombPlan::commit,"removal requires EXACT old+1 retirement revision, not merely absence");unchanged(tomb,before,"wrong retirement does not prune captured history");
    }
    private static void geometryAndProfileBudgets()throws Exception{
        var f=fixture(false,false);List<GeometryPage> pages=new ArrayList<>();for(int i=0;i<65;i++){
            var root=Bounds.cube(i%4,(i/4)%8,i/32,1);var seed=f.children.get(0).id();pages.addAll(geometry(seed,root,List.of(root),1).pages());
        }
        var excessive=geometry(f.children.get(0),new SourceGeometry(pages,List.of()));fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),List.of(excessive,f.children.get(1)),24100),"page budget rejected before hydration");
        Bounds root=Bounds.cube(0,0,0,32);var anchor=new BlockPoint(0,0,0);String id=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor);var parent=landmark(anchor,root,geometry(id,root,List.of(root),0),EMPTY,0);
        var palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));Bounds pageRoot=Bounds.cube(0,0,0,16);var checker=SparseOctree.<BlockSample>empty(pageRoot,1,32);
        for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++){int index=(x+y+z)&1;checker=checker.with(Bounds.cube(x,y,z,1),new BlockSample(index==0?BlockSample.Occupancy.AIR:BlockSample.Occupancy.SOLID,index),256);}
        var checkerGeometry=new SourceGeometry(List.of(new GeometryPage(LandmarkIds.geometryPage(DIM,id,anchor,16),1,pageRoot,palette,checker)),List.of());
        var left=landmark(anchor,root,checkerGeometry,EMPTY,1);var rightAnchor=new BlockPoint(16,0,0);String rightId=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,rightAnchor);var right=landmark(rightAnchor,root,geometry(rightId,Bounds.cube(16,0,0,16),List.of(Bounds.cube(16,0,0,16)),1),EMPTY,1);
        Path path=Files.createTempDirectory("activity-topology-budget-");var store=open(manager(path),path);publish(store.stagePut(parent,-1));var state=new LandmarkActivityState();fails(()->LandmarkActivityTopology.prepareSplit(store,state,ref(parent),List.of(left,right),1),"known-leaf budget rejects >2048 without full repo hydration");check(store.cacheStats().reconstructedLeaves()==0,"all source masks came from supplied immutable children");
        var good=f.children.get(1);var old=good.baseEmbedding().profile();var foreign=new LandmarkEmbedding(new EmbeddingProfile(old.model(),old.revision(),old.tokenizer(),old.prefixPolicy(),old.dimensions(),old.normalization(),"foreign-fingerprint"),good.baseEmbedding().vector());
        var incompatible=new Landmark(good.id(),good.dimension(),good.algorithmVersion(),good.kind(),good.biome(),good.anchor(),good.bounds(),foreign,good.baseImportance(),good.activity(),good.ownership(),good.geometry(),good.revision(),good.provenance());fails(()->LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),List.of(f.children.get(0),incompatible),24100),"compatible dimensions do not bypass profile fingerprint");
        var unequalRight=geometry(f.children.get(1),geometry(f.children.get(1).id(),f.parent.bounds(),List.of(Bounds.cube(4,0,0,4)),1));var unclaimed=new LandmarkActivityState.Influence(vector(.2),.1,100);unclaimed.mergedBase=0;var unownedParent=ownership(f.parent,EMPTY);var shares=LandmarkActivityTopology.distribute(unownedParent,unclaimed,List.of(ownership(f.children.get(0),EMPTY),ownership(unequalRight,EMPTY)),100);
        close(shares.values().stream().mapToDouble(v->v.level).sum(),.1,"unequal occupied-volume shares conserve mass");close(shares.get(f.children.get(0).id()).level,.08,"larger occupied geometry gets four-fifths, not bbox half");check(shares.values().stream().allMatch(v->v.mergedBase==0),"zero merged base overrides are supported");
        var zeroState=new LandmarkActivityState();shares.forEach(zeroState::publish);var zeroReload=LandmarkActivityState.read(zeroState.writeNbt(new NbtCompound(),null),null);check(zeroReload.entries.values().stream().allMatch(v->v.mergedBase==0&&v.base(.9)==0),"zero override survives codec and suppresses natural child baseline");
    }
    private static void retirementAndPause()throws Exception{
        var f=fixture(false,false);List<Landmark> children=new ArrayList<>();
        for(int i=0;i<2;i++){var anchor=new BlockPoint(i==0?1:5,1,1);String id=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor);
            children.add(landmark(anchor,f.parent.bounds(),geometry(id,f.parent.bounds(),List.of(Bounds.cube(anchor.x(),anchor.y(),anchor.z(),1)),1),i==0?owners(A):owners(B),1));
        }
        var split=LandmarkActivityTopology.prepareSplit(f.store,f.state,ref(f.parent),children,24100);
        var wrongRemove=LandmarkActivityTopology.prepareRemove(f.store,f.state,List.of(ref(f.parent)));var before=f.state.writeNbt(new NbtCompound(),null);
        publish(f.store.stageSplit(ref(f.parent),children));fails(wrongRemove::commit,"same-revision retirement from SPLIT is not DELETE publication");unchanged(f,before,"lineage guard preserves entire captured history");
        split.commit();check(!f.state.entries.containsKey(f.parent.id())&&f.state.entries.size()==2,"retired parent moves history to exactly two genuine occupied children");
        check(f.store.tombstoneRevision(f.parent.id()).orElseThrow()==1&&f.store.lineageChildren(f.parent.id(),8).size()==2,"exact retired revision/lineage checked through bounded core point APIs");
        var gate=new SpiritActivityService.MutationPause();var thread=Thread.currentThread();java.util.function.BooleanSupplier onThread=()->thread==Thread.currentThread();
        fails(()->gate.acquire(true,onThread),"busy mutation/discovery cannot acquire pause");check(!gate.held,"rejected acquisition cannot cancel or pause current history");
        Runnable resume=gate.acquire(false,onThread);check(gate.held,"real production gate held after acquisition");fails(()->gate.acquire(false,onThread),"nested extractor acquisition rejected");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{resume.run();return false;}catch(IllegalStateException expected){return true;}}).get(5,java.util.concurrent.TimeUnit.SECONDS)&&gate.held,"off-thread resume cannot release extractor protection");
        resume.run();check(!gate.held,"server-thread resume unpauses");Runnable newer=gate.acquire(false,onThread);resume.run();check(gate.held,"idempotent OLD resume cannot release NEW owner's pause");newer.run();newer.run();check(!gate.held,"resume idempotent");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{gate.acquire(false,onThread);return false;}catch(IllegalStateException expected){return true;}}).get(5,java.util.concurrent.TimeUnit.SECONDS)&&!gate.held,"off-thread acquisition never pauses");
    }
    private static void aliasRemoval()throws Exception{
        var f=fixture(false,false);var anchor=new BlockPoint(8,0,0);var root=Bounds.cube(8,0,0,8);String seed=LandmarkIds.seed(DIM,ALGORITHM,Landmark.Kind.CAVE,BIOME,anchor);var other=landmark(anchor,root,geometry(seed,root,List.of(root),0),owners(A),0);publish(f.store.stagePut(other,-1));
        check(f.parent.geometry().sample(7,0,0).occupancy()==BlockSample.Occupancy.AIR&&other.geometry().sample(8,0,0).occupancy()==BlockSample.Occupancy.AIR,"actual adjacent observed AIR face supports fixture proof, not semantics");
        var oldAliasHistory=new LandmarkActivityState.Influence(vector(.2),.1,100);oldAliasHistory.owners.add(A.toString());f.state.publish(other.id(),oldAliasHistory);
        var wrong=LandmarkActivityTopology.prepareRemove(f.store,f.state,List.of(ref(f.parent),ref(other)));f.state.setDirty(false);var before=f.state.writeNbt(new NbtCompound(),null);
        var proof=new LandmarkRepository.VerifiedConnectivity(List.of(ref(f.parent),ref(other)),"fixture observed adjacent full AIR masks across east/west source face");publish(f.store.stageMerge(proof,100,LandmarkInfluence.POLICY));fails(wrong::commit,"merge aliases are NOT proof of core deletion");unchanged(f,before,"redirected parent guard preserves valid influence histories");String canonical=f.store.resolve(f.parent.id());var current=f.store.metadata(canonical).orElseThrow();
        var refs=List.of(new LandmarkRepository.RevisionRef(canonical,current.revision()));var plan=LandmarkActivityTopology.prepareRemove(f.store,f.state,refs);publish(f.store.stageDelete(refs.get(0)));plan.commit();check(f.state.entries.isEmpty()&&f.state.claimedBy(A)==0&&f.state.claimedBy(B)==0,"removal also prunes capped-map legacy alias overlays and quotas");
    }
    public static void main(String[] args)throws Exception{SharedConstants.createGameVersion();conservationAndReload();cancellationStaleAndBudgets();geometryAndProfileBudgets();retirementAndPause();aliasRemoval();System.out.println("ActivityTopologyTest: "+checks+" checks passed");}
}
