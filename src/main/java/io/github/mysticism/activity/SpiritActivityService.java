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
    private static final ImportancePolicy POLICY=LandmarkInfluence.POLICY;
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
        LandmarkStore.PendingMutation mutation; Runnable committed;
        long skipped; int cursor,landmarkCursor;
    }
    private SpiritActivityService(){}
    public static void init(){
        if(initialized)return;initialized=true;
        ServerTickEvents.END_SERVER_TICK.register(SpiritActivityService::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{Session s=SESSIONS.remove(server);if(s!=null){if(s.mutation!=null)s.mutation.cancel();s.jobs.forEach(j->j.future.cancel(false));s.players.values().forEach(p->{if(p.pending!=null)p.pending.cancel(false);});}});
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
    public static void clearPlayer(MinecraftServer server,UUID id){Session s=SESSIONS.get(server);if(s!=null){Personal a=s.players.remove(id);if(a!=null&&a.pending!=null)a.pending.cancel(false);s.events.removeIf(e->id.equals(e.owner));s.jobs.removeIf(j->{if(id.equals(j.pulse.owner)){j.future.cancel(false);return true;}return false;});}}
    private static void enqueue(ServerWorld world,Pulse pulse){
        if(!world.getServer().isOnThread()||pulse.dimension.equals("mysticism:spirit"))return;
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
        var influence=LandmarkActivityState.get(server).entries.get(landmark.id());
        double now=influence==null?0:influence.level(server.getOverworld().getTime());
        return Math.min(1,Math.max(0,landmark.header().baseImportance()+now));
    }
    /** Extractor-supplied physical proof only. Semantic affinity is an extra gate, never connectivity evidence.
     * Returns whether the bounded transaction was staged, not whether it is already committed.
     */
    public static boolean tryVerifiedLocalMerge(MinecraftServer server,LandmarkRepository.VerifiedConnectivity proof){
        if(!server.isOnThread())throw new IllegalStateException("activity server thread");
        if(proof.fragments().size()<2||proof.fragments().size()>8)return false;
        Session s=SESSIONS.computeIfAbsent(server,k->new Session());if(s.mutation!=null)return false;
        LandmarkStore store=LandmarkStore.get(server);LandmarkActivityState state=LandmarkActivityState.get(server);
        try{
            List<LandmarkMetadata> fragments=new ArrayList<>();
            for(var ref:proof.fragments()){var m=store.metadata(ref.id()).orElseThrow();if(m.revision()!=ref.revision())return false;fragments.add(m);}
            fragments.sort(Comparator.comparing(LandmarkMetadata::id));var seed=fragments.getFirst();
            for(var m:fragments)if(!m.header().dimension().equals(seed.header().dimension())||!m.header().biome().equals(seed.header().biome())
                    ||m.header().kind()!=seed.header().kind()||m.header().bounds().distanceSquared(seed.header().bounds().center())>64*64
                    ||effectiveEmbedding(server,m).distanceSquared(effectiveEmbedding(server,seed))>0.04)return false;
            List<Vec384f> vectors=new ArrayList<>();List<Double> weights=new ArrayList<>();double level=0;long now=server.getOverworld().getTime();
            var combined=new LandmarkActivityState.Influence(seed.header().baseEmbedding().vector(),0,now);
            for(var m:fragments){var v=state.entries.get(m.id());vectors.add(effectiveEmbedding(server,m).vector());weights.add(Math.max(0.01,importance(server,m)));
                if(v!=null){level=Math.max(level,v.level(now));combined.owners.addAll(v.owners);
                    if(v.claims!=null)for(var cell:v.claims.cells(LandmarkActivityState.CELL_LIMIT)){
                        if(combined.claims==null)combined.claims=SparseOctree.empty(v.claims.rootBounds(),1,512);
                        combined.claims=combined.claims.with(cell.bounds(),cell.value(),256);combined.claims.cells(LandmarkActivityState.CELL_LIMIT);
                    }
                }
            }
            if(combined.owners.size()>16||state.entries.size()>=LandmarkActivityState.LIMIT&&!state.entries.containsKey(seed.id()))return false;
            combined.vector=ActivityMath.weighted(vectors,weights);combined.level=Math.min(0.35,level);
            s.mutation=store.stageMerge(proof,now,POLICY);
            s.committed=()->{fragments.forEach(m->state.remove(m.id()));state.publish(seed.id(),combined);};return true;
        }catch(IllegalArgumentException|IllegalStateException invalidOrBusy){s.skipped++;return false;}
    }
    public static long skipped(MinecraftServer server){Session s=SESSIONS.get(server);return s==null?0:s.skipped;}
    private static void tick(MinecraftServer server){
        Session s=SESSIONS.computeIfAbsent(server,k->new Session());
        // Dimension exits and disconnects invalidate in-flight observations even between sample cadences.
        for(var id:List.copyOf(s.players.keySet())){var p=server.getPlayerManager().getPlayer(id);if(p==null||!dimension(p.getServerWorld()).equals(s.players.get(id).dimension))clearPlayer(server,id);}
        if(s.mutation!=null){try{s.mutation.advance(1,256);if(s.mutation.complete()){s.committed.run();s.mutation=null;s.committed=null;}}catch(RuntimeException stale){s.mutation.cancel();s.mutation=null;s.committed=null;s.skipped++;}}
        if(server.getTicks()%20!=0)return;
        List<ServerPlayerEntity> online=server.getPlayerManager().getPlayerList();
        // At most four inventory samples per second, independent of player count.
        for(int n=0;n<Math.min(4,online.size());n++){
            ServerPlayerEntity p=online.get(Math.floorMod(s.cursor++,online.size()));
            personal(p).ifPresent(a->sample(server,p,a,s));
        }
        // Poll only completed futures; getNow never joins or waits.
        int jobs=s.jobs.size();for(int n=0;n<jobs;n++){Job j=s.jobs.remove();if(!j.future.isDone()){s.jobs.add(j);continue;}
            if(j.future.isCompletedExceptionally()||j.future.isCancelled()){s.skipped++;continue;}
            Vec384f v=j.future.getNow(null);if(v!=null)enqueueVector(s,j.pulse,v);
        }
        if(s.mutation==null&&!s.events.isEmpty()){
            Pulse e=s.events.remove();
            if(e.vector==null){if(EmbeddingHelper.isReady()&&s.jobs.size()<MAX_JOBS)s.jobs.add(new Job(e,EmbeddingHelper.getEmbedding(e.descriptor)));else s.skipped++;}
            else apply(server,s,e);
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
    private static void apply(MinecraftServer server,Session s,Pulse e){
        LandmarkStore store=LandmarkStore.get(server);LandmarkActivityState state=LandmarkActivityState.get(server);
        Bounds range=new Bounds(e.pos.getX()-24L,e.pos.getY()-24L,e.pos.getZ()-24L,e.pos.getX()+25L,e.pos.getY()+25L,e.pos.getZ()+25L);
        try {
            // Core rejects catalogs >128 BEFORE enumeration. Never an unbounded catalog scan.
            var nearby=store.sourceRange(e.dimension,range,8,128);
            for(int offset=0;offset<nearby.size();offset++){
                var metadata=nearby.get(Math.floorMod(s.landmarkCursor+offset,nearby.size()));
                var h=metadata.header();LandmarkProfiles.current().requireCompatible(h.baseEmbedding().profile());double relevance=ActivityMath.relevance(h.bounds().distanceSquared(new Point3(e.pos.getX(),e.pos.getY(),e.pos.getZ())),24);
                if(relevance==0)continue;
                var old=state.entries.get(h.id());if(old==null&&state.entries.size()>=LandmarkActivityState.LIMIT){s.skipped++;continue;}
                long now=server.getOverworld().getTime();
                var reduced=LandmarkInfluence.reduce(h,old,e.vector,new BlockPoint(e.pos.getX(),e.pos.getY(),e.pos.getZ()),e.owner,e.strength,e.claim,
                        e.owner==null?0:state.claimedBy(e.owner),now);
                s.mutation=store.stageActivity(new LandmarkRepository.RevisionRef(h.id(),h.revision()),reduced.activity(),reduced.ownership());
                s.committed=()->state.publish(h.id(),reduced.influence());
                // Rotate the bounded nearby set: repeat dwell reaches overlapping cave/biome regions,
                // never a catalog-global shop district. Each pulse consumes at most one transaction.
                s.landmarkCursor++;break;
            }
        }catch(IllegalArgumentException|IllegalStateException budgetOrBusy){s.skipped++;}
    }
}
