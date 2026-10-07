package io.github.mysticism.landmark;

import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Per-frame resumable semantic range traversal, never nearest K. Source-space geometry is 3D;
 * geometry page masks can be disconnected and unknown cells never become sample boxes.
 * Cold pages hydrate only after semantic admission, and optionally after source AABB admission. */
public final class SourceSelector {
    private SourceSelector(){}
    public record Sample(String landmarkId,long revision,String dimension,Bounds sourceBounds,
                         BlockPalette.State material,BlockSample.Occupancy occupancy,
                         LandmarkEmbedding embedding,double importance){}
    public record Batch(List<Sample> samples,boolean complete,String cursor){public Batch{samples=List.copyOf(samples);}}
    public static Request begin(MinecraftServer server,LandmarkEmbedding embedding,double radius,long frameGeneration,Bounds sourceRange){
        if(!server.isOnThread())throw new IllegalStateException("source selector server thread");
        LandmarkProfiles.current().requireCompatible(embedding.profile());
        if(!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("semantic radius");
        return new Request(server,embedding,radius,frameGeneration,sourceRange);
    }
    public static final class Request {
        final MinecraftServer server;final LandmarkStore store;final LandmarkEmbedding embedding;final double radius;final Bounds sourceRange;final long frameGeneration;
        String cursor;boolean end,cancelled;final ArrayDeque<LandmarkMetadata> candidates=new ArrayDeque<>();final ArrayDeque<Sample> staged=new ArrayDeque<>();
        LandmarkMetadata current;LandmarkEmbedding currentEmbedding;double currentImportance;LandmarkStore.GeometryRead read;
        final ArrayDeque<GeometryPage> pages=new ArrayDeque<>();GeometryPage currentPage;SparseOctree<BlockSample>.Cursor cells;
        Request(MinecraftServer server,LandmarkEmbedding embedding,double radius,long generation,Bounds sourceRange){this.server=server;store=LandmarkStore.get(server);this.embedding=new LandmarkEmbedding(embedding.profile(),embedding.vector());this.radius=radius;frameGeneration=generation;this.sourceRange=sourceRange;}
        public long generation(){return frameGeneration;}
        public boolean complete(){return cancelled||(end&&candidates.isEmpty()&&read==null&&staged.isEmpty()&&pages.isEmpty()&&cells==null);}
        public void cancel(){cancelled=true;if(read!=null)read.cancel();read=null;candidates.clear();staged.clear();pages.clear();cells=null;currentPage=null;}
        public Batch advance(int metadataBudget,int pageBudget,int leafBudget,int maxSamples){
            if(!server.isOnThread())throw new IllegalStateException("source selector server thread");
            if(metadataBudget<1||metadataBudget>16||pageBudget<1||pageBudget>4||leafBudget<1||leafBudget>512||maxSamples<1||maxSamples>512)throw new IllegalArgumentException("source selection operation budget");
            LandmarkProfiles.current().requireCompatible(embedding.profile());if(cancelled)return new Batch(List.of(),true,cursor);
            if(!staged.isEmpty())return drain(maxSamples);
            if(cells==null&&!pages.isEmpty()){currentPage=pages.removeFirst();cells=currentPage.cells().cursor(sourceRange==null?currentPage.bounds():sourceRange);}
            if(cells!=null){
                for(var cell:cells.advance(leafBudget,maxSamples))staged.add(new Sample(current.id(),current.revision(),current.header().dimension(),cell.bounds(),currentPage.palette().state(cell.value().paletteIndex()),cell.value().occupancy(),currentEmbedding,currentImportance));
                if(cells.complete()){cells=null;currentPage=null;}return drain(maxSamples);
            }
            if(read!=null){
                read.advance(pageBudget,leafBudget);pages.addAll(read.drain());
                if(read.complete()){if(!read.isCurrent())pages.clear();read=null;}
                return drain(maxSamples);
            }
            if(!candidates.isEmpty()){
                current=candidates.removeFirst();currentEmbedding=SpiritActivityService.effectiveEmbedding(server,current);currentImportance=SpiritActivityService.importance(server,current);
                if(currentEmbedding.distanceSquared(embedding)<radius*radius && (sourceRange==null||sourceRange.intersects(current.header().bounds())) && store.geometryReadAvailable())read=store.beginGeometryRead(current.id(),sourceRange);
                else if(!store.geometryReadAvailable()){candidates.addFirst(current);}
                return drain(maxSamples);
            }
            if(!end){var page=store.semanticRangePage(embedding,radius,cursor,metadataBudget,metadataBudget,m->SpiritActivityService.effectiveEmbedding(server,m));cursor=page.nextId();end=page.end();candidates.addAll(page.landmarks());}
            return drain(maxSamples);
        }
        private Batch drain(int limit){List<Sample> out=new ArrayList<>();while(out.size()<limit&&!staged.isEmpty())out.add(staged.removeFirst());return new Batch(out,complete(),cursor);}
    }
}
