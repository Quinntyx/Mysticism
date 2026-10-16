package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Function;

/** Reference-only codec checks, including well beyond one page-of-page-keys. No game,
 * model inference, migration or geometry hydration. Fixture vectors are store test data. */
public final class GeometryReferenceIndexTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){
        try{action.run();}catch(RuntimeException expected){checks++;return;}
        throw new AssertionError(message);
    }
    private static Landmark header(){
        var anchor=new BlockPoint(0,64,0);String dimension="minecraft:overworld",biome="minecraft:plains";
        String id=LandmarkIds.seed(dimension,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,biome,anchor);
        float[] v=new float[EmbeddingSpace.DIMENSIONS];v[0]=1;
        return new Landmark(id,dimension,SourceLandmarks.ALGORITHM,Landmark.Kind.BIOME,biome,anchor,Bounds.cube(0,64,0,1),
            LandmarkProfiles.wrap(new Vec384f(v)),.08,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"reference directory fixture");
    }
    private static List<String> keys(int count){
        var keys=new ArrayList<String>();
        for(int i=0;i<count;i++)keys.add("mysticism.landmark.geometry.lm-"+String.format(Locale.ROOT,"%064x",i)+".0");
        return List.copyOf(keys);
    }
    private static void roundtrip(int count){
        var value=new LandmarkMetadata(header(),keys(count));var encoded=LandmarkNbt.encodeMetadataPages(value);
        var blobs=new HashMap<String,NbtCompound>();
        for(var page:encoded.references()){
            check(GeometryReferenceIndex.validKey(page.key()),"content-addressed reference page key");
            int size=page.nbt().getList("geometry",NbtElement.STRING_TYPE).size();
            check(size>0&&size<=GeometryReferenceIndex.PAGE_SIZE,"every reference page is independently bounded");
            blobs.put(page.key(),page.nbt());
        }
        int[] loads={0};Function<String,NbtCompound> loader=key->{loads[0]++;return blobs.get(key);};
        var restored=LandmarkNbt.decodeMetadata(encoded.record(),loader);
        check(restored.geometryKeys().equals(value.geometryKeys()),"every opaque geometry key round-trips without truncation or reordering");
        check(restored.header().baseEmbedding().equals(value.header().baseEmbedding()),"native embedding bits/profile stay unchanged");
        if(count<=LandmarkNbt.MAX_INLINE_GEOMETRY_REFS){
            check(loads[0]==0&&encoded.references().isEmpty()&&!encoded.record().contains("geometryIndex"),"legacy inline headers require no reference pages");
            check(LandmarkNbt.decodeMetadata(LandmarkNbt.encodeMetadata(value)).equals(value),"legacy standalone metadata codec stays compatible");
        }else{
            check(loads[0]==(count+GeometryReferenceIndex.PAGE_SIZE-1)/GeometryReferenceIndex.PAGE_SIZE,"directory has no hidden root-list ceiling");
            check(encoded.record().getList("geometry",NbtElement.STRING_TYPE).isEmpty(),"large metadata header stores one root, not an oversized inline list");
            check(encoded.record().getInt("schema")==2,"old readers must reject the paged layout instead of accepting empty geometry");
            rejects(()->LandmarkNbt.decodeMetadata(encoded.record()),"large metadata must never decode as empty geometry without its loader");
        }
    }
    private static void malformed(){
        var value=new LandmarkMetadata(header(),keys(4800));var encoded=LandmarkNbt.encodeMetadataPages(value);
        var blobs=new HashMap<String,NbtCompound>();for(var p:encoded.references())blobs.put(p.key(),p.nbt());
        var record=encoded.record();String root=record.getString("geometryIndex");
        var oldSchema=record.copy();oldSchema.putInt("schema",1);
        rejects(()->LandmarkNbt.decodeMetadata(oldSchema,blobs::get),"paged directory cannot masquerade as legacy schema");
        var futureSchema=record.copy();futureSchema.putInt("schema",3);
        rejects(()->LandmarkNbt.decodeMetadata(futureSchema,blobs::get),"unknown record layouts fail loudly");
        var wrongCount=record.copy();wrongCount.putInt("geometryCount",4801);
        rejects(()->LandmarkNbt.decodeMetadata(wrongCount,blobs::get),"incorrect count must fail, not lose the final references");
        var truncatedCount=record.copy();truncatedCount.putInt("geometryCount",4608);
        rejects(()->LandmarkNbt.decodeMetadata(truncatedCount,blobs::get),"extra tail must fail, not truncate unread references");
        var ambiguous=record.copy();var inline=new NbtList();inline.add(NbtString.of(value.geometryKeys().getFirst()));ambiguous.put("geometry",inline);
        rejects(()->LandmarkNbt.decodeMetadata(ambiguous,blobs::get),"inline plus paged references must not be combined ambiguously");
        var corrupt=new HashMap<>(blobs);var changed=blobs.get(root).copy();changed.putString("next","bad path");corrupt.put(root,changed);
        rejects(()->LandmarkNbt.decodeMetadata(record,corrupt::get),"content hash mismatch must not be treated as missing/empty terrain");
        var missing=new HashMap<>(blobs);missing.remove(encoded.references().getFirst().key());
        rejects(()->LandmarkNbt.decodeMetadata(record,missing::get),"missing remote directory tail fails loudly");
        var invalid=record.copy();invalid.putString("geometryIndex","../../unrelated");
        rejects(()->LandmarkNbt.decodeMetadata(invalid,key->{throw new AssertionError("invalid path reached loader");}),"keys validated before IO");
        var duplicate=new ArrayList<>(value.geometryKeys());duplicate.set(4799,duplicate.getFirst());
        var dupIndex=GeometryReferenceIndex.encode(duplicate);var dupBlobs=new HashMap<String,NbtCompound>();for(var p:dupIndex.pages())dupBlobs.put(p.key(),p.nbt());
        var dupRecord=record.copy();dupRecord.putString("geometryIndex",dupIndex.root());
        rejects(()->LandmarkNbt.decodeMetadata(dupRecord,dupBlobs::get),"two versions of one geometry page ID must still be rejected across index pages");
    }
    public static void main(String[] args){
        SharedConstants.createGameVersion();
        roundtrip(0);roundtrip(4096);roundtrip(4097);roundtrip(4800);roundtrip(70001);malformed();
        System.out.println("GeometryReferenceIndexTest passed: "+checks+" checks");
    }
}
