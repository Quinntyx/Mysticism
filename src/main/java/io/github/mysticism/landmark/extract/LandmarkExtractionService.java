package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import io.github.mysticism.activity.*;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Stable parent entrypoint. The obsolete cube/journal controller is intentionally gone:
 * one native 3D source service owns all discovery, edits, queries and topology. */
public final class LandmarkExtractionService {
    public static final String ALGORITHM=SourceLandmarks.ALGORITHM;
    public static final int CELLS_PER_TICK=512,PENDING_REGIONS=64,TRACKED_CHUNKS=0;
    public static final long NANOS_PER_TICK=1_500_000;
    private LandmarkExtractionService(){}
    public static void init(){SourceLandmarks.init();}
    public static void changed(ServerWorld world,BlockPos pos){SourceLandmarks.changed(world,pos);}
    public record Stats(int pendingRegions,int trackedChunks,int journalRegions,int lastSampledCells,String status){}
    public static Stats stats(MinecraftServer server){return new Stats(SourceLandmarks.pending(server),0,0,SourceLandmarks.lastSampledCells(server),SourceLandmarks.status(server));}
    @FunctionalInterface public interface TopologyListener{void committed(MinecraftServer server,List<String> previous,List<String> replacement);}
    public static final Event<TopologyListener> COMMITTED_TOPOLOGY=EventFactory.createArrayBacked(TopologyListener.class,listeners->(server,previous,next)->{for(var listener:listeners)try{listener.committed(server,previous,next);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger("Mysticism-Extraction").error("Committed source topology observer failed",failure);}});
    public enum TopologyKind{MERGE,SPLIT,REMOVE}
    public record TopologyChange(TopologyKind kind,List<LandmarkRepository.RevisionRef> parents,List<Landmark> children,LandmarkRepository.VerifiedConnectivity proof){public TopologyChange{parents=List.copyOf(parents);children=List.copyOf(children);}}
    public interface TopologyPlan{void commit();void cancel();}
    @FunctionalInterface public interface TopologyAdapter{TopologyPlan prepare(MinecraftServer server,TopologyChange change);}
    private static TopologyAdapter adapter;
    public static void topologyAdapter(TopologyAdapter value){adapter=Objects.requireNonNull(value);}
    /** Native activity wiring is complete; an installed parent adapter may still veto/replace it. */
    public static TopologyPlan prepareTopology(MinecraftServer server,TopologyChange change){
        if(adapter!=null)return adapter.prepare(server,change);
        if(change.kind()==TopologyKind.MERGE){var plan=LandmarkMerge.prepare(server,change.proof());return new TopologyPlan(){public void commit(){plan.commit();}public void cancel(){plan.cancel();}};}
        LandmarkActivityTopology.Plan plan=change.kind()==TopologyKind.SPLIT?LandmarkActivityTopology.prepareSplit(server,change.parents().getFirst(),change.children()):LandmarkActivityTopology.prepareRemove(server,change.parents());
        return new TopologyPlan(){public void commit(){plan.commit();}public void cancel(){plan.cancel();}};
    }
}
