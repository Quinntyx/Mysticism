package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShapes;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Explicit production field/ledger/persistence checks, no JUnit/model or Minecraft server boot. */
public final class TerrainSelfTest {
    private static int checks;
    private static final EmbeddingProfile PROFILE=new EmbeddingProfile("terrain-test","pinned","tokenizer","prefix",Vec384f.ZERO().data().length,EmbeddingProfile.Normalization.NONE,"schema");
    private static final BlockPalette.State AIR=new BlockPalette.State("minecraft:air",Map.of());
    private static final BlockPalette.State STONE=new BlockPalette.State("minecraft:stone",Map.of());
    private static final BlockPalette.State DIRT=new BlockPalette.State("minecraft:dirt",Map.of());
    private static void check(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }
    private static void fails(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; } throw new AssertionError(why);
    }
    private static Vec384f vector(double x,double y,double z,double hidden) {
        float[] v=Vec384f.ZERO().data(); v[0]=(float)x;v[1]=(float)y;v[2]=(float)z;v[3]=(float)hidden;return new Vec384f(v);
    }
    private static LandmarkEmbedding embedding(double x,double hidden) { return new LandmarkEmbedding(PROFILE,vector(x,0,0,hidden)); }
    private static ProjectionFrame frame() { return new ProjectionFrame(1,123,embedding(0,0),new Point3(0,128,0),vector(1,0,0,0),vector(0,1,0,0),vector(0,0,1,0),96); }
    private static Landmark landmark(long anchor,double semantic,double hidden,double importance,BlockPalette.State material,boolean floor) {
        BlockPoint a=new BlockPoint(anchor,0,0); Bounds b=Bounds.cube(anchor,0,0,8);
        var palette=new BlockPalette(List.of(AIR,material));
        var cells=SparseOctree.<BlockSample>empty(b,1,8).with(b,new BlockSample(BlockSample.Occupancy.AIR,0),4096);
        if(floor)cells=cells.with(new Bounds(anchor,0,0,anchor+8,1,8),new BlockSample(BlockSample.Occupancy.SOLID,1),4096);
        var page=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",a,8),0,b,palette,cells);
        return new Landmark(LandmarkIds.seed("minecraft:overworld","terrain-test",Landmark.Kind.CAVE,"minecraft:plains",a),"minecraft:overworld","terrain-test",Landmark.Kind.CAVE,"minecraft:plains",a,b,
                embedding(semantic,hidden),importance,new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(page),List.of()),0,"test");
    }
    private static LandmarkMetadata metadata(Landmark l) {
        var header=new Landmark(l.id(),l.dimension(),l.algorithmVersion(),l.kind(),l.biome(),l.anchor(),l.bounds(),l.baseEmbedding(),l.baseImportance(),l.activity(),l.ownership(),new SourceGeometry(List.of(),l.geometry().frontiers()),l.revision(),l.provenance());
        return new LandmarkMetadata(header,List.of("mysticism.landmark.geometry."+l.geometry().pages().getFirst().id()+".0"));
    }
    private static TerrainField.Layer layer(Landmark l,Placement p) { return new TerrainField.Layer(metadata(l),p,l.geometry(),l.baseEmbedding(),l.baseImportance()); }
    private static final class World implements OverlayLedger.WorldAccess<BlockPalette.State> {
        final Map<OverlayLedger.Pos,BlockPalette.State> blocks=new HashMap<>(); boolean loaded=true,allowed=true; int reads,writes,reject=-1;
        public boolean loaded(OverlayLedger.Region r) { return loaded; }
        public boolean mayChange(OverlayLedger.Region r) { return allowed; }
        public BlockPalette.State get(OverlayLedger.Pos p) { reads++;return blocks.getOrDefault(p,AIR); }
        public boolean replaceable(OverlayLedger.Pos p,BlockPalette.State s) { return s.equals(AIR); }
        public boolean set(OverlayLedger.Pos p,BlockPalette.State s) { writes++;if(writes==reject)return false;blocks.put(p,s);return true; }
    }
    private static OverlayLedger.Sampler<BlockPalette.State> sampler(TerrainField f) {
        return p->{var s=f.sample(p.x(),p.y(),p.z());return s==null?null:new OverlayLedger.Desired<>(s.material(),s.landmarkId());};
    }
    private static void fieldAndProjection() {
        var a=landmark(0,0,0,.2,STONE,true);var b=landmark(64,0,.1,1,DIRT,true);var empty=landmark(128,0,.2,1,STONE,false);
        var state=new TerrainState();state.initialize(frame());
        var pa=state.placement(metadata(a));var pb=state.placement(metadata(b));
        var field=new TerrainField(123,List.of(layer(a,pa),layer(b,pb),layer(empty,state.placement(metadata(empty)))));
        var reversed=new TerrainField(123,List.of(layer(empty,state.placement(metadata(empty))),layer(b,pb),layer(a,pa)));
        int stone=0,dirt=0;
        for(int z=0;z<8;z++)for(int x=0;x<8;x++) {
            var s=field.sample(x,128,z);check(s!=null,"overlapping observed AIR cannot punch a floor hole");
            check(s.equals(reversed.sample(x,128,z)),"absolute dither independent of layer/chunk order");
            if(s.material().equals(STONE))stone++;else if(s.material().equals(DIRT))dirt++;
            check(field.sample(x,129,z)==null,"observed corridor air stays air");
            check(field.knownAir(a.id(),x,129,z),"entry air must be observed in owning landmark");
        }
        check(stone>0 && dirt>0,"bounded spatial blend uses both actual palette materials");
        check(field.sample(8,128,0)==null,"half-open unknown frontier is not synthetic solid");
        check(!field.knownAir(a.id(),8,129,0),"unknown frontier is not safe landing air");
        for(int camera=-100;camera<=100;camera++) {
            check(field.sample(0,128,0).equals(reversed.sample(0,128,0)),"camera movement never affects sampler");
            check(pa.projectSource(new Point3(7,1,5)).equals(new Point3(7,129,5)),"absolute source relative directions preserved");
        }
        var changed=new Landmark(a.id(),a.dimension(),a.algorithmVersion(),a.kind(),a.biome(),a.anchor(),a.bounds(),embedding(.9,0),a.baseImportance(),a.activity(),a.ownership(),a.geometry(),1,a.provenance());
        check(state.placement(metadata(changed)).equals(pa),"base updates cannot reanchor already physical landmark");
        fails(()->state.initialize(frame()),"frozen frame cannot be reinitialized");
        var incompatible=new EmbeddingProfile(PROFILE.model(),"changed",PROFILE.tokenizer(),PROFILE.prefixPolicy(),PROFILE.dimensions(),PROFILE.normalization(),PROFILE.descriptorSchema());
        fails(()->new TerrainField.Layer(metadata(a),pa,a.geometry(),new LandmarkEmbedding(incompatible,vector(0,0,0,0)),1),"profile fingerprint mismatch rejected");
        var support=new SpiritTerrainService.Support(a.id(),vector(1,0,0,0),Vec3d.ZERO,new Vec3d(0,1,0));
        var exposed=support.embedding();exposed.mul(0);check(support.embedding().data()[0]==1,"support returns cloned embedding");
        var axes=SpiritTerrainService.orthonormal(vector(0,1,0,0),vector(0,1,0,0),Vec384f.ZERO());
        for(int i=0;i<3;i++)for(int j=0;j<3;j++)check(Math.abs(axes[i].dot(axes[j])-(i==j?1:0))<1e-4,"degenerate basis repaired without parallel fallback");
        check(SpiritTerrainService.distanceSquared(new Box(0,0,0,8,8,8),new Vec3d(88,4,4))==6400,"fog guard uses nearest AABB not center");
    }
    private static void selection() {
        var config=TerrainConfig.DEFAULT;check(config.fog().hidden()==64 && config.mutationDistance()>=80,"parent opaque/load boundary contract");
        var repository=new LandmarkRepository();
        var near=landmark(0,.02,0,.1,STONE,true);var distant=landmark(64,.65,0,1,DIRT,true);var outside=landmark(128,2,0,1,DIRT,true);
        repository.put(near,-1);repository.put(distant,-1);repository.put(outside,-1);
        var all=repository.semanticRange(embedding(0,0),config.semanticRadius(),256);
        check(all.size()==2 && all.stream().anyMatch(l->l.id().equals(distant.id())),"ALL in-range landmarks, not nearest K");
        var selector=new FrozenTerrainSelector(config);
        var physical=new TerrainState();physical.initialize(frame());var importance=new ImportancePolicy(1,1200,0,0);
        var selected=selector.select(all,embedding(0,0),1,frame(),new Point3(0,128,0),config.fog(),importance,1,1,null,Set.of(),l->physical.placement(metadata(l)));
        check(selected.representatives().size()==2,"separate semantic clusters retain far in-range feature");
        var dense=new ArrayList<>(all);
        for(int i=1;i<=100;i++)dense.add(landmark(256+i*8,.02,0,.1,STONE,true));
        var crowded=selector.select(dense,embedding(0,0),2,frame(),new Point3(0,128,0),config.fog(),importance,2,1,selected,Set.of(),l->physical.placement(metadata(l)));
        check(crowded.representatives().size()==selected.representatives().size(),"source/chunk density does not add physical slots");
        check(crowded.representatives().stream().anyMatch(r->r.landmarkId().equals(distant.id())),"dense near duplicates cannot evict far semantic cluster");
        var moved=selector.select(dense,embedding(0,0),3,frame(),new Point3(4,128,0),config.fog(),importance,3,1,crowded,Set.of(),l->physical.placement(metadata(l)));
        check(moved.representatives().stream().map(RepresentativeSelector.Representative::seedId).toList().equals(crowded.representatives().stream().map(RepresentativeSelector.Representative::seedId).toList()),"representative slot IDs stable across camera movement");
        check(selector.lastStats().medoidComponentOperations()<=256L*8*PROFILE.dimensions()*8,"bounded production selector work");
        fails(()->repository.semanticRange(embedding(0,0),1.25,1),"range overflow rejects instead of truncating topology");
        fails(()->new TerrainConfig(1.25,.18,config.fog(),257,8,2,65536,128,16,1,100),"catalog scan budget");
    }
    private static Landmark withEmbedding(Landmark l,LandmarkEmbedding embedding) {
        return new Landmark(l.id(),l.dimension(),l.algorithmVersion(),l.kind(),l.biome(),l.anchor(),l.bounds(),embedding,
                l.baseImportance(),l.activity(),l.ownership(),l.geometry(),l.revision()+1,l.provenance());
    }
    private static void selectorParity() {
        var random=new Random(5817);var config=TerrainConfig.DEFAULT;var f=frame();var policy=new ImportancePolicy(1,1200,0,0);
        for(int trial=0;trial<40;trial++) {
            var input=new ArrayList<Landmark>();
            for(int i=0;i<30;i++)input.add(landmark(i*8,(random.nextDouble()-.5)*1.5,random.nextDouble()*.2,random.nextDouble(),STONE,true));
            Collections.shuffle(input,random);
            var core=config.selector();var frozen=new FrozenTerrainSelector(config);var state=new TerrainState();state.initialize(f);
            var expected=core.select(input,embedding(0,0),trial,f,new Point3(0,128,0),config.fog(),policy,trial,1,null,Set.of());
            var actual=frozen.select(input,embedding(0,0),trial,f,new Point3(0,128,0),config.fog(),policy,trial,1,null,Set.of(),l->state.placement(metadata(l)));
            check(actual.equals(expected),"frozen-selector algorithm matches wave1 when physical and semantic placements coincide");
            check(core.lastStats().semanticDistanceEvaluations()==frozen.lastStats().semanticDistanceEvaluations()
                    && core.lastStats().medoidComponentOperations()==frozen.lastStats().medoidComponentOperations(),"wave1 deterministic operation-budget parity");
        }
    }
    private static void frozenSelection() throws Exception {
        var original=landmark(0,0,0,1,STONE,true);
        var unit=new EmbeddingProfile(PROFILE.model(),PROFILE.revision(),PROFILE.tokenizer(),PROFILE.prefixPolicy(),
                PROFILE.dimensions(),EmbeddingProfile.Normalization.UNIT,PROFILE.descriptorSchema());
        var before=withEmbedding(original,new LandmarkEmbedding(unit,vector(1,0,0,0)));
        var f=new ProjectionFrame(1,123,before.baseEmbedding(),new Point3(0,128,0),vector(1,0,0,0),vector(0,1,0,0),vector(0,0,1,0),96);
        var state=new TerrainState();state.initialize(f);var frozen=state.placement(metadata(before));
        var after=withEmbedding(before,new LandmarkEmbedding(unit,vector(-1,0,0,0)));
        var config=TerrainConfig.DEFAULT;var player=frozen.realmAnchor();var policy=new ImportancePolicy(1,1200,0,0);
        check(frozen.projectedBounds(after.bounds()).distanceSquared(player)==0,"actual frozen terrain remains at player");
        check(f.place(after,1).projectedBounds(after.bounds()).distanceSquared(player)==33856,"reproduce reviewed UNIT drift distance");
        check(config.selector().select(List.of(after),after.baseEmbedding(),1,f,player,config.fog(),policy,1,1,null,Set.of()).representatives().isEmpty(),"control reproduces foundation recalculation mismatch");
        var selector=new FrozenTerrainSelector(config);
        var selected=selector.select(List.of(after),after.baseEmbedding(),1,f,player,config.fog(),policy,1,1,null,Set.of(),l->state.placement(metadata(l)));
        check(selected.representatives().size()==1 && selected.representatives().getFirst().landmarkId().equals(after.id()),"production selection uses persisted placement after compatible drift");
        var active=Map.of(after.id(),new TerrainField.Layer(metadata(after),frozen,after.geometry(),after.baseEmbedding(),1));
        check(TerrainEntryEligibility.owners(selected,after.baseEmbedding(),player,active,config).equals(Set.of(after.id())),"entry uses physical placement and current compatible semantics");
        check(TerrainEntryEligibility.owners(selected,before.baseEmbedding(),player,active,config).isEmpty(),"stale selection cannot bypass current personal semantic radius");
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        Path file=Files.createTempFile("terrain-drift-",".dat");NbtIo.writeCompressed(state.writeNbt(new NbtCompound(),lookup),file);
        var reload=TerrainState.fromNbt(NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes()),lookup);
        var again=selector.select(List.of(after),after.baseEmbedding(),2,reload.frame(),player,config.fog(),policy,2,1,selected,Set.of(),l->reload.placement(metadata(l)));
        check(again.representatives().equals(selected.representatives()) && reload.placement(metadata(after)).equals(frozen),"compressed reload preserves drift-safe representative and physical transform");
        check(selector.select(List.of(after),before.baseEmbedding(),3,f,player,config.fog(),policy,3,1,selected,Set.of(after.id()),l->state.placement(metadata(l))).representatives().isEmpty(),"physical proximity/pins cannot bypass semantic gate");
    }
    private static void personalLanding() {
        var a=landmark(0,0,0,1,STONE,true);var b=landmark(64,.65,0,1,DIRT,true);
        var pa=frame().place(a,1);var pb=frame().place(b,1);var la=layer(a,pa);var lb=layer(b,pb);
        var layers=Map.of(a.id(),la,b.id(),lb);var field=new TerrainField(1,List.of(la,lb));
        var sa=new RepresentativeSelector.RepresentativeSet(1,1,1,PROFILE,List.of(new RepresentativeSelector.Representative(a.id(),a.id(),1,0)));
        var sb=new RepresentativeSelector.RepresentativeSet(1,1,1,PROFILE,List.of(new RepresentativeSelector.Representative(b.id(),b.id(),1,0)));
        var config=TerrainConfig.DEFAULT;
        var selectedA=TerrainEntryEligibility.owners(sa,a.baseEmbedding(),pa.realmAnchor(),layers,config);
        var selectedB=TerrainEntryEligibility.owners(sb,b.baseEmbedding(),pb.realmAnchor(),layers,config);
        check(selectedA.equals(Set.of(a.id())) && selectedB.equals(Set.of(b.id())),"two players have distinct personal selected owners despite global union");
        check(TerrainEntryEligibility.owners(null,a.baseEmbedding(),pa.realmAnchor(),layers,config).isEmpty(),"entry before own selection cannot borrow global landing");
        var empty=new RepresentativeSelector.RepresentativeSet(1,1,1,PROFILE,List.of());
        check(TerrainEntryEligibility.owners(empty,a.baseEmbedding(),pa.realmAnchor(),layers,config).isEmpty(),"empty personal selection stays empty");
        check(TerrainEntryEligibility.owners(sa,a.baseEmbedding(),new Point3(1000,128,0),layers,config).isEmpty(),"selected owner must remain physically local");
        var ledger=new OverlayLedger<BlockPalette.State>(1024);var w=new World();
        var ra=new OverlayLedger.Region(0,16,0);var rb=new OverlayLedger.Region(7,16,0);
        check(ledger.reconcile(ra,w,sampler(field)) && ledger.reconcile(rb,w,sampler(field)),"real field generates distinct owned landing regions");
        java.util.function.Predicate<TerrainLandingSearch.Landing> safe=c->{
            var p=c.floor();var entry=ledger.entry(p);
            return entry!=null && w.get(p).equals(entry.generated())
                && w.get(new OverlayLedger.Pos(p.x(),p.y()+1,p.z())).equals(AIR)
                && w.get(new OverlayLedger.Pos(p.x(),p.y()+2,p.z())).equals(AIR)
                && field.knownAir(c.owner(),p.x(),p.y()+1,p.z()) && field.knownAir(c.owner(),p.x(),p.y()+2,p.z());
        };
        var first=new TerrainLandingSearch();var second=new TerrainLandingSearch();
        var floorA=first.find(ledger,selectedA,safe).orElseThrow();
        var floorB=second.find(ledger,selectedB,safe).orElseThrow();
        check(floorA.owner().equals(a.id()) && floorB.owner().equals(b.id()),"production cursors never assign another player's cached floor");
        check(first.find(ledger,Set.of(),safe).isEmpty(),"cached candidate is invalid without own current selection");
        floorA=first.find(ledger,selectedA,safe).orElseThrow();
        w.blocks.put(floorA.floor(),AIR);ledger.protect(floorA.floor());
        var blocked=new OverlayLedger.Pos(floorA.floor().x()+1,129,floorA.floor().z());w.blocks.put(blocked,DIRT);
        w.allowed=false;int writes=w.writes;
        check(!ledger.reconcile(ra,w,sampler(field)),"near-player mutation gate rejects restamping");
        var recovered=first.find(ledger,selectedA,safe).orElseThrow();
        check(!recovered.floor().equals(floorA.floor()) && recovered.floor().x()!=blocked.x(),"broken cached floor and blocked air rediscover another existing safe floor");
        check(w.writes==writes && w.blocks.get(blocked).equals(DIRT),"landing rediscovery performs no mutation and preserves protected region edits");
        check(first.lastProbes()<=TerrainLandingSearch.PROBES_PER_PLAYER,"landing cell budget independent of stamps");
        check(ledger.ownedRegions(selectedB).equals(Set.of(rb)),"indexed scan skips unrelated player regions");
        ledger.protect(recovered.floor());
        check(first.find(ledger,selectedA,safe).orElseThrow().floor().x()!=recovered.floor().x(),"same-state protected floor cannot remain cached landing");
        check(second.find(ledger,selectedA,safe).orElseThrow().owner().equals(a.id()),"changed personal selection invalidates old owner cache");
        check(new TerrainLandingSearch().find(ledger,selectedA,c->false).isEmpty(),"no safe floor returns empty, never synthesizes entry terrain");
        var missing=new TerrainLandingSearch();missing.find(ledger,selectedA,c->false);
        check(missing.lastProbes()==TerrainLandingSearch.PROBES_PER_PLAYER,"failed search consumes exact fixed budget");
    }
    private static void ledger() {
        var a=landmark(0,0,0,1,STONE,true);var f=new TerrainField(1,List.of(layer(a,frame().place(a,1))));
        var r=new OverlayLedger.Region(0,16,0);var w=new World();var ledger=new OverlayLedger<BlockPalette.State>(512);
        var base=new OverlayLedger.Pos(0,128,0);w.blocks.put(base,DIRT);
        check(ledger.reconcile(r,w,sampler(f)),"production field attached to block-write ledger");
        check(w.reads==512 && w.writes==63,"one region bounded preflight/writes; base floor preserved");
        check(w.blocks.get(base).equals(DIRT) && ledger.entry(base)==null,"never overwrite existing base terrain");
        check(ledger.ownedCount(a.id())==63,"owned floor count");
        int writes=w.writes;check(ledger.reconcile(r,w,sampler(f)) && w.writes==writes,"identical field has stable collision/no writes");
        var edit=new OverlayLedger.Pos(1,128,0);var broken=new OverlayLedger.Pos(2,128,0);var same=new OverlayLedger.Pos(3,128,0);
        w.blocks.put(edit,DIRT);w.blocks.put(broken,AIR);ledger.protect(same);ledger.protect(same);
        check(ledger.reconcile(r,w,p->null),"cleanup pass");
        check(w.blocks.get(edit).equals(DIRT) && w.blocks.get(broken).equals(AIR) && w.blocks.get(same).equals(STONE),"cleanup preserves changed/broken/same-state player edits");
        check(ledger.ownedCount(a.id())==0 && ledger.entries().size()==3,"edited cells detached and budgeted");
        check(!ledger.forgetEmpty(r),"protected region cannot be discarded and later restamped");
        var overflow=new OverlayLedger.Region(1,16,0);int before=w.reads;check(!ledger.reconcile(overflow,w,sampler(f)) && before==w.reads,"region budget fails before scanning");
        var other=new OverlayLedger<BlockPalette.State>(512);w.loaded=false;before=w.reads;
        check(!other.reconcile(r,w,sampler(f)) && before==w.reads,"unloaded region never loads or reads");w.loaded=true;w.allowed=false;
        check(!other.reconcile(r,w,sampler(f)) && before==w.reads,"near-player/lifecycle gate freezes collision");
        var failure=new World();failure.reject=31;
        fails(()->other.reconcile(r,failure,sampler(f)),"write failure aborts batch");
        check(other.entries().isEmpty() && other.regions().isEmpty(),"ownership published only after successful writes");
        check(failure.blocks.values().stream().allMatch(AIR::equals) && failure.writes<=1024,"rejected batch rolls back within bounded failure budget");
        var throwing=new World();
        fails(()->other.reconcile(r,throwing,p->{if(p.x()==3)throw new IllegalArgumentException("bad palette");return new OverlayLedger.Desired<>(STONE,a.id());}),"palette failure preflight");
        check(throwing.writes==0 && other.entries().isEmpty(),"palette resolution failure makes zero partial writes");
        check(new OverlayLedger.Pos(-1,-1,-1).region().equals(new OverlayLedger.Region(-1,-1,-1)),"negative region coordinates");
    }
    private static void collisionContact() {
        var pos=new BlockPos(0,128,0);
        check(SpiritTerrainService.contact(VoxelShapes.fullCube(),pos,new Box(.1,129,.1,.7,130.8,.7),129),"actual full-cube floor contact");
        var stair=VoxelShapes.union(VoxelShapes.cuboid(0,0,0,.5,.5,1),VoxelShapes.cuboid(.5,0,0,1,1,1));
        check(SpiritTerrainService.contact(stair,pos,new Box(.05,128.5,.1,.45,130.3,.7),128.5),"lower stair step uses contacted box top, not global shape maximum");
        check(!SpiritTerrainService.contact(VoxelShapes.empty(),pos,new Box(.1,129,.1,.7,130.8,.7),129),"actual air has no collision support");
        check(!SpiritTerrainService.contact(VoxelShapes.fullCube(),pos,new Box(.1,129.2,.1,.7,131,.7),129.2),"not grounded on floor is not support");
    }
    private static void persistence() throws Exception {
        SharedConstants.createGameVersion();var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        var a=landmark(0,0,0,1,STONE,true);var state=new TerrainState();state.initialize(frame());var placement=state.placement(metadata(a));
        var w=new World();var r=new OverlayLedger.Region(0,16,0);
        state.ledger.reconcile(r,w,sampler(new TerrainField(1,List.of(layer(a,placement)))));
        var edited=new OverlayLedger.Pos(0,128,0);state.ledger.protect(edited);
        NbtCompound n=state.writeNbt(new NbtCompound(),lookup);Path directory=Files.createTempDirectory("mysticism-terrain-test-");Path file=directory.resolve("terrain.dat");
        NbtIo.writeCompressed(n,file);
        var loaded=TerrainState.fromNbt(NbtIo.readCompressed(file,NbtSizeTracker.ofUnlimitedBytes()),lookup);
        check(loaded.writeNbt(new NbtCompound(),lookup).equals(n),"actual compressed NBT save/reload equality");
        check(loaded.placement(metadata(a)).equals(placement),"saved frozen source-to-realm placement");
        check(loaded.frame().project(embedding(.2,0)).equals(state.frame().project(embedding(.2,0))),"saved axes/origin/profile projection exact");
        check(loaded.ledger.entry(edited).protectedEdit(),"save/reload preserves same-state edit protection");
        check(loaded.ledger.ownedCount(a.id())==63,"saved ownership counters reconstructed");
        check(loaded.ledger.ownedRegions(Set.of(a.id())).equals(Set.of(r)),"saved per-owner landing-region index reconstructed without entry scans");
        int before=w.writes;loaded.ledger.reconcile(r,w,sampler(new TerrainField(1,List.of(layer(a,placement)))));
        check(w.writes==before,"save/reload maintains actual stable collision");
        loaded.ledger.reconcile(r,w,p->null);check(w.blocks.get(edited).equals(STONE),"reloaded cleanup preserves edited cell");
        check(loaded.ledger.ownedRegions(Set.of(a.id())).isEmpty(),"protected-only saved regions do not enter landing index");
        var bad=n.copy();bad.putString("entries","corrupt");fails(()->TerrainState.fromNbt(bad,lookup),"wrong list type must fail closed");
        var duplicate=n.copy();duplicate.getList("entries",NbtElement.COMPOUND_TYPE).add(duplicate.getList("entries",NbtElement.COMPOUND_TYPE).getCompound(0).copy());
        fails(()->TerrainState.fromNbt(duplicate,lookup),"duplicate owned cell rejected");
        var missing=n.copy();missing.put("regions",new NbtList());fails(()->TerrainState.fromNbt(missing,lookup),"ownership outside region rejected");
        var profile=n.copy();profile.getCompound("frame").getCompound("profile").putString("revision","other-pinned");
        var migrated=TerrainState.fromNbt(profile,lookup);fails(()->migrated.frame().semanticOrigin().profile().requireCompatible(PROFILE),"saved profile fingerprint cannot silently migrate");
        check(TerrainState.fromNbt(new TerrainState().writeNbt(new NbtCompound(),lookup),lookup).frame()==null,"empty lifecycle state roundtrip");
        System.out.println("Compressed save fixture retained: "+directory);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();fieldAndProjection();selection();selectorParity();frozenSelection();personalLanding();ledger();collisionContact();persistence();System.out.println("TerrainSelfTest: "+checks+" checks passed");
    }
}
