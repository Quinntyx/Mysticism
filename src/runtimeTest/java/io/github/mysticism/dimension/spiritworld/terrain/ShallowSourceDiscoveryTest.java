package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Runs the production outward acquisition state machine, real octree traversal, compaction,
 * publication and SAT collision. Only repository/generated-world IO and material resolution
 * are fixtures; no bootstrapped game, invented inferred solids or model vectors are needed. */
public final class ShallowSourceDiscoveryTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static final String DIM="minecraft:overworld";
    private static final Vec3d FOCUS=new Vec3d(.5,64,.5);
    private static final SourceMeshBuilder.Tile STONE=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:stone",Map.of()),List.of(SourceMeshBuilder.UNIT),0xffffff,0xf000f0,false,true);
    private static final SourceMeshBuilder.Tile AIR=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:air",Map.of()),List.of(),0xffffff,0xf000f0,true,false);
    private static final BlockPalette.State STATE=new BlockPalette.State("minecraft:stone",Map.of());

    private static final class Fixture implements ShallowSourceDiscovery.Source {
        final List<Bounds> queries=new ArrayList<>();
        final List<LandmarkMetadata> metadata=new ArrayList<>();
        final Map<String,GeometryPage> pages=new HashMap<>();
        boolean busy,delayed,failed,emptyCatalog,fullRegions;
        CompletableFuture<SourceLandmarks.Region> held;
        BlockPoint airObservation;
        int reads,cancels;
        Fixture(){this(120);}
        Fixture(int horizon) {
            // Distant native terrain belongs to DIFFERENT landmarks; similarity selection is
            // neither called nor capable of supplying this source-identical entry horizon.
            for(BlockPos pos:List.of(new BlockPos(horizon,63,0),new BlockPos(-horizon,63,0),new BlockPos(0,63,horizon),new BlockPos(0,63,-horizon),new BlockPos(0,64+horizon,0))) {
                var anchor=new BlockPoint(pos.getX(),pos.getY(),pos.getZ());Bounds b=Bounds.cube(anchor.x(),anchor.y(),anchor.z(),1);
                var page=new GeometryPage(LandmarkIds.geometryPage(DIM,anchor,1),0,b,new BlockPalette(List.of(STATE)),
                        SparseOctree.<BlockSample>empty(b,1,1).with(b,new BlockSample(BlockSample.Occupancy.SOLID,0),1));
                String id=LandmarkIds.seed(DIM,"fixture",Landmark.Kind.BIOME,"fixture",anchor);
                // Metadata embedding is irrelevant: no inference is claimed or used by discovery.
                var landmark=new Landmark(id,DIM,"fixture",Landmark.Kind.BIOME,"fixture",anchor,b,SpiritTerrainService.coordinate(Vec384f.ZERO()),.1,
                        new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"fixture IO");
                metadata.add(new LandmarkMetadata(landmark,List.of("mysticism.landmark.geometry."+page.id()+".0")));pages.put(id,page);
            }
        }
        public LandmarkStore.SourceRangePage catalog(Bounds range,String cursor) {
            List<LandmarkMetadata> all=emptyCatalog?List.of():metadata.stream().filter(m->m.header().bounds().intersects(range)).toList();
            int index=cursor==null?0:Integer.parseInt(cursor);int end=Math.min(all.size(),index+4);
            check(end-index<=4,"Catalog acquisition must use bounded batches");
            return new LandmarkStore.SourceRangePage(all.subList(index,end),Integer.toString(end),end-index,end==all.size());
        }
        public ShallowSourceDiscovery.Read read(String id,Bounds range,int index) {
            if(busy)return null;reads++;
            return new ShallowSourceDiscovery.Read() {
                int cursor=index;boolean drained;
                public void advance(){cursor=1;}
                public List<GeometryPage> drain(){if(drained || index!=0)return List.of();drained=true;return List.of(pages.get(id));}
                public boolean complete(){return cursor==1;}
                public boolean current(){return true;}
                public int nextPageIndex(){return cursor;}
                public void cancel(){cancels++;}
            };
        }
        public CompletableFuture<SourceLandmarks.Region> region(Bounds range) {
            queries.add(range);
            long volume=(range.maxX()-range.minX())*(range.maxY()-range.minY())*(range.maxZ()-range.minZ());
            check(volume<=4096,"Outward generated query exceeds production IO budget");
            if(delayed){held=new CompletableFuture<>();return held;}
            if(failed){failed=false;return CompletableFuture.failedFuture(new IllegalStateException("fixture IO failure"));}
            if(fullRegions) {
                List<SourceLandmarks.Cell> cells=new ArrayList<>();
                for(long x=range.minX();x<range.maxX();x++)for(long y=range.minY();y<range.maxY();y++)for(long z=range.minZ();z<range.maxZ();z++) {
                    boolean marker=x==range.minX()+8 && y==range.minY() && z==range.minZ()+8;
                    cells.add(new SourceLandmarks.Cell(new BlockPoint(x,y,z),marker?STATE:new BlockPalette.State("minecraft:air",Map.of()),"fixture",true));
                }
                return CompletableFuture.completedFuture(new SourceLandmarks.Region(DIM,range,cells,true));
            }
            // One known surface sample per patch; UNKNOWN space is never filled in by the algorithm.
            List<SourceLandmarks.Cell> cells=new ArrayList<>();
            if(range.minY()<=63 && range.maxY()>63)cells.add(new SourceLandmarks.Cell(new BlockPoint(range.minX()+8,63,range.minZ()+8),STATE,"fixture",true));
            if(airObservation!=null && range.contains(airObservation.x(),airObservation.y(),airObservation.z()))
                cells.add(new SourceLandmarks.Cell(airObservation,new BlockPalette.State("minecraft:air",Map.of()),"fixture",true));
            return CompletableFuture.completedFuture(new SourceLandmarks.Region(DIM,range,cells,false));
        }
    }
    private static ShallowSourceDiscovery discovery(Fixture io) {
        return new ShallowSourceDiscovery(io,c->c.air()?AIR:STONE,m->STONE);
    }
    private static TerrainMeshFrame publish(List<SourceMeshBuilder.Node> nodes,Vec3d source,Vec3d carrier) {
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        MeshPublication.append(nodes,DIM,source,carrier,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),carrier,1,1024,
                materials,new HashMap<>(),cells,new HashSet<>());
        return new TerrainMeshFrame(1,true,DIM,source,carrier,materials,cells);
    }
    private static void nativeCatalogHorizon() {
        Fixture io=new Fixture();var d=discovery(io);
        for(int tick=0;tick<100;tick++)d.advance(FOCUS,-64,320,tick);
        check(io.reads>=5 && io.cancels==io.reads,"Every source landmark must acquire geometry and yield repository cursor");
        check(d.snapshot().size()<=ShallowSourceDiscovery.MAX_NODES,"Bounded source-node reservoir");
        // Same source displacement at a completely different carrier origin, not text-semantic projection.
        Vec3d carrier=new Vec3d(3000,90,-2000);var frame=publish(d.snapshot(),FOCUS,carrier);
        for(var m:io.metadata) {
            BlockPoint point=m.header().anchor();
            var visible=frame.cells().stream().filter(c->c.landmarkId().equals(m.id())).findFirst();
            check(visible.isPresent(),"Normal shallow entry never acquired distant source landmark "+point);
            Vec3d projected=carrier.add(point.x()-FOCUS.x,point.y()-FOCUS.y,point.z()-FOCUS.z);
            check(visible.get().min().distanceTo(projected)<1e-8,"Distant shallow source geometry changed projection");
            check(!new MeshCollision.Index(frame).clearRay(projected.add(.5,2,.5),projected.add(.5,.5,.5)),"Visible acquired source cell lacks matching collision");
        }
    }
    private static void generatedHorizonAndLifecycle() {
        Fixture io=new Fixture();io.emptyCatalog=true;io.failed=true;var d=discovery(io);
        for(int tick=0;tick<8000;tick++)d.advance(FOCUS,-64,320,tick);
        check(io.queries.getFirst().minY()<=63 && io.queries.getFirst().maxY()>63,"Supported entry must discover its floor layer first");
        check(io.queries.stream().anyMatch(b->b.minX()>=112),"Outward queries never reached positive render horizon");
        check(io.queries.stream().anyMatch(b->b.maxX()<=-112),"Outward queries never reached negative render horizon");
        check(io.queries.stream().anyMatch(b->b.minZ()>=112),"Outward queries never reached lateral render horizon");
        check(io.queries.stream().anyMatch(b->b.minY()>80),"Outward traversal silently reduced source acquisition to a horizontal/local patch");
        var frame=publish(d.snapshot(),FOCUS,FOCUS);
        check(frame.cells().stream().anyMatch(c->Math.abs(c.sourceMin().x)>=112),"Generated horizon never reached publication input");
        check(frame.cells().stream().allMatch(c->c.landmarkId().isEmpty()),"Generated-only acquisition invented source ownership");
        check(frame.cells().size()<=1024 && d.snapshot().size()<=ShallowSourceDiscovery.MAX_NODES,"Wire and reservoir budgets must remain bounded");
        Fixture held=new Fixture();held.delayed=true;var pending=discovery(held);pending.advance(FOCUS,-64,320,0);
        check(held.held!=null && !held.held.isDone(),"Fixture must exercise actual pending generated IO");
        pending.cancel();check(held.held.isCancelled(),"Mode/disconnect cancellation left stale generated IO alive");
        held.delayed=false;for(int tick=1;tick<8;tick++)pending.advance(FOCUS.add(1000,0,0),-64,320,tick);
        check(pending.snapshot().stream().allMatch(n->n.position().getX()>800),"Recenter retained old out-of-range source terrain");
        check(held.queries.getLast().minX()>800,"Recenter continued old source requests");
    }
    private static void actualRenderDistance() {
        check(DiscoveryBudget.renderDistance(16)==256, "Client render distance may not silently clamp to legacy 128 blocks");
        check(DiscoveryBudget.renderDistance(0)==32 && DiscoveryBudget.renderDistance(1000)==512, "Client setting must obey vanilla's bounded range");
        Fixture io=new Fixture(240);var d=discovery(io);
        for(int tick=0;tick<100;tick++)d.advance(FOCUS,-64,320,tick,256);
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        MeshPublication.append(d.snapshot(),DIM,FOCUS,FOCUS,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),FOCUS,1,1024,
                materials,new HashMap<>(),cells,new HashSet<>(),256);
        for(var m:io.metadata)check(cells.stream().anyMatch(c->c.landmarkId().equals(m.id())), "Real render-distance source acquired then culled at 128 blocks");
        d.advance(FOCUS,-64,320,101,64);
        check(d.snapshot().stream().noneMatch(n->!n.ownerId().isEmpty()), "Changed render distance failed to restart/cull old outward acquisition");
    }
    private static void movingAcquisitionAndRetry() {
        Fixture moving=new Fixture();moving.emptyCatalog=true;var d=discovery(moving);
        // Recenter on each completed patch. Restarting at the center would query only near tiles
        // forever, even though every individual request appeared bounded and successful.
        Vec3d at=FOCUS;
        for(int step=0;step<20;step++) {
            int before=moving.queries.size();
            for(int tick=0;tick<20 && moving.queries.size()==before;tick++)d.advance(at,-64,320,step*20L+tick);
            check(moving.queries.size()>before,"Moving outward acquisition stopped advancing");
            at=at.add(step%2==0?20:-20,0,0);
        }
        check(moving.queries.stream().anyMatch(b->Math.abs(b.minX()-FOCUS.x)>32 || Math.abs(b.minZ()-FOCUS.z)>32),
                "Ordinary motion restarted every query at its near center");
        for(int tick=400;tick<500;tick++)d.advance(at,-64,320,tick);
        List<SourceMeshBuilder.Node> before=d.snapshot();check(!before.isEmpty(),"Retry fixture must retain real acquired geometry");
        d.retry();check(d.snapshot().equals(before),"An IO retry erased the coherent acquired terrain");
        d.advance(at,-64,320,501);check(!d.snapshot().isEmpty(),"Resumed acquisition discarded its still-in-range reservoir");
    }
    private static void observationMasks() {
        Fixture io=new Fixture(8);io.airObservation=new BlockPoint(8,63,0);var d=discovery(io);
        for(int tick=0;tick<600;tick++)d.advance(FOCUS,-64,320,tick);
        check(d.snapshot().stream().noneMatch(n->n.position().equals(new BlockPos(8,63,0))),
                "Periodic persisted acquisition resurrected a source SOLID after generated AIR observation");
        NegativeCoverage masks=new NegativeCoverage();BlockPos inside=new BlockPos(0,63,0),outside=new BlockPos(1000,63,0);
        masks.add(inside);masks.add(outside);masks.retain(new Bounds(-128,-64,-128,129,192,129));
        check(masks.size()==1 && masks.contains(inside) && !masks.contains(outside),
                "Recentered observation retention leaked old source buckets or forgot still-visible negative observations");
    }
    /** A stationary 32-chunk observer with no persisted geometry. Every generated request
     * returns ALL 4096 cells, not the old one-cell fixture that hid the mask-cap deadlock.
     * Three complete source layers exceed 8192 buckets; acquisition must still reach the last
     * layer's horizon and publish real collidable terrain without a recenter/retry. */
    private static void fullRegionExhaustion() {
        Fixture io=new Fixture();io.emptyCatalog=true;io.fullRegions=true;var d=discovery(io);
        int tick=0;
        while(tick<1_000_000) {
            d.advance(FOCUS,32,80,tick++,512);
            if((tick&63)==0 && io.queries.size()>NegativeCoverage.MAX_BUCKETS
                    && d.snapshot().stream().anyMatch(n->n.position().getY()==64 && n.position().getX()>=480 && Math.abs(n.position().getZ())<=32)
                    && d.snapshot().stream().anyMatch(n->n.position().getY()==64 && n.position().getX()<=-480 && Math.abs(n.position().getZ())<=32))break;
        }
        check(tick<1_000_000,"Full generated regions stopped before the final-layer horizon: queries="+io.queries.size()+", deferred="+d.budgetPaused()+", nodes="+d.snapshot().size());
        check(io.queries.size()>NegativeCoverage.MAX_BUCKETS,"Regression never crossed the old full-region exhaustion boundary");
        check(!d.budgetPaused(),"Complete generated regions must fit compact masks throughout the maximum horizon");
        check(d.snapshot().size()<=ShallowSourceDiscovery.MAX_NODES,"Full-region exhaustion escaped the node retention budget");
        List<TerrainMeshFrame.Material> materials=new ArrayList<>();List<TerrainMeshFrame.Cell> cells=new ArrayList<>();
        MeshPublication.append(d.snapshot(),DIM,FOCUS,FOCUS,new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),FOCUS,1,1024,
                materials,new HashMap<>(),cells,new HashSet<>(),512);
        var frame=new TerrainMeshFrame(2,true,DIM,FOCUS,FOCUS,materials,cells);var collision=new MeshCollision.Index(frame);
        for(int sign:new int[]{-1,1}) {
            // The 1024-cell wire budget uses spatial bins, so require the outer 64-block
            // horizon band, not one particular sample within the final bin.
            var horizon=cells.stream().filter(c->c.sourceMin().x*sign>=448 && Math.abs(c.sourceMin().z)<=64 && c.sourceMin().y==64).findFirst();
            check(horizon.isPresent(),"Post-exhaustion generated horizon lost render publication in direction "+sign);
            Vec3d point=horizon.orElseThrow().min();
            check(!collision.clearRay(point.add(.5,2,.5),point.add(.5,.5,.5)),"Post-exhaustion visible horizon lacks matching SAT collision");
        }
    }
    private static void serviceWiring() throws Exception {
        String source=Files.readString(Path.of("src/main/java/io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService.java"));
        check(source.contains("c.advanceShallow();c.advanceNear();c.advanceGeometry();"),"Production tick does not run outward acquisition");
        check(source.contains("s.shallowDiscovery.snapshot()"),"Production publication does not consume outward geometry");
        check(source.contains("SourceLandmarks.region(server,w.dimension,range,4096)"),"Production outward source IO is not wired");
        check(source.contains("store.sourceRangePage(w.dimension,range,cursor,4,4)"),"Production shallow catalog is not bounded/source-native");
    }
    public static void main(String[] args) throws Exception {
        nativeCatalogHorizon();generatedHorizonAndLifecycle();actualRenderDistance();movingAcquisitionAndRetry();observationMasks();fullRegionExhaustion();serviceWiring();
        System.out.println("ShallowSourceDiscoveryTest: "+checks+" checks passed (production source acquisition / mesh / SAT)");
    }
}
