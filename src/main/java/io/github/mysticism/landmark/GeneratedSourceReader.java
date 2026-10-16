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
    /** Owner-thread adapter; saved rows are detached generated-only observations. */
    interface Source {
        LiveChunk loaded(ChunkPos position);
        CompletableFuture<List<SourceLandmarks.Cell>> saved(ChunkPos position, Bounds bounds);
    }
    record LiveChunk(Object identity, java.util.function.Function<BlockPoint,SourceLandmarks.Cell> sample) {}
    final String dimension; final Bounds bounds; final ServerWorld world; final Source source;
    Object liveIdentity;
    final SourceLandmarks.Cell[] cells; final List<ChunkPos> chunks=new ArrayList<>();
    final Executor executor; int chunkIndex,cellIndex,lastSampled; boolean cancelled;
    CompletableFuture<List<SourceLandmarks.Cell>> disk;List<SourceLandmarks.Cell> diskRows;int diskCursor;
    GeneratedSourceReader(ServerWorld world,Bounds bounds,Executor executor) {
        this(world,world.getRegistryKey().getValue().toString(),bounds,executor,source(world,executor));
    }
    GeneratedSourceReader(String dimension,Bounds bounds,Executor executor,Source source) {
        this(null,dimension,bounds,executor,source);
    }
    private GeneratedSourceReader(ServerWorld world,String dimension,Bounds bounds,Executor executor,Source source) {
        this.world=world;this.dimension=dimension;this.bounds=bounds;this.executor=executor;this.source=source;
        long volume=Math.multiplyExact(Math.multiplyExact(bounds.maxX()-bounds.minX(),bounds.maxY()-bounds.minY()),bounds.maxZ()-bounds.minZ());
        if(volume>32768)throw new IllegalArgumentException("source region volume");
        cells=new SourceLandmarks.Cell[(int)volume];
        for(int x=Math.floorDiv((int)bounds.minX(),16);x<=Math.floorDiv((int)bounds.maxX()-1,16);x++)
            for(int z=Math.floorDiv((int)bounds.minZ(),16);z<=Math.floorDiv((int)bounds.maxZ()-1,16);z++)chunks.add(new ChunkPos(x,z));
        if(chunks.size()>16)throw new IllegalArgumentException("source chunk IO budget");
    }
    boolean advance(int budget) {
        lastSampled=0;if(cancelled)throw new CancellationException();
        if(chunkIndex==chunks.size())return true;
        ChunkPos cp=chunks.get(chunkIndex);
        LiveChunk live=source.loaded(cp);
        // A reloaded chunk is authoritative, even while an old/absent disk scan is pending.
        // Start its slice again: never combine partial observations from different adapters.
        if(live!=null && (disk!=null || liveIdentity!=null && liveIdentity!=live.identity())) {
            discardCurrentSamples(cp);
            if(disk!=null)disk.cancel(false);
            disk=null;diskRows=null;diskCursor=0;cellIndex=0;
        }
        if(disk!=null) {
            if(!disk.isDone())return false;if(diskRows==null)diskRows=disk.getNow(List.of());
            int end=Math.min(diskRows.size(),diskCursor+budget);
            while(diskCursor<end){lastSampled++;var cell=diskRows.get(diskCursor++);cells[index(bounds,cell.position())]=cell;}
            if(diskCursor==diskRows.size()){disk=null;diskRows=null;diskCursor=0;chunkIndex++;cellIndex=0;liveIdentity=null;}
            return chunkIndex==chunks.size();
        }
        if(live==null) {
            // Saved data may lack sections/cells. Partial live rows cannot prove those missing
            // cells: discard them before merging the detached saved observation.
            discardCurrentSamples(cp);cellIndex=0;liveIdentity=null;
            disk=source.saved(cp,bounds);
            return false;
        }
        liveIdentity=live.identity();
        Bounds slice=slice(cp);
        int volume=(int)((slice.maxX()-slice.minX())*(slice.maxY()-slice.minY())*(slice.maxZ()-slice.minZ()));int sampled=0;long deadline=System.nanoTime()+1_500_000L;
        while(cellIndex<volume && sampled<budget && (sampled==0||System.nanoTime()<deadline)) {
            BlockPoint p=point(slice,cellIndex++);int i=index(bounds,p);sampled++;lastSampled++;
            cells[i]=live.sample().apply(p);
        }
        if(cellIndex==volume){chunkIndex++;cellIndex=0;liveIdentity=null;}
        return chunkIndex==chunks.size();
    }
    private Bounds slice(ChunkPos cp) {
        return new Bounds(Math.max(bounds.minX(),cp.getStartX()),bounds.minY(),Math.max(bounds.minZ(),cp.getStartZ()),Math.min(bounds.maxX(),cp.getStartX()+16L),bounds.maxY(),Math.min(bounds.maxZ(),cp.getStartZ()+16L));
    }
    private void discardCurrentSamples(ChunkPos cp) {
        Bounds slice=slice(cp);
        // Clear contiguous x-runs without allocating/visiting cells in other chunks.
        for(long y=slice.minY();y<slice.maxY();y++)for(long z=slice.minZ();z<slice.maxZ();z++) {
            int start=index(bounds,new BlockPoint(slice.minX(),y,z));
            Arrays.fill(cells,start,start+(int)(slice.maxX()-slice.minX()),null);
        }
    }
    private static Source source(ServerWorld world,Executor executor) {
        return new Source() {
            public LiveChunk loaded(ChunkPos cp) {
                WorldChunk chunk=world.getChunkManager().getWorldChunk(cp.x,cp.z);
                if(chunk==null)return null;
                return new LiveChunk(chunk,p->{
                    if(p.y()<world.getBottomY() || p.y()>=world.getTopY())return null;
                    BlockPos pos=new BlockPos((int)p.x(),(int)p.y(),(int)p.z());var state=chunk.getBlockState(pos);
                    Map<String,String> properties=new TreeMap<>();state.getEntries().forEach((key,value)->properties.put(key.getName(),name(key,value)));
                    String biome=chunk.getBiomeForNoiseGen(pos.getX()>>2,pos.getY()>>2,pos.getZ()>>2).getKey().map(k->k.getValue().toString()).orElse(null);
                    return biome==null?null:new SourceLandmarks.Cell(p,new BlockPalette.State(Registries.BLOCK.getId(state.getBlock()).toString(),properties),biome,
                        p.y()>chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,pos.getX()&15,pos.getZ()&15));
                });
            }
            public CompletableFuture<List<SourceLandmarks.Cell>> saved(ChunkPos cp,Bounds bounds) {
                // Capture world dimensions on server thread, no world/registry access on worker.
                int bottom=world.getBottomY(),height=world.getHeight();var scanner=new LimitedCollector();
                return world.getChunkManager().getChunkIoWorker().scanChunk(cp,scanner)
                    .thenApplyAsync(v->Arrays.stream(decode(scanner.getRoot(),cp,bounds,bottom,height)).filter(Objects::nonNull).toList(),executor);
            }
        };
    }
    CompletableFuture<SourceLandmarks.Region> resultAsync(){var copy=cells.clone();return CompletableFuture.supplyAsync(()->new SourceLandmarks.Region(dimension,bounds,Arrays.stream(copy).filter(Objects::nonNull).toList(),Arrays.stream(copy).allMatch(Objects::nonNull)),executor);}
    private static int index(Bounds b,BlockPoint p){return (int)((p.x()-b.minX())+(b.maxX()-b.minX())*((p.z()-b.minZ())+(b.maxZ()-b.minZ())*(p.y()-b.minY())));}
    void cancel(){cancelled=true;if(disk!=null)disk.cancel(false);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static String name(Property p,Comparable value){return p.name(value);}
    static BlockPoint point(Bounds b,int i){long dx=b.maxX()-b.minX(),dz=b.maxZ()-b.minZ();return new BlockPoint(b.minX()+i%dx,b.minY()+i/(dx*dz),b.minZ()+(i/dx)%dz);}
    private static final class LimitedCollector extends SelectiveNbtCollector {
        private long words,characters,nodes;
        LimitedCollector(){super(new NbtScanQuery(NbtString.TYPE,"Status"),new NbtScanQuery(NbtList.TYPE,"sections"),new NbtScanQuery(NbtCompound.TYPE,"Heightmaps"));}
        @Override public NbtScanner.Result visitListMeta(NbtType<?> type,int length){if(length>4096||(nodes+=length)>65536)throw new IllegalArgumentException("chunk NBT list budget");return super.visitListMeta(type,length);}
        @Override public NbtScanner.Result visitLongArray(long[] values){if(values.length>4096||(words+=values.length)>524288)throw new IllegalArgumentException("chunk packed array budget");return super.visitLongArray(values);}
        @Override public NbtScanner.Result visitString(String value){if(value.length()>1024||(characters+=value.length())>4194304)throw new IllegalArgumentException("chunk descriptor budget");return super.visitString(value);}
    }
    static SourceLandmarks.Cell[] decode(NbtElement root,ChunkPos cp,Bounds bounds,int bottom,int height) {
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
