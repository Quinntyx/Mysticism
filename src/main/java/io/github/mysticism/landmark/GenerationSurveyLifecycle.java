package io.github.mysticism.landmark;

import io.github.mysticism.landmark.extract.SourceDimensions;
import java.util.*;

/** Server-thread generation event state, independent of source-service initialization.
 * Spawn CHUNK_LOAD can precede SERVER_STARTED: capture actual columns immediately and
 * hand the same residency-bounded queue to the later session, without world/chunk/view
 * references, replaying events, loading chunks, or initializing a store/model early.
 * The type parameter is only the server identity, permitting startup-order regressions
 * without constructing a Minecraft server or bypassing the production survey path. */
final class GenerationSurveyLifecycle<S> {
    private final Map<S,GenerationSurveyQueue> queues=new IdentityHashMap<>();

    GenerationSurveyQueue started(S server){
        return queues.computeIfAbsent(Objects.requireNonNull(server),key->new GenerationSurveyQueue());
    }
    void loaded(S server,String dimension,int chunkX,int chunkZ,int bottom,int top,GenerationSurvey.ColumnView columns){
        if(!SourceDimensions.isSource(dimension))return;
        List<GenerationSurvey.Probe> probes;
        try{probes=GenerationSurvey.survey(chunkX*16,chunkZ*16,bottom,top,columns);}
        catch(RuntimeException unavailable){return;}
        started(server).loaded(dimension,chunkX,chunkZ,SourceLandmarks.surveyHints(probes));
    }
    void unloaded(S server,String dimension,int chunkX,int chunkZ){
        var queue=queues.get(server);if(queue!=null)queue.unloaded(dimension,chunkX,chunkZ);
    }
    void unloaded(S server,String dimension){
        var queue=queues.get(server);if(queue!=null)queue.unloaded(dimension);
    }
    void stopped(S server){
        var queue=queues.remove(server);if(queue!=null)queue.clear();
    }
    int servers(){return queues.size();}
}
