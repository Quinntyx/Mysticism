package io.github.mysticism.net;

import io.github.mysticism.mixin.SourceChunkHolderAccessor;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.*;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.registry.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Generated-status proof covers the COMPLETE vanilla ticket dependency pyramid, not just its center. */
final class SourceObservationTickets {
    private static final int MAX_OBSERVERS=8,RADIUS=4,MAX_PROOFS=2048,MAX_PENDING=8,PEEKS_PER_POLL=64;
    private static final ChunkTicketType<UUID> TYPE=ChunkTicketType.create("mysticism_source_observer",Comparator.<UUID>naturalOrder());
    private static final Map<ServerPlayerEntity,State> states=new IdentityHashMap<>();
    private record Proof(ChunkPos pos,ChunkStatus required){}
    private static final class State {
        final ServerWorld world;final ChunkPos center;final List<Proof> proofs=new ArrayList<>();final Set<Long> passed=new HashSet<>();
        final Map<Long,CompletableFuture<?>> pending=new HashMap<>();int cursor;boolean ticket;
        State(ServerWorld world,ChunkPos center){
            this.world=world;this.center=center;int level=33-RADIUS,r=0;
            while(r<32){var status=ChunkLevels.getStatus(level+r);if(status==null||status==ChunkStatus.EMPTY)break;r++;}
            if(r==32)throw new IllegalStateException("Unknown vanilla source ticket dependencies");
            for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){var required=ChunkLevels.getStatus(level+Math.max(Math.abs(dx),Math.abs(dz)));if(required==null||required==ChunkStatus.EMPTY)continue;proofs.add(new Proof(new ChunkPos(center.x+dx,center.z+dz),required));}
            if(proofs.size()>MAX_PROOFS)throw new IllegalStateException("Source ticket proof budget exceeded");
        }
    }
    static boolean observe(ServerPlayerEntity player,SpiritScenePayload.Binding binding){
        if(binding==null){release(player);return false;}
        var key=RegistryKey.of(RegistryKeys.WORLD,Identifier.of(binding.dimension()));var world=player.getServer().getWorld(key);
        if(world==null){release(player);return false;}var center=new ChunkPos(BlockPos.ofFloored(binding.sourcePosition()));var s=states.get(player);
        if(s!=null&&(s.world!=world||!s.center.equals(center))){release(player);s=null;}
        if(s==null){if(states.size()>=MAX_OBSERVERS)return world.getChunkManager().getWorldChunk(center.x,center.z)!=null;s=new State(world,center);states.put(player,s);}
        if(!s.ticket){var storage=world.getChunkManager().chunkLoadingManager;var access=(SourceChunkHolderAccessor)storage;
            for(int checked=0;checked<PEEKS_PER_POLL&&s.cursor<s.proofs.size();checked++){
                var proof=s.proofs.get(s.cursor);long id=proof.pos().toLong();
                if(s.passed.contains(id)||s.pending.containsKey(id)){s.cursor++;continue;}
                var holder=access.mysticism$currentHolder(id);var status=holder==null?null:holder.getActualStatus();
                if(status!=null&&status.isAtLeast(proof.required())){s.passed.add(id);s.cursor++;continue;}
                if(s.pending.size()>=MAX_PENDING)break;s.cursor++;
                State owned=s;var server=player.getServer();var request=storage.getNbt(proof.pos());s.pending.put(id,request);
                request.whenComplete((optional,error)->{
                    // IO result is detached NBT; no server world/player/registry reads off-thread.
                    boolean generated=false;
                    try{if(error==null&&optional!=null&&optional.isPresent()){
                        var root=optional.get();var data=root.contains("Level")?root.getCompound("Level"):root;var saved=ChunkStatus.byId(data.getString("Status"));
                        generated=saved!=null&&saved.isAtLeast(proof.required())&&!data.contains("below_zero_retrogen");
                    }}catch(RuntimeException malformed){/* Malformed/unknown status fails proof and remains retryable. */}
                    boolean accepted=generated;if(server.isStopping())return;
                    server.execute(()->{if(states.get(player)!=owned||owned.pending.get(id)!=request)return;owned.pending.remove(id);if(accepted)owned.passed.add(id);});
                });
            }
            if(s.cursor>=s.proofs.size()&&s.pending.isEmpty()){
                if(s.passed.size()==s.proofs.size()){world.getChunkManager().addTicket(TYPE,s.center,RADIUS,player.getUuid());s.ticket=true;}
                else s.cursor=0; // Retry absent/unsaved source statuses; never generate to repair them.
            }
        }
        return world.getChunkManager().getWorldChunk(center.x,center.z)!=null; // Already-loaded source stays visible while retention proof is pending.
    }
    static void release(ServerPlayerEntity player){var s=states.remove(player);if(s==null)return;if(s.ticket)s.world.getChunkManager().removeTicket(TYPE,s.center,RADIUS,player.getUuid());var pending=List.copyOf(s.pending.values());s.pending.clear();pending.forEach(f->f.cancel(false));}
    static void stop(net.minecraft.server.MinecraftServer server){new ArrayList<>(states.keySet()).stream().filter(p->p.getServer()==server).forEach(SourceObservationTickets::release);}
}
