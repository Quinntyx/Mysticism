package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.ArrayList;
import java.util.List;

/** Owns staged display resources until a complete build transfers ownership to the renderer.
 * A failed later batch closes ALL earlier batches without touching the committed display. */
public final class TerrainBatchBuild<T extends AutoCloseable> implements AutoCloseable {
    private final List<T> resources=new ArrayList<>();
    private boolean transferred;
    public void add(T resource){
        if(transferred)throw new IllegalStateException("Display build already transferred");
        resources.add(resource);
    }
    public List<T> complete(){
        if(transferred)throw new IllegalStateException("Display build already transferred");
        List<T> result=List.copyOf(resources);transferred=true;return result;
    }
    @Override public void close(){
        if(transferred)return;
        RuntimeException failure=null;
        for(T resource:resources)try{resource.close();}catch(Exception error){
            if(failure==null)failure=new IllegalStateException("Terrain batch cleanup failed",error);
            else failure.addSuppressed(error);
        }
        resources.clear();transferred=true;
        if(failure!=null)throw failure;
    }
}
