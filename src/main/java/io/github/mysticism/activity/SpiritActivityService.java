package io.github.mysticism.activity;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.embedding.*;
import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.Vec384f;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.*;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded server-thread event adapter. No model invocation, waits, geometry hydration or chunk loads on tick. */
public final class SpiritActivityService {
    private static final int MAX_PLAYERS=64, MAX_EVENTS=64, MAX_JOBS=8;
    private static boolean initialized;
    private static final Map<MinecraftServer,Session> SESSIONS=new IdentityHashMap<>();
    private static final class Personal {
        final ActivityMath.Window window=new ActivityMath.Window();
        String dimension; CompletableFuture<Vec384f> pending;
        long nextSample;
        Personal(String dimension){this.dimension=dimension;}
    }
    private record Pulse(String dimension,BlockPos pos,UUID owner,Vec384f vector,String descriptor,double strength,boolean claim){}
    private record Job(Pulse pulse,CompletableFuture<Vec384f> future){}
    private static final class Session {
        final Map<UUID,Personal> players=new LinkedHashMap<>();
        final ArrayDeque<Pulse> events=new ArrayDeque<>();
        final ArrayDeque<Job> jobs=new ArrayDeque<>();
        LandmarkStore.PendingMutation mutation; Runnable committed; String mutationDimension;
        long skipped; int cursor;
        final LinkedHashMap<Region,String> nearbyCursors=new LinkedHashMap<>(16,0.75f,true);
        Pulse discovering; NearbyDiscovery discovery;CompletableFuture<Optional<LandmarkMetadata>> sourcePending;
        final MutationPause externalPause=new MutationPause();
    }
    /** Thread-confined pause state used by the real extractor/landmark tick gate. */
    static final class MutationPause {
        boolean held;
        Runnable acquire(boolean busy,java.util.function.BooleanSupplier onThread){
            if(!onThread.getAsBoolean())throw new IllegalStateException("activity server thread");
            if(held||busy)throw new IllegalStateException("activity landmark mutations busy/paused");
            held=true;
            return new Runnable(){boolean resumed;
                @Override public void run(){
                    if(!onThread.getAsBoolean())throw new IllegalStateException("activity server thread");
                    if(resumed)return;held=false;resumed=true;
                }
            };
        }
    }
    /** Acquire before any extractor prepare/staging; release on cancellation or after commit.
     * Does not cancel, finish or overwrite a pending activity write. Nested acquisition rejects. */
    public static Runnable pauseLandmarkMutations(MinecraftServer server){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        Session s=SESSIONS.computeIfAbsent(server,k->new Session());
        return s.externalPause.acquire(s.mutation!=null,server::isOnThread);
    }
    private record Region(String dimension,int x,int y,int z) {
        static Region of(Pulse pulse){return new Region(pulse.dimension,Math.floorDiv(pulse.pos.getX(),24),Math.floorDiv(pulse.pos.getY(),24),Math.floorDiv(pulse.pos.getZ(),24));}
    }
    /** The production incremental probe. One retained pulse, <=4 metadata reads per tick,
     * no catalogue list, one full circular pass at most, strict source radius after AABB gate. */
    static final class NearbyDiscovery {
        final String dimension,start; final Point3 point; final Bounds range;
        String cursor; boolean wrapped,done; int lastScanned;
        NearbyDiscovery(String dimension,BlockPos pos,String cursor){
            this.dimension=dimension;this.start=cursor;this.cursor=cursor;
            point=new Point3(pos.getX(),pos.getY(),pos.getZ());
            range=new Bounds(pos.getX()-24L,pos.getY()-24L,pos.getZ()-24L,pos.getX()+25L,pos.getY()+25L,pos.getZ()+25L);
        }
        Optional<LandmarkMetadata> advance(LandmarkStore store){
            lastScanned=0;if(done)return Optional.empty();
            var page=store.sourceRangePage(dimension,range,cursor,1,4);lastScanned=page.scanned();cursor=page.nextId();
            for(var metadata:page.landmarks()){
                if(wrapped&&start!=null&&metadata.id().compareTo(start)>0)break;
                if(ActivityMath.relevance(metadata.header().bounds().distanceSquared(point),24)>0){done=true;return Optional.of(metadata);}
            }
            if(wrapped&&start!=null&&cursor!=null&&cursor.compareTo(start)>=0)done=true;
            else if(page.end()){
                if(!wrapped&&start!=null){wrapped=true;cursor=null;}else done=true;
            }
            return Optional.empty();
        }
    }
    private SpiritActivityService(){}
    public static void init(){
        if(initialized)return;initialized=true;
        // Load overlays before terrain/activity ticks; no first-use overlay file load on tick.
        ServerLifecycleEvents.SERVER_STARTED.register(server->{LandmarkStore.get(server);LandmarkActivityState.get(server);SESSIONS.computeIfAbsent(server,k->new Session());});
        ServerTickEvents.END_SERVER_TICK.register(SpiritActivityService::tick);
        ServerWorldEvents.UNLOAD.register((server,world)->clearDimension(server,dimension(world)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{Session s=SESSIONS.remove(server);if(s!=null){cancelMutation(s);s.jobs.forEach(j->j.future.cancel(false));s.players.values().forEach(p->{if(p.pending!=null)p.pending.cancel(false);});if(s.sourcePending!=null)s.sourcePending.cancel(false);}});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->clearPlayer(server,handler.player.getUuid()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player,origin,destination)->clearPlayer(destination.getServer(),player.getUuid()));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer,newPlayer,alive)->clearPlayer(newPlayer.getServer(),newPlayer.getUuid()));
        PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,entity)->{if(player instanceof ServerPlayerEntity p && world instanceof ServerWorld w){
            String descriptor=block(state);personal(p).ifPresent(a->a.window.add(descriptor,3));
            enqueue(w,new Pulse(dimension(w),pos.toImmutable(),p.getUuid(),null,descriptor,0.2,true));
        }});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayerEntity p){
            enqueue(p.getServerWorld(),new Pulse(dimension(p.getServerWorld()),p.getBlockPos(),p.getUuid(),null,"player death loss survival",0.3,false));
        }});
    }
    private static String dimension(ServerWorld w){return w.getRegistryKey().getValue().toString();}
    static String block(BlockState state){return CanonicalDescriptors.block(Registries.BLOCK.getId(state.getBlock()).toString(),state.streamTags().map(t->t.id().toString()).limit(32).toList());}
    static String item(ItemStack stack){return CanonicalDescriptors.item(Registries.ITEM.getId(stack.getItem()).toString(),stack.streamTags().map(t->t.id().toString()).limit(32).toList());}
    private static Optional<Personal> personal(ServerPlayerEntity p){
        MinecraftServer server=p.getServer();if(server==null||!server.isOnThread())return Optional.empty();
        Session s=SESSIONS.computeIfAbsent(server,k->new Session());Personal a=s.players.get(p.getUuid());
        String dim=dimension(p.getServerWorld());
        if(a!=null&&!dim.equals(a.dimension)){clearPlayer(server,p.getUuid());a=null;}
        if(a==null&&s.players.size()<MAX_PLAYERS){a=new Personal(dim);s.players.put(p.getUuid(),a);}
        return Optional.ofNullable(a);
    }
    public static void clearPlayer(MinecraftServer server,UUID id){Session s=SESSIONS.get(server);if(s!=null){Personal a=s.players.remove(id);if(a!=null&&a.pending!=null)a.pending.cancel(false);s.events.removeIf(e->id.equals(e.owner));s.jobs.removeIf(j->{if(id.equals(j.pulse.owner)){j.future.cancel(false);return true;}return false;});if(s.discovering!=null&&id.equals(s.discovering.owner)){s.discovering=null;s.discovery=null;if(s.sourcePending!=null){s.sourcePending.cancel(false);s.sourcePending=null;}}}}
    private static void clearDimension(MinecraftServer server,String dimension){
        Session s=SESSIONS.get(server);if(s==null)return;
        for(var id:List.copyOf(s.players.keySet()))if(s.players.get(id).dimension.equals(dimension))clearPlayer(server,id);
        s.events.removeIf(e->e.dimension.equals(dimension));
        s.jobs.removeIf(j->{if(j.pulse.dimension.equals(dimension)){j.future.cancel(false);return true;}return false;});
        s.nearbyCursors.keySet().removeIf(k->k.dimension.equals(dimension));
        if(s.discovering!=null&&s.discovering.dimension.equals(dimension)){s.discovering=null;s.discovery=null;if(s.sourcePending!=null){s.sourcePending.cancel(false);s.sourcePending=null;}}
        if(dimension.equals(s.mutationDimension))cancelMutation(s);
    }
    private static void cancelMutation(Session s){
        if(s.mutation!=null&&!s.mutation.complete())s.mutation.cancel();
        s.mutation=null;s.committed=null;s.mutationDimension=null;
    }
    private static void enqueue(ServerWorld world,Pulse pulse){
        if(!world.getServer().isOnThread()||!io.github.mysticism.landmark.extract.SourceDimensions.isSource(pulse.dimension))return;
        Session s=SESSIONS.computeIfAbsent(world.getServer(),k->new Session());
        if(s.events.size()<MAX_EVENTS)s.events.add(pulse);else s.skipped++;
    }
    /** Called only after BlockItem has successfully placed the actual requested block. */
    public static void placed(ServerPlayerEntity player,BlockPos pos,BlockState actual){
        String d=block(actual);personal(player).ifPresent(a->a.window.add(d,4));
        enqueue(player.getServerWorld(),new Pulse(dimension(player.getServerWorld()),pos.toImmutable(),player.getUuid(),null,d,0.25,true));
    }
    /** Called from successful ServerWorld.spawnEntity; entity loading does not count as a spawn. */
    public static void spawned(ServerWorld world,Entity entity){if(entity instanceof MobEntity && !entity.isRemoved()){
        String d=CanonicalDescriptors.describe("mob",Registries.ENTITY_TYPE.getId(entity.getType()).toString(),List.of());
        enqueue(world,new Pulse(dimension(world),entity.getBlockPos(),null,null,d,0.05,false));
    }}
    /** Actual StatHandler delta, captured after vanilla increment. No cumulative counter replay. */
    public static void statDelta(ServerPlayerEntity player,Stat<?> stat,int delta){
        if(delta<=0)return;String descriptor=statDescription(stat);
        if(descriptor!=null){String d=descriptor;personal(player).ifPresent(a->a.window.add(d,Math.min(16,delta)));}
    }
    static String statDescription(Stat<?> stat){
        String descriptor=null;var type=stat.getType();Object value=stat.getValue();
        if(type==Stats.MINED && value instanceof net.minecraft.block.Block b) descriptor=block(b.getDefaultState());
        else if(type==Stats.USED && value instanceof net.minecraft.item.Item i) descriptor=item(new ItemStack(i));
        else if(type==Stats.CUSTOM && (value.equals(Stats.DEATHS)||value.equals(Stats.MOB_KILLS)||value.equals(Stats.PLAYER_KILLS))) descriptor="player statistics "+CanonicalDescriptors.words(value.toString());
        return descriptor;
    }
    public static LandmarkEmbedding effectiveEmbedding(MinecraftServer server,LandmarkMetadata landmark){
        LandmarkEmbedding base=landmark.header().baseEmbedding();
        var influence=LandmarkActivityState.get(server).entries.get(landmark.id());
        if(influence==null)return new LandmarkEmbedding(base.profile(),base.vector());
        LandmarkProfiles.current().requireCompatible(base.profile());
        return LandmarkProfiles.wrap(influence.vector);
    }
    public static double importance(MinecraftServer server,LandmarkMetadata landmark){
        return LandmarkMerge.importance(server,landmark);
    }
    // Merges are extractor-owned: LandmarkMerge.prepare(server, realProof), then core
    // stageMerge with reconciled observed geometry, Plan.commit only after core completion.
    // No independent convenience merge can bypass transactional history conservation.
    /** Discard only derived landmark overlays alongside a disposable old source catalogue. */
    public static void discardGeneratedInfluences(MinecraftServer server){if(!server.isOnThread())throw new IllegalStateException("activity server thread");var state=LandmarkActivityState.get(server);for(var id:List.copyOf(state.entries.keySet()))state.remove(id);}
    public static long skipped(MinecraftServer server){Session s=SESSIONS.get(server);return s==null?0:s.skipped;}
    private static void tick(MinecraftServer server){
        Session s=SESSIONS.computeIfAbsent(server,k->new Session());
        // Dimension exits and disconnects invalidate in-flight observations even between sample cadences.
        for(var id:List.copyOf(s.players.keySet())){var p=server.getPlayerManager().getPlayer(id);if(p==null||!dimension(p.getServerWorld()).equals(s.players.get(id).dimension))clearPlayer(server,id);}
        if(!s.externalPause.held){
            if(s.mutation!=null){try{s.mutation.advance(1,256);if(s.mutation.complete()){s.committed.run();s.mutation=null;s.committed=null;s.mutationDimension=null;}}catch(RuntimeException stale){cancelMutation(s);s.skipped++;}}
            if(s.mutation==null&&s.sourcePending!=null)advanceDiscovery(server,s);
        }
        if(server.getTicks()%20!=0)return;
        List<ServerPlayerEntity> online=server.getPlayerManager().getPlayerList();
        // At most four inventory samples per second, independent of player count.
        for(int n=0;n<Math.min(4,online.size());n++){
            ServerPlayerEntity p=online.get(Math.floorMod(s.cursor++,online.size()));
            personal(p).ifPresent(a->sample(server,p,a,s));
        }
        // Personal sampling above may enqueue bounded dwell; do not drain landmark jobs or
        // events while an extractor owns the pause. Completed futures retain <=8 slots.
        if(s.externalPause.held)return;
        // Poll only completed futures; getNow never joins or waits.
        int jobs=s.jobs.size();for(int n=0;n<jobs;n++){Job j=s.jobs.remove();if(!j.future.isDone()){s.jobs.add(j);continue;}
            if(j.future.isCompletedExceptionally()||j.future.isCancelled()){s.skipped++;continue;}
            Vec384f v=j.future.getNow(null);if(v!=null)enqueueVector(s,j.pulse,v);
        }
        if(s.mutation==null&&s.sourcePending==null&&!s.events.isEmpty()){
            Pulse e=s.events.remove();
            if(e.vector==null){if(EmbeddingHelper.isReady()&&s.jobs.size()<MAX_JOBS)s.jobs.add(new Job(e,EmbeddingHelper.getEmbedding(e.descriptor)));else s.skipped++;}
            else {s.discovering=e;s.sourcePending=SourceLandmarks.ensureSourceLocation(server,e.dimension,e.pos);}
        }
    }
    private static void enqueueVector(Session s,Pulse e,Vec384f v){if(s.events.size()<MAX_EVENTS)s.events.addFirst(new Pulse(e.dimension,e.pos,e.owner,v.clone(),null,e.strength,e.claim));else s.skipped++;}
    private static void sample(MinecraftServer server,ServerPlayerEntity p,Personal a,Session s){
        if(a.pending!=null&&a.pending.isDone()){
            if(!a.pending.isCompletedExceptionally()&&!a.pending.isCancelled()){Vec384f v=a.pending.getNow(null);if(v!=null){
                var att=p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);att.observe(v);
                // Initialize the existing semantic position once, before any realm projection exists.
                // Never move a physical player or re-key an established projection.
                if(!a.dimension.equals("mysticism:spirit")&&p.getComponent(MysticismEntityComponents.LATENT_POS).get().length()==0)
                    MysticismEntityComponents.setLatentPos(p,att.get());
            }}
            a.pending=null;
        }
        // Fixed 36+offhand slots, not registry/entity/world enumeration. Counts do not amplify dwell.
        Set<String> inventory=new TreeSet<>();for(int i=0;i<Math.min(41,p.getInventory().size());i++){ItemStack stack=p.getInventory().getStack(i);if(!stack.isEmpty())inventory.add(item(stack));}
        inventory.stream().limit(8).forEach(d->a.window.add(d,0.25));
        sampleChests(p);
        if(!p.getMainHandStack().isEmpty())a.window.add(item(p.getMainHandStack()),2);
        long now=server.getOverworld().getTime();
        if(now<a.nextSample)return;a.nextSample=now+200;
        Vec384f personal=p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).personal();
        if(personal.length()>0)enqueue(p.getServerWorld(),new Pulse(dimension(p.getServerWorld()),p.getBlockPos(),p.getUuid(),personal,null,0.1,false));
        if(a.pending==null&&EmbeddingHelper.isReady()&&s.players.values().stream().filter(x->x.pending!=null).count()<4){
            var window=a.window.take();if(!window.isEmpty()){
                a.pending=EmbeddingHelper.composeDescriptors(window.entrySet().stream()
                        .map(entry->new DescriptorVectors.WeightedDescriptor(entry.getKey(),entry.getValue())).toList());
            }
        }
        MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p);
    }
    /** Bounded real chest contents: <=50 loaded block-entity probes, two chests and 16 slots
     * each per sampled player/second. Never tickets, nearby entity scans or off-thread stacks. */
    private static void sampleChests(ServerPlayerEntity player){
        var world=player.getServerWorld();if(!io.github.mysticism.landmark.extract.SourceDimensions.isSource(dimension(world)))return;
        BlockPos base=player.getBlockPos();int chests=0;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=0;dy++){
            BlockPos pos=base.add(dx,dy,dz);var chunk=world.getChunkManager().getWorldChunk(pos.getX()>>4,pos.getZ()>>4);if(chunk==null)continue;
            if(chunk.getBlockEntity(pos) instanceof net.minecraft.block.entity.ChestBlockEntity chest){
                Map<String,Double> items=new TreeMap<>();for(int slot=0;slot<Math.min(16,chest.size());slot++){var stack=chest.getStack(slot);if(!stack.isEmpty())items.merge(item(stack),Math.sqrt(Math.min(64,stack.getCount())),Double::sum);}
                if(!items.isEmpty()){
                    String description="stored chest contents "+String.join(", ",items.keySet().stream().limit(8).toList());
                    enqueue(world,new Pulse(dimension(world),pos.toImmutable(),null,null,description,.05,false));
                }
                if(++chests==2)return;
            }
        }
    }
    private static void advanceDiscovery(MinecraftServer server,Session s){
        LandmarkStore store=LandmarkStore.get(server);
        try {
            if(!s.sourcePending.isDone())return;
            var found=s.sourcePending.getNow(Optional.empty());s.sourcePending=null;
            if(found.isEmpty()){s.discovering=null;return;}
            Pulse e=s.discovering;var h=found.orElseThrow().header();
            // Remember a source-local ID seek, not a list of every overlap. Repeat pulses
            // traverse all eligible overlaps even when local/world catalogues exceed old caps.
            s.nearbyCursors.put(Region.of(e),h.id());
            while(s.nearbyCursors.size()>64)s.nearbyCursors.remove(s.nearbyCursors.keySet().iterator().next());
            s.discovery=null;s.discovering=null;
            LandmarkProfiles.current().requireCompatible(h.baseEmbedding().profile());
            LandmarkActivityState state=LandmarkActivityState.get(server);
            var old=state.entries.get(h.id());
            var reduced=LandmarkInfluence.reduce(h,old,e.vector,new BlockPoint(e.pos.getX(),e.pos.getY(),e.pos.getZ()),e.owner,e.strength,e.claim,
                    e.owner==null?0:state.claimedBy(e.owner),server.getOverworld().getTime());
            s.mutation=store.stageActivity(new LandmarkRepository.RevisionRef(h.id(),h.revision()),reduced.activity(),reduced.ownership());
            s.mutationDimension=e.dimension;s.committed=()->{state.publish(h.id(),reduced.influence());SourceLandmarks.activity(server,h.id(),e.dimension,e.pos);};
        }catch(RuntimeException budgetOrBusy){s.discovery=null;s.discovering=null;if(s.sourcePending!=null)s.sourcePending.cancel(false);s.sourcePending=null;s.skipped++;}
    }
}
