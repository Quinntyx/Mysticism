package io.github.mysticism.landmark;

import io.github.mysticism.vector.Vec384f;
import java.util.*;

/** Explicit checks (no -ea, JUnit, world bootstrap, models or external test dependencies). */
public final class LandmarkCoreSelfTest {
    private static int checks;
    static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    static void close(double actual,double expected,String message) { check(Math.abs(actual-expected)<1e-7,message+": "+actual+" != "+expected); }
    static void fails(Class<? extends Throwable> type,Runnable action,String message) {
        checks++; try { action.run(); } catch(Throwable e) { if(type.isInstance(e)) return; throw new AssertionError(message,e); } throw new AssertionError(message+" did not fail");
    }
    static final EmbeddingProfile PROFILE=new EmbeddingProfile("test-model","pinned-revision","test-tokenizer","none",Vec384f.ZERO().data().length,EmbeddingProfile.Normalization.NONE,"test-descriptor-v1");
    static Vec384f vector(double x,double y,double z,double hidden) {
        float[] data=Vec384f.ZERO().data(); data[0]=(float)x; data[1]=(float)y; data[2]=(float)z; data[3]=(float)hidden; return new Vec384f(data);
    }
    static LandmarkEmbedding embedding(double x,double y,double z,double hidden) { return new LandmarkEmbedding(PROFILE,vector(x,y,z,hidden)); }
    static Landmark landmark(long x,double semantic,double importance) {
        BlockPoint anchor=new BlockPoint(x,0,0); Bounds bounds=Bounds.cube(x,0,0,1);
        return landmark(anchor,bounds,embedding(semantic,0,0,0),importance,new SourceGeometry(List.of(),List.of()),0,new Ownership(List.of()));
    }
    static Landmark landmark(BlockPoint anchor,Bounds bounds,LandmarkEmbedding embedding,double importance,SourceGeometry geometry,long revision,Ownership owner) {
        return new Landmark(LandmarkIds.seed("minecraft:overworld","test-v1",Landmark.Kind.CAVE,"minecraft:plains",anchor),"minecraft:overworld","test-v1",Landmark.Kind.CAVE,"minecraft:plains",anchor,bounds,embedding,importance,new ActivityMetadata(0.5,0),owner,geometry,revision,"deterministic-test");
    }
    static ProjectionFrame frame(Point3 origin) { return new ProjectionFrame(1,42,embedding(0,0,0,0),origin,vector(1,0,0,0),vector(0,1,0,0),vector(0,0,1,0),100); }
    private static void octree() {
        SparseOctree<String> empty=SparseOctree.empty(Bounds.cube(0,0,0,1),1,128);
        SparseOctree<String> tree=empty.with(Bounds.cube(-8,-8,-8,8),"solid",4096).with(Bounds.cube(-1,-1,-1,1),"air",4096);
        check(empty.sample(-1,-1,-1)==null,"persistent immutable octree");
        check("air".equals(tree.sample(-1,-1,-1)),"negative fine cell");
        check("solid".equals(tree.sample(-2,-1,-1)),"refined sibling retained");
        check(tree.sample(0,-1,-1)==null,"half-open boundary unknown not air");
        check(tree.query(Bounds.cube(0,0,0,1),10).isEmpty(),"touching face excluded");
        SparseOctree<String> expanded=tree.with(Bounds.cube(32,32,32,1),"far",4096);
        check("air".equals(expanded.sample(-1,-1,-1)),"root expansion preserves absolute data");
        fails(IllegalArgumentException.class,()->empty.with(Bounds.cube(32,32,32,1),"far",1),"expansion consumes bounded update work");
        fails(IllegalArgumentException.class,()->expanded.with(Bounds.cube(1000,0,0,1),"too-far",100),"maximum root size");
        fails(IllegalArgumentException.class,()->tree.with(Bounds.cube(-2,-2,-2,1),"x",1),"atomic update budget");
        check("solid".equals(tree.sample(-2,-2,-2)),"failed update did not mutate");
        SparseOctree<String> coarse=SparseOctree.empty(Bounds.cube(-4,-4,-4,8),2,32);
        fails(IllegalArgumentException.class,()->coarse.with(Bounds.cube(-1,-2,-2,2),"x",20),"bounded minimum resolution");
        SparseOctree<String> compact=SparseOctree.<String>empty(Bounds.cube(0,0,0,2),1,8).with(Bounds.cube(0,0,0,2),"solid",1);
        check(compact.cells(1).size()==1,"uniform coarse node");
        check(compact.with(Bounds.cube(0,0,0,1),"air",32).with(Bounds.cube(0,0,0,1),"solid",32).cells(1).size()==1,"coalesce refined cells");
        fails(IllegalArgumentException.class,()->tree.cells(0),"no silent leaf truncation");
        Random random=new Random(1729); SparseOctree<Integer> randomized=SparseOctree.empty(Bounds.cube(0,0,0,1),1,256);
        Map<BlockPoint,Integer> reference=new HashMap<>();
        for(int i=0;i<400;i++) {
            BlockPoint p=new BlockPoint(random.nextInt(32)-16,random.nextInt(32)-16,random.nextInt(32)-16); Integer v=i%7==0?null:i;
            randomized=randomized.with(Bounds.cube(p.x(),p.y(),p.z(),1),v,512); reference.put(p,v);
        }
        for(var entry:reference.entrySet()) { BlockPoint p=entry.getKey(); check(Objects.equals(randomized.sample(p.x(),p.y(),p.z()),entry.getValue()),"randomized absolute sampling"); }
    }
    private static void geometryAndVectors() {
        Vec384f mutable=vector(1,0,0,0); LandmarkEmbedding e=new LandmarkEmbedding(PROFILE,mutable); mutable.mul(100);
        close(e.data()[0],1,"defensive constructor vector"); e.vector().mul(100); float[] exposed=e.data(); exposed[0]=10;
        close(e.data()[0],1,"defensive accessor vector");
        EmbeddingProfile different=new EmbeddingProfile("other-model","pinned-revision","test-tokenizer","none",PROFILE.dimensions(),EmbeddingProfile.Normalization.NONE,"test-descriptor-v1");
        fails(IllegalArgumentException.class,()->e.distanceSquared(new LandmarkEmbedding(different,vector(1,0,0,0))),"no mixed models");
        float[] nan=Vec384f.ZERO().data(); nan[0]=Float.NaN; fails(IllegalArgumentException.class,()->new LandmarkEmbedding(PROFILE,new Vec384f(nan)),"nonfinite vector");
        BlockPalette palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        SparseOctree<BlockSample> cells=SparseOctree.<BlockSample>empty(Bounds.cube(-4,0,0,4),1,4)
                .with(Bounds.cube(-4,0,0,1),new BlockSample(BlockSample.Occupancy.SOLID,1),32).with(Bounds.cube(-3,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32);
        GeometryPage page=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",new BlockPoint(-4,0,0),4),2,Bounds.cube(-4,0,0,4),palette,cells);
        FrontierFace frontier=new FrontierFace("minecraft:overworld",Bounds.cube(0,0,0,1),FrontierFace.Direction.EAST,2,"resume-cell-0");
        SourceGeometry geometry=new SourceGeometry(List.of(page),List.of(frontier));
        check(geometry.sample(-2,0,0)==null,"unknown is separate from observed air");
        check(geometry.sample(-3,0,0).occupancy()==BlockSample.Occupancy.AIR,"known-air occupancy");
        check(!geometry.frontierClosed(),"explicit unobserved frontier");
        check(palette.state(geometry.sample(-4,0,0).paletteIndex()).blockId().equals("minecraft:stone"),"usable physical palette");
        check(geometry.materialAt(-4,0,0).orElseThrow().material().blockId().equals("minecraft:stone"),"page-local palette resolved for terrain");
        check(geometry.materialAt(-2,0,0).isEmpty(),"unknown material not air");
        SparseOctree<BlockSample> otherCells=SparseOctree.<BlockSample>empty(page.bounds(),1,4).with(Bounds.cube(-2,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32);
        GeometryPage otherPage=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld","distinct-fragment",new BlockPoint(-4,0,0),4),0,page.bounds(),palette,otherCells);
        SourceGeometry overlappingMasks=new SourceGeometry(List.of(page,otherPage),List.of());
        check(overlappingMasks.sample(-2,0,0).occupancy()==BlockSample.Occupancy.AIR,"disjoint masks in same page cube");
        check(overlappingMasks.materialAt(-2,0,0).orElseThrow().material().blockId().equals("minecraft:air"),"skip unknown pages during material lookup");
        check(overlappingMasks.queryMaterials(page.bounds(),10).size()==3,"query page-local materials without AABB ambiguity");
        fails(IllegalArgumentException.class,()->new SourceGeometry(List.of(page,new GeometryPage(otherPage.id(),1,page.bounds(),palette,cells)),List.of()),"conflicting known masks rejected");
        fails(IllegalArgumentException.class,()->new GeometryPage(page.id(),3,page.bounds(),palette,cells.with(Bounds.cube(-4,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,1),32)),"occupancy/palette mismatch");
    }
    private static void importanceAndSelection() {
        ImportancePolicy policy=new ImportancePolicy(0.6,20,0.01,1);
        ActivityMetadata activity=new ActivityMetadata(1,0);
        close(policy.score(1,1,1,activity,0),0,"strict hard semantic cutoff");
        close(policy.score(100,1,1,activity,0),0,"importance cannot override radius");
        close(policy.score(100,1,Double.NaN,null,-1),0,"distance gate precedes any importance/activity evaluation");
        check(policy.score(0,1,1,activity,0)<=0.6,"bounded importance");
        close(activity.decayed(20,policy),0.5,"half-life decay");
        ActivityMetadata growing=new ActivityMetadata(0,0).advance(10,100,policy);
        close(growing.level(),0.1,"growth budget per elapsed tick");
        close(growing.advance(10,100,policy).level(),0.1,"same-tick activity cannot grow");
        fails(IllegalArgumentException.class,()->growing.decayed(9,policy),"monotonic activity time");
        RepresentativeSelector selector=new RepresentativeSelector(new RepresentativeSelector.Config(2,0.35,64,2,1,2,0.5,0.1,0.1,0.01,1));
        List<Landmark> candidates=List.of(landmark(0,-0.9,0.5),landmark(1,-0.8,0.5),landmark(2,-0.7,0.5),landmark(10,0.7,0.5),landmark(11,0.8,0.5),landmark(12,0.9,0.5),landmark(20,2,1),landmark(21,4,1));
        var selected=selector.select(candidates,embedding(0,0,0,0),1,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,null,Set.of(candidates.get(6).id(),candidates.get(7).id()));
        check(selected.representatives().size()==2,"two semantic clusters, not nearest K");
        Set<String> expected=Set.of(candidates.get(1).id(),candidates.get(4).id());
        check(selected.representatives().stream().map(RepresentativeSelector.Representative::landmarkId).collect(java.util.stream.Collectors.toSet()).equals(expected),"stable weighted medoids");
        for(int seed=0;seed<40;seed++) {
            List<Landmark> shuffled=new ArrayList<>(candidates); Collections.shuffle(shuffled,new Random(seed));
            check(selected.equals(selector.select(shuffled,embedding(0,0,0,0),1,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,null,Set.of(candidates.get(6).id(),candidates.get(7).id()))),"selection arrival-order independence");
        }
        var retained=selector.select(candidates,embedding(0,0,0,0),2,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,selected,Set.of());
        check(retained.representatives().equals(selected.representatives()),"stable seed IDs and hysteresis");
        Set<String> pins=selected.representatives().stream().map(RepresentativeSelector.Representative::landmarkId).collect(java.util.stream.Collectors.toSet());
        var pinned=selector.select(candidates,embedding(0,0,0,0),2,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,selected,pins);
        check(pinned.representatives().equals(selected.representatives()),"pinned medoids preserve previous seed identities");
        var tiny=new RepresentativeSelector(new RepresentativeSelector.Config(2,0.35,1,1,1,2,0,0,0,0,1));
        fails(IllegalArgumentException.class,()->tiny.select(candidates,embedding(0,0,0,0),1,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,null,pins),"candidate truncation cannot silently evict eligible pins");
        check(selector.select(candidates,embedding(0,0,0,0),1,frame(new Point3(1000,0,0)),new Point3(0,0,0),new FogHorizons(100,200,400),policy,0,1,null,Set.of()).representatives().isEmpty(),"projected horizon before importance");
        var one=new RepresentativeSelector(new RepresentativeSelector.Config(2,1,8,1,1,1,1,0,0,0,1));
        Landmark hidden=landmark(new BlockPoint(30,0,0),Bounds.cube(30,0,0,1),embedding(0,0,0,0.5),0.5,new SourceGeometry(List.of(),List.of()),0,new Ownership(List.of()));
        var distorted=one.select(List.of(landmark(31,0,0.5),hidden),embedding(0,0,0,0),1,frame(new Point3(0,0,0)),new Point3(0,0,0),new FogHorizons(1,2,3),policy,0,1,null,Set.of());
        check(distorted.representatives().getFirst().distortion()>0,"projection distortion reported/penalized");
    }
    private static void projectionAndDither() {
        Vec384f axis=vector(1,0,0,0); ProjectionFrame frame=new ProjectionFrame(5,7,embedding(0,0,0,0),new Point3(100,20,-30),axis,vector(0,1,0,0),vector(0,0,1,0),10); axis.mul(10); frame.axisX().mul(10);
        check(frame.project(embedding(1,2,3,0)).equals(new Point3(110,40,0)),"frozen basis and absolute origin");
        Landmark l=landmark(-7,1,0.5); Placement start=frame.place(l,2);
        check(start.projectSource(new Point3(-6,0,0)).equals(new Point3(112,20,-30)),"negative source coordinates placement");
        Placement end=new Placement(l.id(),6,new Point3(210,20,-30),l.anchor(),2);
        check(start.transitionTo(end,0,false).equals(start),"transition starts exactly");
        check(start.transitionTo(end,1,false).equals(end),"transition ends exactly");
        close(start.transitionTo(end,0.5,false).realmAnchor().x(),160,"continuous midpoint");
        check(start.transitionTo(end,0.8,true).equals(start),"collision pin freezes placement");
        close(BorderDither.smoothstep(0),0,"blend endpoint 0"); close(BorderDither.smoothstep(1),1,"blend endpoint 1");
        double fixed=BorderDither.sample(42,123,-17,63,16);
        close(fixed,BorderDither.sample(42,123,-17,63,16),"world-coordinate hash stability");
        check(fixed>=0 && fixed<1,"hash unit interval");
        for(int x=-33;x<=33;x++) {
            boolean choice=BorderDither.chooseIncoming(42,123,x,63,16,0.5);
            check(choice==BorderDither.chooseIncoming(42,123,Math.floorDiv(x,16)*16+Math.floorMod(x,16),63,16,0.5),"chunk border identical dither");
            check(!BorderDither.chooseIncoming(42,123,x,63,16,0),"zero weight always old");
            check(BorderDither.chooseIncoming(42,123,x,63,16,1),"full weight always incoming");
        }
        FogHorizons fog=new FogHorizons(16,32,48); close(fog.opacity(16),0,"visible horizon"); close(fog.opacity(32),1,"fully hidden horizon");
        check(!fog.shouldPrefetch(48*48),"strict prefetch horizon");
        fails(IllegalArgumentException.class,()->new FogHorizons(16,16,32),"ordered horizons");
        fails(IllegalArgumentException.class,()->new FogHorizons(1,2,Double.MAX_VALUE),"horizon squares must remain finite");
        fails(IllegalArgumentException.class,()->new ImportancePolicy(1,20,0.01,1).score(0,Double.MAX_VALUE,1,new ActivityMetadata(0,0),0),"semantic gate cannot silently overflow");
    }
    private static void aliasesAndSplits() {
        Landmark a=landmark(-8,0.1,0.3),b=landmark(0,0.2,0.5),c=landmark(8,0.3,0.7);
        List<Landmark> fragments=List.of(a,b,c); String canonical=fragments.stream().map(Landmark::id).min(String::compareTo).orElseThrow();
        ImportancePolicy policy=new ImportancePolicy(0.8,20,0.01,1); List<LandmarkRepository.Snapshot> snapshots=new ArrayList<>();
        for(int seed=0;seed<8;seed++) {
            List<Landmark> order=new ArrayList<>(fragments); Collections.shuffle(order,new Random(seed)); LandmarkRepository repo=new LandmarkRepository(); order.forEach(l->repo.put(l,-1));
            var proof=new LandmarkRepository.VerifiedConnectivity(order.stream().map(l->new LandmarkRepository.RevisionRef(l.id(),0)).toList(),"verified-six-neighbour-edges");
            Landmark merged=repo.mergeVerified(proof,0,policy); check(merged.id().equals(canonical),"deterministic union representative");
            for(Landmark l:fragments) check(repo.resolve(l.id()).equals(canonical),"flattened aliases");
            snapshots.add(repo.snapshot());
            fails(IllegalStateException.class,()->repo.mergeVerified(proof,0,policy),"stale merge guard");
            fails(IllegalArgumentException.class,()->repo.put(order.stream().filter(l->!l.id().equals(canonical)).findFirst().orElseThrow(),-1),"cannot revive alias as record");
            Landmark restored=LandmarkRepository.restore(repo.snapshot()).get(a.id()).orElseThrow(); check(restored.id().equals(canonical),"alias restart rule");
        }
        for(var snapshot:snapshots) check(snapshot.equals(snapshots.getFirst()),"merge input-order independence");
        LandmarkRepository repo=LandmarkRepository.restore(snapshots.getFirst()); Landmark parent=repo.get(canonical).orElseThrow();
        Landmark left=landmark(new BlockPoint(-7,0,0),Bounds.cube(-7,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        Landmark right=landmark(new BlockPoint(7,0,0),Bounds.cube(7,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        repo.split(new LandmarkRepository.RevisionRef(parent.id(),1),List.of(right,left));
        check(repo.get(parent.id()).isEmpty(),"split without retained seed tombstones parent");
        for(Landmark l:fragments) check(repo.get(l.id()).isEmpty(),"split aliases do not pick arbitrary child");
        check(repo.snapshot().lineage().get(parent.id()).equals(List.of(left.id(),right.id()).stream().sorted().toList()),"explicit sorted split lineage");
        check(LandmarkRepository.restore(repo.snapshot()).landmarks().size()==2,"split restart rule");
        Map<String,String> cycle=Map.of("a","b","b","a"); fails(IllegalArgumentException.class,()->LandmarkRepository.restore(new LandmarkRepository.Snapshot(List.of(),cycle,Map.of(),Map.of())),"reject alias cycles");
        fails(IllegalArgumentException.class,()->LandmarkRepository.restore(new LandmarkRepository.Snapshot(List.of(),Map.of("alias","missing"),Map.of(),Map.of())),"reject dangling aliases");
        Ownership.Claim early=new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000001"),1),late=new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000002"),2);
        check(new Ownership(List.of(late,early)).owner().orElseThrow().equals(early.player()),"ownership deterministic conflict");
        check(new Ownership(List.of(late)).merge(new Ownership(List.of(early))).equals(new Ownership(List.of(early)).merge(new Ownership(List.of(late)))),"ownership merge commutative");
        LandmarkRepository queries=new LandmarkRepository(); queries.put(a,-1); queries.put(b,-1);
        check(queries.sourceRange("minecraft:overworld",Bounds.cube(-8,0,0,1),1).equals(List.of(a)),"source query independent of semantic range");
        check(queries.semanticRange(embedding(0,0,0,0),0.15,1).equals(List.of(a)),"semantic strict range");
        fails(IllegalArgumentException.class,()->queries.semanticRange(embedding(0,0,0,0),1,1),"no silent query truncation");
    }
    private static void reconciledMerges() {
        BlockPalette palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        Bounds cube=Bounds.cube(0,0,0,4);
        SparseOctree<BlockSample> cells=SparseOctree.<BlockSample>empty(cube,1,4).with(Bounds.cube(0,0,0,1),new BlockSample(BlockSample.Occupancy.SOLID,1),32);
        GeometryPage p=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",new BlockPoint(0,0,0),4),0,cube,palette,cells);
        FrontierFace missing=new FrontierFace("minecraft:overworld",Bounds.cube(4,0,0,1),FrontierFace.Direction.EAST,0,"resume");
        Landmark a=landmark(new BlockPoint(0,0,0),cube,embedding(0,0,0,0),0.5,new SourceGeometry(List.of(p),List.of(missing)),0,new Ownership(List.of()));
        Landmark b=landmark(new BlockPoint(1,0,0),cube,embedding(0,0,0,0),0.5,new SourceGeometry(List.of(p),List.of(missing)),0,new Ownership(List.of()));
        LandmarkRepository repository=new LandmarkRepository(); repository.put(a,-1); repository.put(b,-1);
        var proof=new LandmarkRepository.VerifiedConnectivity(List.of(new LandmarkRepository.RevisionRef(a.id(),0),new LandmarkRepository.RevisionRef(b.id(),0)),"verified-adjacent-components");
        SourceGeometry discarded=new SourceGeometry(List.of(),List.of());
        fails(IllegalArgumentException.class,()->repository.mergeVerified(proof,0,new ImportancePolicy(1,20,0.01,1),discarded),"reconciliation cannot discard known material");
        check(repository.landmarks().size()==2,"failed reconciliation atomic");
        GeometryPage advanced=new GeometryPage(p.id(),1,cube,palette,cells.with(Bounds.cube(1,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32));
        Landmark merged=repository.mergeVerified(proof,0,new ImportancePolicy(1,20,0.01,1),new SourceGeometry(List.of(advanced),List.of()));
        check(!merged.provisional() && merged.geometry().sample(1,0,0).occupancy()==BlockSample.Occupancy.AIR,"extractor closes frontier with monotonic observations");
        check(merged.geometry().sample(0,0,0).occupancy()==BlockSample.Occupancy.SOLID,"merge preserves source material");
    }
    public static void main(String[] args) {
        octree(); geometryAndVectors(); importanceAndSelection(); projectionAndDither(); aliasesAndSplits(); reconciledMerges();
        System.out.println("LandmarkCoreSelfTest PASS ("+checks+" explicit checks)");
    }
}
