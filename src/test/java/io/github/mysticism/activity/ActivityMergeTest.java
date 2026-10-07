package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
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

/** Actual reducer, immutable octrees, NBT, and core PersistentState transactions; no mocked model/world. */
public final class ActivityMergeTest {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static void fails(Runnable action,String name){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(name);}
    private static Vec384f vector(double y){float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;v[1]=(float)y;return new Vec384f(new Vec384f(v).norm());}
    private static Landmark landmark(long x,double base,String biome){
        BlockPoint p=new BlockPoint(x,0,0);
        return new Landmark(LandmarkIds.seed("minecraft:overworld","activity-test",Landmark.Kind.CAVE,biome,p),"minecraft:overworld","activity-test",Landmark.Kind.CAVE,biome,p,Bounds.cube(x,0,0,8),LandmarkProfiles.wrap(vector(x/1000.0)),base,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"test observed connectivity supplied separately");
    }
    private static LandmarkMetadata metadata(Landmark l){return new LandmarkMetadata(l,List.of());}
    private static PersistentStateManager manager(Path path){
        DataFixer identity=new DataFixer(){
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> value,int from,int to){return value;}
            @Override public Schema getSchema(int version){throw new UnsupportedOperationException();}
        };
        return new PersistentStateManager(path.toFile(),identity,RegistryWrapper.WrapperLookup.of(Stream.empty()));
    }
    // Foundation exposes the scratch-store constructor only within its package. Reflection
    // reaches that REAL constructor in tests; production still uses LandmarkStore.get(server).
    private static LandmarkStore store(PersistentStateManager manager,Path path)throws Exception{
        var factory=LandmarkStore.class.getDeclaredMethod("open",PersistentStateManager.class,Path.class);
        factory.setAccessible(true);return (LandmarkStore)factory.invoke(null,manager,path);
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.createGameVersion();UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000001"),other=UUID.fromString("00000000-0000-0000-0000-000000000002");
        Landmark low=landmark(0,.1,"minecraft:plains");
        var coarse=LandmarkInfluence.reduce(low,null,vector(.05),new BlockPoint(1,1,1),owner,1,true,0,20);
        var leaf=coarse.influence().claims.cells(256).getFirst();
        check(leaf.bounds().equals(Bounds.cube(0,0,0,8)),"low importance uses one coarse eight-block cube");
        var repeat=LandmarkInfluence.reduce(low,coarse.influence(),vector(.1),new BlockPoint(2,2,2),owner,1,true,0,20);
        check(repeat.influence().vector.squareDistance(coarse.influence().vector)==0,"same tick cannot drift");
        check(repeat.influence().level==coarse.influence().level,"same tick cannot grow importance");
        check(repeat.influence().claims.cells(256).size()==1,"low-value repeated events do not allocate block leaves");
        Landmark high=landmark(0,.9,"minecraft:plains");
        var fine=LandmarkInfluence.reduce(high,null,vector(.1),new BlockPoint(1,1,1),other,1,true,0,20);
        check(fine.influence().claims.cells(256).getFirst().bounds().equals(Bounds.cube(1,1,1,1)),"high importance permits one-block refinement");
        var overlap=LandmarkInfluence.reduce(low,fine.influence(),vector(.1),new BlockPoint(6,6,6),owner,1,true,0,40);
        check(overlap.influence().claims.sample(1,1,1).equals(other.toString()),"coarse write never overwrites another owner's fine cell");
        check(overlap.influence().claims.sample(6,6,6).equals(owner.toString()),"claim refines locally around existing owner");
        check(fine.influence().claims.sample(6,6,6)==null,"old tree immutable");
        fails(()->LandmarkInfluence.reduce(low,coarse.influence(),vector(.1),new BlockPoint(1,1,1),owner,1,true,0,19),"time reversal rejected");
        Landmark a=landmark(0,.6,"minecraft:plains"),b=landmark(8,.7,"minecraft:plains");
        var ia=new LandmarkActivityState.Influence(vector(0),.2,100);var ib=new LandmarkActivityState.Influence(vector(.008),.3,100);
        ia.claims=SparseOctree.<String>empty(Bounds.cube(0,0,0,8),1,512).with(Bounds.cube(0,0,0,8),owner.toString(),256);ia.owners.add(owner.toString());
        ib.claims=SparseOctree.<String>empty(Bounds.cube(8,0,0,8),1,512).with(Bounds.cube(8,0,0,8),other.toString(),256);ib.owners.add(other.toString());
        var combined=LandmarkMerge.combine(List.of(metadata(a),metadata(b)),List.of(ia,ib),100);
        check(Math.abs(combined.mergedBase-1.3)<1e-10,"raw base SUM conserved separately from render cap");
        check(Math.abs(combined.level-.5)<1e-10,"activity SUM conserved, not maximum");
        check(combined.importance(.7,100)==1,"render importance capped at one");
        check(Math.abs(combined.level(24100)-.25)<1e-10,"merged activity decays without losing histories");
        check(combined.claims.sample(0,0,0).equals(owner.toString())&&combined.claims.sample(8,0,0).equals(other.toString()),"union preserves spatial claims");
        check(combined.owners.size()==2,"union preserves all ownership histories");
        var reverse=LandmarkMerge.combine(List.of(metadata(b),metadata(a)),List.of(ib,ia),100);
        check(reverse.vector.squareDistance(combined.vector)==0&&reverse.claims.cells(256).equals(combined.claims.cells(256)),"merge order deterministic");
        fails(()->LandmarkMerge.combine(List.of(metadata(a),metadata(landmark(256,.1,"minecraft:plains"))),Arrays.asList(ia,null),100),"source locality hard gate");
        fails(()->LandmarkMerge.combine(List.of(metadata(a),metadata(landmark(8,.1,"minecraft:desert"))),Arrays.asList(ia,null),100),"different biome cave barrier");
        var remoteVector=new LandmarkActivityState.Influence(vector(1),0,100);
        fails(()->LandmarkMerge.combine(List.of(metadata(a),metadata(b)),List.of(ia,remoteVector),100),"semantic radius hard gate despite importance");
        fails(()->LandmarkMerge.combine(List.of(metadata(a),metadata(a)),List.of(ia,ia),100),"duplicate fragments cannot multiply mass");
        LandmarkActivityState state=new LandmarkActivityState();state.publish(a.id(),ia);state.publish(b.id(),ib);
        Path dir=Files.createTempDirectory("activity-merge-regression-");var pm=manager(dir);LandmarkStore store=store(pm,dir);
        store.stagePut(a,-1).advance(8);store.stagePut(b,-1).advance(8);
        var proof=new LandmarkRepository.VerifiedConnectivity(List.of(new LandmarkRepository.RevisionRef(a.id(),0),new LandmarkRepository.RevisionRef(b.id(),0)),"fixture physical evidence; production extractor must observe six-neighbour connectivity");
        var cancelled=LandmarkMerge.prepare(store,state,proof,100);cancelled.cancel();
        fails(cancelled::commit,"cancelled activity plan cannot publish");
        check(state.entries.size()==2&&store.resolve(a.id()).equals(a.id())&&store.resolve(b.id()).equals(b.id()),"cancellation changes neither store nor histories");
        check(java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{LandmarkMerge.prepare(store,state,proof,100);return false;}catch(IllegalStateException confined){return true;}
        }).get(5,java.util.concurrent.TimeUnit.SECONDS),"real store rejects off-thread merge preparation");
        var capState=new LandmarkActivityState();var capPlan=LandmarkMerge.prepare(store,capState,proof,100);
        for(int i=0;i<LandmarkActivityState.LIMIT;i++)capState.publish(LandmarkIds.seed("minecraft:overworld","budget-test",Landmark.Kind.CAVE,"minecraft:plains",new BlockPoint(1000+i,0,0)),new LandmarkActivityState.Influence(vector(0),0,100));
        var plan=LandmarkMerge.prepare(store,state,proof,100);
        fails(plan::commit,"activity cannot publish before core merge");
        check(state.entries.size()==2,"early commit leaves both histories intact");
        var mutation=store.stageMerge(proof,100,LandmarkInfluence.POLICY);mutation.advance(1,256);
        fails(plan::commit,"partially advanced core merge cannot publish activity");
        while(!mutation.complete())mutation.advance(1,256);
        fails(capPlan::commit,"commit rechecks unrelated overlay capacity changes");
        check(capState.entries.size()==LandmarkActivityState.LIMIT,"failed late-budget commit retains all old state without over-allocation");
        plan.commit();
        String canonical=List.of(a.id(),b.id()).stream().min(String::compareTo).orElseThrow();
        check(state.entries.size()==1&&state.entries.containsKey(canonical),"history follows real atomic core canonical alias");
        check(store.metadata(canonical).orElseThrow().header().anchor().equals(canonical.equals(a.id())?a.anchor():b.anchor()),"source seed/anchor unchanged");
        fails(plan::commit,"commit is single use, no duplicate sums");pm.save();
        Path file=dir.resolve("activity.dat");NbtIo.writeCompressed(state.writeNbt(new NbtCompound(),null),file);
        var loaded=LandmarkActivityState.read(NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes()),null);
        check(Math.abs(loaded.entries.get(canonical).mergedBase-1.3)<1e-10,"mass above rendering cap survives real compressed reload");
        check(loaded.claimedBy(owner)==1&&loaded.claimedBy(other)==1,"combined quotas survive reload");
        var storeReload=store(manager(dir),dir);check(storeReload.resolve(a.id()).equals(canonical)&&storeReload.resolve(b.id()).equals(canonical),"authoritative aliases survive reload");
        var post=LandmarkInfluence.reduce(storeReload.metadata(canonical).orElseThrow().header(),loaded.entries.get(canonical),vector(.01),new BlockPoint(1,1,1),owner,1,false,1,120);
        check(post.influence().mergedBase==combined.mergedBase,"later dwell preserves summed base history");
        check(post.influence().level>.49,"later dwell does not clamp old merged activity to individual .35 cap");
        check(post.influence().level<=combined.level,"high merged activity only decays, cannot regrow above individual growth cap");
        var chained=LandmarkMerge.combine(List.of(storeReload.metadata(canonical).orElseThrow(),metadata(landmark(16,.4,"minecraft:plains"))),Arrays.asList(combined,null),100);
        check(Math.abs(chained.mergedBase-1.7)<1e-10&&chained.level==.5,"repeat merge conserves pre-cap base/activity sums");
        var fullMass=new LandmarkActivityState.Influence(vector(0),0,100);fullMass.mergedBase=LandmarkActivityState.MAX_IMPORTANCE_MASS;
        var atCap=LandmarkInfluence.reduce(a,fullMass,vector(.01),new BlockPoint(1,1,1),null,1,false,0,120);
        check(atCap.influence().level==0,"monotonic growth obeys total conserved mass cap");
        fails(()->LandmarkMerge.combine(List.of(metadata(a),metadata(b)),Arrays.asList(fullMass,null),100),"mass overflow rejects union rather than truncating histories");
        var overlapMass=new LandmarkActivityState.Influence(vector(0),0,100);overlapMass.claims=ia.claims;overlapMass.owners.add(owner.toString());
        var nested=new LandmarkActivityState.Influence(vector(.008),0,100);nested.claims=SparseOctree.<String>empty(Bounds.cube(0,0,0,8),1,512).with(Bounds.cube(0,0,0,1),other.toString(),256);nested.owners.add(other.toString());
        var overlapped=LandmarkMerge.combine(List.of(metadata(a),metadata(b)),List.of(overlapMass,nested),100);
        check(overlapped.claims.sample(7,7,7)!=null&&overlapped.claims.sample(0,0,0)!=null,"overlapping coarse/fine merge preserves full spatial union");
        check(overlapped.owners.containsAll(List.of(owner.toString(),other.toString())),"overlap never drops player history");
        NbtCompound malformed=state.writeNbt(new NbtCompound(),null);malformed.getList("entries",NbtElement.COMPOUND_TYPE).getCompound(0).putString("owners","wrong");
        final var malformedOwners=malformed;
        fails(()->LandmarkActivityState.read(malformedOwners,null),"wrong owner list type rejected");
        malformed=state.writeNbt(new NbtCompound(),null);var row=malformed.getList("entries",NbtElement.COMPOUND_TYPE).getCompound(0);var claims=row.getList("claims",NbtElement.COMPOUND_TYPE);claims.add(claims.getCompound(0).copy());final var overlapTag=malformed;
        fails(()->LandmarkActivityState.read(overlapTag,null),"overlapping claim cubes rejected");
        System.out.println("ActivityMergeTest: "+checks+" checks passed; compressed/core fixtures retained at "+dir);
    }
}
