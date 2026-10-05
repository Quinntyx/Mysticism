package io.github.mysticism.world.region;

import io.github.mysticism.embedding.*;
import io.github.mysticism.world.region.impl.BiomeSpiritualRegion;
import io.github.mysticism.world.state.SpatialEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.*;
import org.slf4j.*;
import java.util.*;

/** Resumable, loaded-only biome observation. Budget is CHUNK LOOKUPS, never 1024-chunk regions.
 * Samples chunk-center/sea-level biomes, not caves or representative semantic clustering.
 */
public final class HorizonSeeder {
    private static final Logger LOGGER=LoggerFactory.getLogger("Mysticism-HorizonSeeder");
    private static final int MAX_QUEUE=1024,MAX_PENDING=32,MAX_LOOKUPS=64,RADIUS_REGIONS=4;
    public static final double FILL_TARGET=.95,FILL_MIN=.85;
    private static final Deque<Work> QUEUE=new ArrayDeque<>();
    private static final Set<String> ENQUEUED=new HashSet<>(),PENDING=new HashSet<>();
    private static final Map<String,Integer> LAST_SCAN=new LinkedHashMap<>();
    private static boolean registered;
    private static long epoch;
    private static int chunkBudget=16;
    private static final class Work {
        final ServerWorld world;final int rx,rz;final String id;int cursor;
        Work(ServerWorld world,int rx,int rz){this.world=world;this.rx=rx;this.rz=rz;this.id=key(world,rx,rz);}
    }
    private HorizonSeeder(){}
    public static int getQueueSize(){return QUEUE.size();}
    /** Historical command name; the returned budget is now loaded-chunk checks per tick. */
    public static int getRegionsPerTick(){return chunkBudget;}
    public static int getMaxQueue(){return MAX_QUEUE;}
    public static boolean isQueueSaturated(){return QUEUE.size()>=MAX_QUEUE;}
    public static boolean isBusy(){return !QUEUE.isEmpty()||!PENDING.isEmpty();}
    public static double getMspt(MinecraftServer server){return server.getAverageNanosPerTick()/1_000_000.0;}
    public static double getTargetMspt(MinecraftServer server){return 1000.0/Math.max(1,server.getTickManager().getTickRate());}
    public static double getLagRatio(MinecraftServer server){return getMspt(server)/getTargetMspt(server);}
    /** Bounded diagnostic sample (at most 64 loaded checks), not an exhaustive view-distance scan. */
    public static double getWorstChunkFill(MinecraftServer server){
        int remaining=64;double worst=1;
        for(var player:server.getPlayerManager().getPlayerList()){
            int loaded=0,total=0;var pos=player.getChunkPos();
            for(int z=-2;z<=2&&remaining>0;z++)for(int x=-2;x<=2&&remaining>0;x++){
                remaining--;total++;if(player.getServerWorld().getChunkManager().getWorldChunk(pos.x+x,pos.z+z)!=null)loaded++;
            }
            if(total>0)worst=Math.min(worst,loaded/(double)total);if(remaining==0)break;
        }
        return worst;
    }
    public static double chunkFillHeadroom(MinecraftServer server){return Math.clamp((getWorstChunkFill(server)-FILL_MIN)/(FILL_TARGET-FILL_MIN),0,1);}
    public static double getChunkFillHeadroom(MinecraftServer server){return chunkFillHeadroom(server);}
    public static void register(){
        if(registered)return;registered=true;
        ServerTickEvents.END_SERVER_TICK.register(HorizonSeeder::tick);
        ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->enqueue(world,chunk.getPos().x>>5,chunk.getPos().z>>5,true));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{epoch++;QUEUE.clear();ENQUEUED.clear();PENDING.clear();LAST_SCAN.clear();chunkBudget=16;});
    }
    private static void enqueue(ServerWorld world,int rx,int rz,boolean loadedEvent){
        String id=key(world,rx,rz);
        if(QUEUE.size()>=MAX_QUEUE||ENQUEUED.contains(id))return;
        if(!loadedEvent&&world.getServer().getTicks()-LAST_SCAN.getOrDefault(id,-1000)<200)return;
        ENQUEUED.add(id);QUEUE.addLast(new Work(world,rx,rz));
    }
    private static void tick(MinecraftServer server){
        if(!EmbeddingHelper.isReady())return;
        var state=SpatialEmbeddingIndexState.get(server);if(state.needsRebuild())return;
        var players=server.getPlayerManager().getPlayerList();
        // One player's 81 region candidates per tick, rotating deterministically for fairness.
        if(!players.isEmpty()){
            var p=players.get(Math.floorMod(server.getTicks(),players.size()));var center=p.getChunkPos();
            for(int z=-RADIUS_REGIONS;z<=RADIUS_REGIONS;z++)for(int x=-RADIUS_REGIONS;x<=RADIUS_REGIONS;x++)enqueue(p.getServerWorld(),(center.x>>5)+x,(center.z>>5)+z,false);
        }
        if(server.getTicks()%40==0){
            double head=Math.min(Math.clamp((.8-getLagRatio(server))/.8,0,1),chunkFillHeadroom(server));
            chunkBudget=Math.max(2,Math.min(MAX_LOOKUPS,2+(int)(62*head*(server.isDedicated()?1:.75))));
        }
        for(int checks=0;checks<chunkBudget&&!QUEUE.isEmpty()&&PENDING.size()<MAX_PENDING;checks++){
            Work work=QUEUE.removeFirst();int local=work.cursor++;int x=(work.rx<<5)+(local&31),z=(work.rz<<5)+(local>>5);
            // Completed chunks only: getChunk(FULL,false) can still join ticketed generation.
            WorldChunk chunk=work.world.getChunkManager().getWorldChunk(x,z);
            if(chunk!=null)observe(work,chunk,x,z,state);
            if(work.cursor<1024)QUEUE.addLast(work);
            else{ENQUEUED.remove(work.id);LAST_SCAN.put(work.id,server.getTicks());if(LAST_SCAN.size()>4096)LAST_SCAN.remove(LAST_SCAN.keySet().iterator().next());}
        }
    }
    private static void observe(Work work,Chunk chunk,int x,int z,SpatialEmbeddingIndexState state){
        var biome=chunk.getBiomeForNoiseGen(((x<<4)+8)>>2,work.world.getSeaLevel()>>2,((z<<4)+8)>>2);
        if(biome.getKey().isEmpty())return;
        var bid=biome.getKey().get().getValue();String id=work.id+"|"+bid;
        ChunkBox box=new ChunkBox(x&31,z&31,x&31,z&31);
        var prior=state.getRegion(id);
        if(prior instanceof BiomeSpiritualRegion old && old.boxes().stream().anyMatch(b->b.minX()<=box.minX()&&b.maxX()>=box.maxX()&&b.minZ()<=box.minZ()&&b.maxZ()>=box.maxZ()))return;
        String pending=id+"|"+x+","+z;if(!PENDING.add(pending))return;
        String descriptor=CanonicalDescriptors.region(work.world.getRegistryKey().getValue().toString(),bid.toString(),biome.streamTags().map(t->t.id().toString()).toList());
        long generation=epoch;MinecraftServer server=work.world.getServer();
        EmbeddingHelper.getEmbedding(descriptor).whenComplete((vector,error)->{
            if(!server.isRunning()||generation!=epoch)return;
            server.execute(()->{
                if(generation!=epoch)return;PENDING.remove(pending);
                if(error!=null){LOGGER.debug("Region embedding failed for {}",id,error);return;}
                if(!state.needsRebuild())state.observeBiome(id,new BiomeSpiritualRegion(work.rx,work.rz,bid,List.of(box)),descriptor,vector);
            });
        });
    }
    private static String key(ServerWorld world,int rx,int rz){return world.getRegistryKey().getValue()+"|vregion|"+rx+","+rz;}
}
