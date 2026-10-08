package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.util.math.ChunkPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Regression: the reader's per-chunk decode state must reset when advancing between
 * chunks. Previously scan/collector survived the chunk transition, so a later unloaded
 * chunk (including a potentially ungenerated one) decoded the FIRST chunk's NBT at the new
 * coordinates - assigning chunk A's materials to chunk B, or decoding an absent chunk at
 * all. Drives the real advance() state machine over real NBT section palettes. */
public final class GeneratedSourceReaderMultiChunkTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}

    /** Minimal saved-chunk root: one full section (Y=0) with a single-block palette. */
    private static NbtCompound root(String blockId){
        var root=new NbtCompound();root.putString("Status","full");
        var sections=new NbtList();
        var section=new NbtCompound();section.putByte("Y",(byte)0);
        var states=new NbtCompound();
        var palette=new NbtList();var entry=new NbtCompound();entry.putString("Name",blockId);palette.add(entry);
        states.put("palette",palette); // single palette entry: no packed data needed
        section.put("block_states",states);
        var biomes=new NbtCompound();var biomePalette=new NbtList();biomePalette.add(NbtString.of("minecraft:plains"));
        biomes.put("palette",biomePalette);
        section.put("biomes",biomes);
        sections.add(section);root.put("sections",sections);
        root.put("Heightmaps",new NbtCompound()); // absent heights conservatively mark sky
        return root;
    }

    /** Saved roots by chunk position; a missing root is an ungenerated chunk. */
    private static GeneratedSourceReader.ChunkIo savedChunks(Map<Long,NbtCompound> roots){
        return new GeneratedSourceReader.ChunkIo(){
            @Override public net.minecraft.world.chunk.WorldChunk live(ChunkPos pos){return null;}
            @Override public CompletableFuture<?> scan(ChunkPos pos,GeneratedSourceReader.LimitedCollector collector){
                var root=roots.get(pos.toLong());
                if(root!=null)root.accept(collector); // vanilla drives the scanner over the NBT after IO
                return CompletableFuture.completedFuture(collector.getRoot());
            }
        };
    }

    public static void main(String[] args){
        // Region spanning three x-chunks within one z-chunk: stone, UNGENERATED, dirt.
        Bounds bounds=new Bounds(0,0,0,48,2,16);
        var roots=new HashMap<Long,NbtCompound>();
        roots.put(new ChunkPos(0,0).toLong(),root("minecraft:stone"));
        roots.put(new ChunkPos(2,0).toLong(),root("minecraft:dirt"));
        var reader=new GeneratedSourceReader("minecraft:overworld",bounds,Runnable::run,-64,384,savedChunks(roots));
        for(int ticks=0;ticks<64&&!reader.advance(512);ticks++)check(ticks<63,"reader kept advancing across the three chunks");
        check(reader.chunkIndex==3,"all three chunks advanced (chunkIndex="+reader.chunkIndex+")");
        check(reader.scan==null&&reader.collector==null&&reader.disk==null,
            "every per-chunk decode state (scan/collector/disk) is reset after the final chunk (scan="+reader.scan+" collector="+reader.collector+" disk="+reader.disk+")");
        var region=reader.resultAsync().join();
        check(!region.complete(),"the ungenerated middle chunk leaves the region honestly incomplete, never invented");
        check(region.cells().size()==1024,"every cell of the two generated chunks is present: "+region.cells().size());
        Map<String,Integer> materialsByHalf=new HashMap<>();
        for(var cell:region.cells()){
            String expected=cell.position().x()<16?"minecraft:stone":cell.position().x()>=32?"minecraft:dirt":"absent";
            check(cell.material().blockId().equals(expected),
                "cell "+cell.position()+" decoded from ITS OWN chunk NBT (expected "+expected+", got "+cell.material().blockId()+")");
            materialsByHalf.merge(expected,1,Integer::sum);
        }
        check(materialsByHalf.get("minecraft:stone")==512&&materialsByHalf.get("minecraft:dirt")==512,
            "both chunks contribute exactly their half: "+materialsByHalf);
        check(region.cells().stream().noneMatch(c->c.position().x()>=16&&c.position().x()<32),"the ungenerated middle chunk contributes nothing");
        check(region.cells().stream().allMatch(SourceLandmarks.Cell::sky),"missing heightmap proof conservatively marks every decoded cell as sky-exposed");
        System.out.println("GeneratedSourceReaderMultiChunkTest: "+checks+" checks passed");
    }
}
