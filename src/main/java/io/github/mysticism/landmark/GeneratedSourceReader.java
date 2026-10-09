package io.github.mysticism.landmark;

import net.minecraft.nbt.*;
import net.minecraft.nbt.scanner.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import java.util.*;
import java.util.concurrent.*;

/** Generated-only IO adapter. Live chunks are sampled on server tick; unloaded chunks use
 * vanilla's async read-only NBT scanner (including pending saves), NEVER getChunk/create/tickets.
 * Detached NBT palettes are decoded on our bounded worker, with no world/registry access there. */
final class GeneratedSourceReader {
    final String dimension; final Bounds bounds; final int seaLevel;
    final SourceLandmarks.Cell[] cells; final List<ChunkPos> chunks=new ArrayList<>();
    final Executor executor; int chunkIndex,cellIndex,lastSampled; boolean cancelled;
    CompletableFuture<List<SourceLandmarks.Cell>> disk;List<SourceLandmarks.Cell> diskRows;int diskCursor;
    LimitedCollector collector;CompletableFuture<?> scan;
    /** Chunk presence + read-only NBT scan; the world-backed adapter in production, a fake in regressions. */
    interface ChunkIo {
        WorldChunk live(ChunkPos pos);
        /** Drives the collector over the saved chunk NBT; must not block the caller. */
        CompletableFuture<?> scan(ChunkPos pos,LimitedCollector collector);
    }
    GeneratedSourceReader(ServerWorld world,Bounds bounds,Executor executor) {
        this(world.getRegistryKey().getValue().toString(),bounds,executor,world.getBottomY(),world.getHeight(),world.getSeaLevel(),new ChunkIo(){
            @Override public WorldChunk live(ChunkPos pos){return world.getChunkManager().getWorldChunk(pos.x,pos.z);}
            @Override public CompletableFuture<?> scan(ChunkPos pos,LimitedCollector collector){return world.getChunkManager().getChunkIoWorker().scanChunk(pos,collector);}
        });
    }
    /** Detached metadata + IO seam: production captures sea level on the owner thread,
     * so Ensure completion never depends on a nullable world retained by a test adapter. */
    GeneratedSourceReader(String dimension,Bounds bounds,Executor executor,int bottom,int height,int seaLevel,ChunkIo io) {
        this.io=io;this.bottom=bottom;this.height=height;this.seaLevel=seaLevel;
        this.dimension=dimension;this.bounds=bounds;this.executor=executor;
        long volume=Math.multiplyExact(Math.multiplyExact(bounds.maxX()-bounds.minX(),bounds.maxY()-bounds.minY()),bounds.maxZ()-bounds.minZ());
        if(volume>32768)throw new IllegalArgumentException("source region volume");
        cells=new SourceLandmarks.Cell[(int)volume];
        for(int x=Math.floorDiv((int)bounds.minX(),16);x<=Math.floorDiv((int)bounds.maxX()-1,16);x++)
            for(int z=Math.floorDiv((int)bounds.minZ(),16);z<=Math.floorDiv((int)bounds.maxZ()-1,16);z++)chunks.add(new ChunkPos(x,z));
        if(chunks.size()>16)throw new IllegalArgumentException("source chunk IO budget");
    }
    private final ChunkIo io;private final int bottom,height;
    /** Advancing to the next chunk clears ALL per-chunk decode state: a new chunk must never
     * decode the previous chunk's NBT (or reuse a completed scan of a different/ungenerated chunk). */
    private void completeChunk(){disk=null;diskRows=null;diskCursor=0;collector=null;scan=null;chunkIndex++;cellIndex=0;}
    boolean advance(int budget) {
        lastSampled=0;if(cancelled)throw new CancellationException();
        if(chunkIndex==chunks.size())return true;
        ChunkPos cp=chunks.get(chunkIndex);
        if(disk!=null) {
            if(!disk.isDone())return false;if(diskRows==null)diskRows=disk.getNow(List.of());
            int end=Math.min(diskRows.size(),diskCursor+budget);
            while(diskCursor<end){lastSampled++;var cell=diskRows.get(diskCursor++);cells[index(bounds,cell.position())]=cell;}
            if(diskCursor==diskRows.size()){completeChunk();}
            return chunkIndex==chunks.size();
        }
        WorldChunk live=io.live(cp);
        if(live==null) {
            Bounds region=bounds;
            if(scan==null){collector=new LimitedCollector();scan=io.scan(cp,collector);}
            // Decode attachment tolerates a saturated worker: retry next tick, never cancel the observation.
            if(disk==null){try{disk=scan.thenApplyAsync(v->Arrays.stream(decode(collector.getRoot(),cp,region,bottom,height)).filter(Objects::nonNull).toList(),executor);}catch(RejectedExecutionException busy){return false;}}
            return false;
        }
        Bounds slice=new Bounds(Math.max(bounds.minX(),cp.getStartX()),bounds.minY(),Math.max(bounds.minZ(),cp.getStartZ()),Math.min(bounds.maxX(),cp.getStartX()+16L),bounds.maxY(),Math.min(bounds.maxZ(),cp.getStartZ()+16L));
        int volume=(int)((slice.maxX()-slice.minX())*(slice.maxY()-slice.minY())*(slice.maxZ()-slice.minZ()));int sampled=0;long deadline=System.nanoTime()+1_500_000L;
        while(cellIndex<volume && sampled<budget && (sampled==0||System.nanoTime()<deadline)) {
            BlockPoint p=point(slice,cellIndex++);int i=index(bounds,p);sampled++;lastSampled++;
            if(p.y()<bottom || p.y()>=bottom+height)continue;
            BlockPos pos=new BlockPos((int)p.x(),(int)p.y(),(int)p.z());var state=live.getBlockState(pos);
            Map<String,String> properties=new TreeMap<>();state.getEntries().forEach((key,value)->properties.put(key.getName(),name(key,value)));
            String biome=live.getBiomeForNoiseGen(pos.getX()>>2,pos.getY()>>2,pos.getZ()>>2).getKey().map(k->k.getValue().toString()).orElse(null);
            if(biome!=null)cells[i]=new SourceLandmarks.Cell(p,new BlockPalette.State(Registries.BLOCK.getId(state.getBlock()).toString(),properties),biome,
                p.y()>live.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,pos.getX()&15,pos.getZ()&15));
        }
        if(cellIndex==volume){completeChunk();}
        return chunkIndex==chunks.size();
    }
    /** Null only on a saturated worker; the caller retries next tick. */
    CompletableFuture<SourceLandmarks.Region> resultAsync(){var copy=cells.clone();try{return CompletableFuture.supplyAsync(()->new SourceLandmarks.Region(dimension,bounds,Arrays.stream(copy).filter(Objects::nonNull).toList(),Arrays.stream(copy).allMatch(Objects::nonNull)),executor);}catch(RejectedExecutionException busy){return null;}}
    private static int index(Bounds b,BlockPoint p){return (int)((p.x()-b.minX())+(b.maxX()-b.minX())*((p.z()-b.minZ())+(b.maxZ()-b.minZ())*(p.y()-b.minY())));}
    void cancel(){cancelled=true;if(disk!=null)disk.cancel(false);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String name(Property p,Comparable value){return p.name(value);}
    static BlockPoint point(Bounds b,int i){long dx=b.maxX()-b.minX(),dz=b.maxZ()-b.minZ();return new BlockPoint(b.minX()+i%dx,b.minY()+i/(dx*dz),b.minZ()+(i/dx)%dz);}
    static final class LimitedCollector extends SelectiveNbtCollector {
        private long words,characters,nodes;
        LimitedCollector(){super(new NbtScanQuery(NbtString.TYPE,"Status"),new NbtScanQuery(NbtList.TYPE,"sections"),new NbtScanQuery(NbtCompound.TYPE,"Heightmaps"));}
        @Override public NbtScanner.Result visitListMeta(NbtType<?> type,int length){if(length>4096||(nodes+=length)>65536)throw new IllegalArgumentException("chunk NBT list budget");return super.visitListMeta(type,length);}
        @Override public NbtScanner.Result visitLongArray(long[] values){if(values.length>4096||(words+=values.length)>524288)throw new IllegalArgumentException("chunk packed array budget");return super.visitLongArray(values);}
        @Override public NbtScanner.Result visitString(String value){if(value.length()>1024||(characters+=value.length())>4194304)throw new IllegalArgumentException("chunk descriptor budget");return super.visitString(value);}
    }
    private static SourceLandmarks.Cell[] decode(NbtElement root,ChunkPos cp,Bounds bounds,int bottom,int height) {
        int volume=(int)((bounds.maxX()-bounds.minX())*(bounds.maxY()-bounds.minY())*(bounds.maxZ()-bounds.minZ()));
        SourceLandmarks.Cell[] result=new SourceLandmarks.Cell[volume];
        if(!(root instanceof NbtCompound n) || !Set.of("full","minecraft:full").contains(n.getString("Status")))return result;
        NbtList sections=n.getList("sections",NbtElement.COMPOUND_TYPE);if(sections.size()>64)throw new IllegalArgumentException("chunk section budget");
        Map<Integer,NbtCompound> byY=new HashMap<>();for(int i=0;i<sections.size();i++){var section=sections.getCompound(i);byY.put((int)section.getByte("Y"),section);}
        long[] heights=n.getCompound("Heightmaps").getLongArray("MOTION_BLOCKING_NO_LEAVES");
        for(int i=0;i<volume;i++) {
            BlockPoint p=point(bounds,i);if(Math.floorDiv(p.x(),16)!=cp.x || Math.floorDiv(p.z(),16)!=cp.z || p.y()<bottom || p.y()>=bottom+height)continue;
            NbtCompound section=byY.get(Math.floorDiv((int)p.y(),16));if(section==null)continue; // absent section/biome is UNKNOWN, never invented air
            NbtCompound bs=section.getCompound("block_states"),biomes=section.getCompound("biomes");
            NbtList palette=bs.getList("palette",NbtElement.COMPOUND_TYPE),bio=biomes.getList("palette",NbtElement.STRING_TYPE);
            if(palette.isEmpty() || bio.isEmpty())continue;
            int x=(int)p.x()&15,y=(int)p.y()&15,z=(int)p.z()&15;
            int index=packed(bs.getLongArray("data"),palette.size(),4,y*256+z*16+x);
            int biomeIndex=packed(biomes.getLongArray("data"),bio.size(),1,(y>>2)*16+(z>>2)*4+(x>>2));
            if(index<0 || index>=palette.size() || biomeIndex<0 || biomeIndex>=bio.size())continue;
            NbtCompound state=palette.getCompound(index);Map<String,String> props=new TreeMap<>();NbtCompound properties=state.getCompound("Properties");
            if(properties.getSize()>64)throw new IllegalArgumentException("state property budget");for(String key:properties.getKeys())props.put(key,properties.getString(key));
            int h=packedBits(heights,32-Integer.numberOfLeadingZeros(height),z*16+x);
            boolean sky=h<0 || p.y()>bottom+h-1; // missing height proof conservatively forbids a closed cave
            result[i]=new SourceLandmarks.Cell(p,new BlockPalette.State(state.getString("Name"),props),bio.getString(biomeIndex),sky);
        }
        return result;
    }
    private static int packed(long[] data,int paletteSize,int minBits,int index){return paletteSize==1?0:packedBits(data,Math.max(minBits,32-Integer.numberOfLeadingZeros(paletteSize-1)),index);}
    private static int packedBits(long[] data,int bits,int index){int per=64/bits,word=index/per;if(word>=data.length)return -1;return (int)((data[word]>>>((index%per)*bits))&((1L<<bits)-1));}
}
