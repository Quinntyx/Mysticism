package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.util.math.ChunkPos;
import java.util.*;
import java.util.concurrent.*;

/** Production reader state machine and vanilla NBT decoder, without a game/model bootstrap. */
public final class GeneratedSourceLifecycleTest {
    private static int checks;
    private static final String DIM="minecraft:overworld";
    private static final Bounds TWO=new Bounds(0,0,0,2,1,1);
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static SourceLandmarks.Cell cell(BlockPoint point,String block){
        return new SourceLandmarks.Cell(point,new BlockPalette.State(block,Map.of()),"minecraft:plains",false);
    }
    private static List<SourceLandmarks.Cell> rows(Bounds bounds,String block){
        List<SourceLandmarks.Cell> rows=new ArrayList<>();
        for(long x=bounds.minX();x<bounds.maxX();x++)rows.add(cell(new BlockPoint(x,bounds.minY(),bounds.minZ()),block));
        return rows;
    }
    private static final class Source implements GeneratedSourceReader.Source {
        final Map<ChunkPos,GeneratedSourceReader.LiveChunk> live=new HashMap<>();
        CompletableFuture<List<SourceLandmarks.Cell>> saved=new CompletableFuture<>();
        final List<ChunkPos> reads=new ArrayList<>();
        public GeneratedSourceReader.LiveChunk loaded(ChunkPos pos){return live.get(pos);}
        public CompletableFuture<List<SourceLandmarks.Cell>> saved(ChunkPos pos,Bounds bounds){reads.add(pos);return saved;}
        void load(ChunkPos pos,String block){live.put(pos,new GeneratedSourceReader.LiveChunk(new Object(),p->cell(p,block)));}
    }
    private static GeneratedSourceReader reader(Source source,Bounds bounds){return new GeneratedSourceReader(DIM,bounds,Runnable::run,source);}
    private static SourceLandmarks.Region result(GeneratedSourceReader reader){return reader.resultAsync().join();}
    private static void finish(GeneratedSourceReader reader){
        int turns=0;while(!reader.advance(1)){check(reader.lastSampled<=1,"per-turn sampling budget");check(++turns<32,"finite progress");}
        check(reader.lastSampled<=1,"completion sampling budget");
    }
    private static void liveToSaved(){
        var source=new Source();var chunk=new ChunkPos(0,0);source.load(chunk,"minecraft:stone");var reader=reader(source,TWO);
        check(!reader.advance(1)&&reader.lastSampled==1,"partial live chunk sampled");
        source.live.clear();check(!reader.advance(1),"unload starts read-only saved scan instead of cancelling");
        check(source.reads.equals(List.of(chunk)),"one saved request for generated adapter");
        check(!reader.advance(1)&&reader.lastSampled==0,"pending IO yields without sampling");
        source.saved.complete(List.of(cell(new BlockPoint(1,0,0),"minecraft:air")));finish(reader);
        var region=result(reader);check(!region.complete()&&region.cells().size()==1,"absent saved cells remain UNKNOWN, not stale live proof");
        check(region.cells().getFirst().position().x()==1&&region.cells().getFirst().air(),"known saved air is preserved");

        source=new Source();source.load(chunk,"minecraft:stone");reader=reader(source,TWO);reader.advance(1);source.live.clear();reader.advance(1);
        source.saved.complete(rows(TWO,"minecraft:air"));finish(reader);
        region=result(reader);check(region.complete()&&region.cells().stream().allMatch(SourceLandmarks.Cell::air),"saved snapshot replaces entire partial live slice");
    }
    private static void reloadWhilePendingOrMerging(){
        for(boolean partialMerge:new boolean[]{false,true}){
            var source=new Source();var reader=reader(source,TWO);var old=source.saved;
            check(!reader.advance(1),"unloaded chunk waits for saved scan");
            if(partialMerge){old.complete(rows(TWO,"minecraft:air"));check(!reader.advance(1)&&reader.lastSampled==1,"partial detached merge");}
            source.load(new ChunkPos(0,0),"minecraft:stone");finish(reader);
            var region=result(reader);check(region.complete()&&region.cells().stream().noneMatch(SourceLandmarks.Cell::air),"reload replaces pending/partial disk observation with live slice");
            check(source.reads.size()==1,"reload does not launch redundant saved scans");
            if(!partialMerge){check(old.isCancelled(),"abandoned decoder future cancelled");check(!old.complete(rows(TWO,"minecraft:air")),"late saved result cannot overwrite reloaded data");}
        }
        var failed=new Source();var recovery=reader(failed,TWO);recovery.advance(1);
        failed.saved.completeExceptionally(new IllegalStateException("old saved IO failure"));failed.load(new ChunkPos(0,0),"minecraft:stone");finish(recovery);
        check(result(recovery).complete(),"reload recovers from an abandoned failed saved scan");
        var source=new Source();source.load(new ChunkPos(0,0),"minecraft:air");var reader=reader(source,TWO);reader.advance(1);
        source.load(new ChunkPos(0,0),"minecraft:stone");finish(reader);
        check(result(reader).cells().stream().noneMatch(SourceLandmarks.Cell::air),"changed live chunk identity restarts partial slice even between polls");
    }
    private static void missingAndCrossChunk(){
        var source=new Source();var reader=reader(source,TWO);reader.advance(1);source.saved.complete(List.of());finish(reader);
        check(!result(reader).complete()&&result(reader).cells().isEmpty(),"missing saved chunk never invents air/terrain");
        Bounds cross=new Bounds(-1,0,0,1,1,1);source=new Source();source.load(new ChunkPos(-1,0),"minecraft:stone");reader=reader(source,cross);
        check(!reader.advance(1),"negative source chunk sampled independently");reader.advance(1);source.saved.complete(List.of());finish(reader);
        check(source.reads.equals(List.of(new ChunkPos(0,0))),"floor-div source coordinates select correct saved chunk");
        check(result(reader).cells().size()==1&&result(reader).cells().getFirst().position().x()==-1,"adapter reset does not erase finished neighboring chunk");
        source=new Source();source.live.put(new ChunkPos(0,0),new GeneratedSourceReader.LiveChunk(new Object(),p->null));reader=reader(source,TWO);finish(reader);
        check(!result(reader).complete()&&result(reader).cells().isEmpty(),"unavailable live samples remain unknown");
        source=new Source();reader=reader(source,TWO);reader.advance(1);reader.cancel();check(source.saved.isCancelled(),"cancellation still releases pending IO");
        try{reader.advance(1);throw new AssertionError("cancelled reader advanced");}catch(CancellationException expected){checks++;}
    }
    private static NbtCompound fullChunk(){
        NbtCompound chunk=new NbtCompound();chunk.putString("Status","minecraft:full");NbtList sections=new NbtList();NbtCompound section=new NbtCompound();section.putByte("Y",(byte)-1);
        NbtCompound states=new NbtCompound();NbtList palette=new NbtList();NbtCompound stone=new NbtCompound();stone.putString("Name","minecraft:stone");palette.add(stone);states.put("palette",palette);section.put("block_states",states);
        NbtCompound biomes=new NbtCompound();NbtList bio=new NbtList();bio.add(NbtString.of("minecraft:plains"));biomes.put("palette",bio);section.put("biomes",biomes);sections.add(section);chunk.put("sections",sections);return chunk;
    }
    private static void savedDecode(){
        Bounds bounds=Bounds.cube(-1,-1,-1,1);ChunkPos pos=new ChunkPos(-1,-1);NbtCompound chunk=fullChunk();
        var decoded=GeneratedSourceReader.decode(chunk,pos,bounds,-64,384);
        check(decoded[0]!=null&&decoded[0].position().equals(new BlockPoint(-1,-1,-1))&&!decoded[0].air(),"real full singleton NBT decodes negative section/coordinates");
        check(decoded[0].sky(),"missing height proof cannot fabricate enclosed cave");
        for(String status:List.of("empty","noise","minecraft:features")){
            NbtCompound partial=chunk.copy();partial.putString("Status",status);
            check(GeneratedSourceReader.decode(partial,pos,bounds,-64,384)[0]==null,"non-full saved chunk remains unknown: "+status);
        }
        check(GeneratedSourceReader.decode(null,pos,bounds,-64,384)[0]==null,"absent NBT remains unknown");
        NbtCompound missing=chunk.copy();missing.remove("sections");check(GeneratedSourceReader.decode(missing,pos,bounds,-64,384)[0]==null,"absent sections remain unknown");
        missing=chunk.copy();missing.getList("sections",NbtElement.COMPOUND_TYPE).getCompound(0).remove("biomes");check(GeneratedSourceReader.decode(missing,pos,bounds,-64,384)[0]==null,"absent biome proof remains unknown");
        missing=chunk.copy();NbtList palette=missing.getList("sections",NbtElement.COMPOUND_TYPE).getCompound(0).getCompound("block_states").getList("palette",NbtElement.COMPOUND_TYPE);
        NbtCompound air=new NbtCompound();air.putString("Name","minecraft:air");palette.add(air);
        check(GeneratedSourceReader.decode(missing,pos,bounds,-64,384)[0]==null,"missing packed data in multi-entry palette is not silently truncated");
    }
    public static void main(String[] args){liveToSaved();reloadWhilePendingOrMerging();missingAndCrossChunk();savedDecode();System.out.println("GeneratedSourceLifecycleTest: "+checks+" checks passed");}
}
