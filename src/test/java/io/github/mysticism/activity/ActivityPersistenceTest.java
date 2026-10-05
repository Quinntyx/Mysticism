package io.github.mysticism.activity;

import io.github.mysticism.component.LatentAttunement;
import io.github.mysticism.embedding.EmbeddingNbt;
import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;

/** Real production CCA codecs, influence state and compressed Minecraft NBT, without a model/server boot. */
public final class ActivityPersistenceTest {
    private static int checks;
    private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    private static Vec384f axis(int index){float[] data=new float[EmbeddingSpace.DIMENSIONS];data[index]=1;return new Vec384f(data);}
    public static void main(String[] args)throws Exception{
        LatentAttunement a=new LatentAttunement();a.observe(axis(0));a.set(axis(1));a.steer(axis(2),0.015);
        NbtCompound tag=new NbtCompound();a.writeToNbt(tag,null);
        LatentAttunement b=new LatentAttunement();b.readFromNbt(tag,null);
        check(b.get().squareDistance(a.get())==0,"current reload");check(b.target().squareDistance(axis(1))==0,"target reload distinct");
        check(b.personal().squareDistance(axis(0))==0,"personal reload distinct");
        b.observe(axis(3));check(b.target().squareDistance(axis(1))==0,"supported/personality drift retains explicit target");
        b.get().mul(0);check(b.get().length()>0.9,"CCA snapshots");
        b.followPersonal();b.observe(axis(4));check(b.target().squareDistance(b.personal())==0,"personal target mode");
        NbtCompound legacy=new NbtCompound();EmbeddingNbt.stamp(legacy);legacy.putIntArray("v",axis(5).toBits());b.readFromNbt(legacy,null);
        check(b.get().squareDistance(axis(5))==0&&b.target().squareDistance(axis(5))==0,"wave1 migration");
        legacy.putString("embeddingFingerprint","old model");b.readFromNbt(legacy,null);check(b.get().length()==0,"mismatched profile quarantined");
        NbtCompound archived=new NbtCompound();b.writeToNbt(archived,null);check(archived.getCompound("embeddingArchive").equals(legacy),"lossless profile archive");
        NbtCompound corrupt=tag.copy();corrupt.putIntArray("personal",new int[]{1});b.readFromNbt(corrupt,null);check(b.target().length()==0,"partial/corrupt decode atomic");
        LandmarkActivityState state=new LandmarkActivityState();
        String id=LandmarkIds.seed("minecraft:overworld","activity-test",Landmark.Kind.CAVE,"minecraft:plains",new BlockPoint(-7,-7,-7));
        UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var influence=new LandmarkActivityState.Influence(axis(0),0.2,100);
        influence.claims=SparseOctree.empty(Bounds.cube(-8,-8,-8,8),1,512);
        influence.claims=influence.claims.with(Bounds.cube(-7,-7,-7,1),owner.toString(),256);
        influence.claims=influence.claims.with(Bounds.cube(1,1,1,1),owner.toString(),256);influence.owners.add(owner.toString());state.publish(id,influence);
        check(influence.claims.sample(-7,-7,-7).equals(owner.toString()),"root growth preserves absolute claims");
        check(influence.claims.sample(-6,-7,-7)==null,"one-block resolution refinement");
        Path dir=Files.createTempDirectory("mysticism-activity-");Path file=dir.resolve("activity.dat");
        NbtIo.writeCompressed(state.writeNbt(new NbtCompound(),null),file);
        LandmarkActivityState reload=LandmarkActivityState.read(NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes()),null);
        check(reload.entries.size()==1&&reload.entries.containsKey(id),"stable source identity disk reload");
        check(reload.entries.get(id).vector.squareDistance(axis(0))==0,"influence vector disk reload");
        check(reload.entries.get(id).claims.sample(1,1,1).equals(owner.toString()),"grown octree disk reload");
        check(reload.claimedBy(owner)==1,"ownership quotas disk reload");
        check(Math.abs(reload.entries.get(id).level(24100)-0.1)<1e-10,"tick half-life persistence");
        NbtCompound bad=state.writeNbt(new NbtCompound(),null);bad.putString("embeddingFingerprint","other");
        check(LandmarkActivityState.read(bad,null).entries.isEmpty(),"activity profile migration quarantine");
        NbtCompound oversized=state.writeNbt(new NbtCompound(),null);NbtList list=new NbtList();for(int i=0;i<513;i++)list.add(oversized.getList("entries",NbtElement.COMPOUND_TYPE).getCompound(0).copy());oversized.put("entries",list);
        try{LandmarkActivityState.read(oversized,null);throw new AssertionError("oversized state accepted");}catch(IllegalArgumentException expected){checks++;}
        Landmark h=new Landmark(id,"minecraft:overworld","activity-test",Landmark.Kind.CAVE,"minecraft:plains",new BlockPoint(-7,-7,-7),
                new Bounds(-8,-8,-8,128,128,128),LandmarkProfiles.wrap(axis(0)),0.8,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"test");
        LandmarkActivityState.Influence previous=null;
        for(int i=1;i<=1000;i++){
            var reduced=LandmarkInfluence.reduce(h,previous,axis(1),new BlockPoint(-7,-7,-7),owner,1,true,0,i*20L);
            check(reduced.influence().level<=0.35,"production importance cap");check(Math.abs(reduced.influence().vector.length()-1)<1e-5,"production normalized event drift");
            check(reduced.ownership().claims().size()==1,"repeated build/break ownership deduplicated");
            check(h.id().equals(id)&&h.anchor().equals(new BlockPoint(-7,-7,-7)),"activity preserves source id/anchor");
            previous=reduced.influence();h=h.withActivity(reduced.activity(),reduced.ownership());
        }
        check(previous.vector.squareDistance(axis(1))<1e-6,"production personal dwell convergence");
        var outside=LandmarkInfluence.reduce(h,previous,axis(2),new BlockPoint(152,128,128),owner,1,true,0,20020);
        check(outside.influence().vector.squareDistance(previous.vector)==0,"nearby-region strict radius independent of importance");
        UUID outsider=UUID.randomUUID();var quota=LandmarkInfluence.reduce(h,previous,axis(2),new BlockPoint(0,0,0),outsider,1,true,8,20040);
        check(quota.ownership().claims().size()==1,"player landmark ownership quota");
        check(quota.influence().claims.sample(0,0,0)==null,"quota leaves claims immutable");
        check(previous.claims.sample(-7,-7,-7).equals(owner.toString()),"old ownership tree immutable after attempts");
        check(previous.claims.cells(256).size()==1,"dwell does not grow cells");
        NbtCompound malformed=state.writeNbt(new NbtCompound(),null);malformed.getList("entries",NbtElement.COMPOUND_TYPE).getCompound(0).putString("root","wrong type");
        try{LandmarkActivityState.read(malformed,null);throw new AssertionError("malformed root accepted");}catch(IllegalArgumentException expected){checks++;}
        NbtCompound restored=LandmarkActivityState.read(bad,null).writeNbt(new NbtCompound(),null);
        check(LandmarkActivityState.read(restored,null).writeNbt(new NbtCompound(),null).contains("embeddingArchive"),"profile archive survives repeated reload");
        System.out.println("ActivityPersistenceTest: "+checks+" checks passed; compressed data retained at "+file);
    }
}
