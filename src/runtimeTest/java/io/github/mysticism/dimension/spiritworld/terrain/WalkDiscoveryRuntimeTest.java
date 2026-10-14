package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService.*;

/** Runs discovery completion -> contact candidate -> source proof -> shallow acquisition, not just
 * attachment policy or bytecode checks. Only server I/O (metadata/ownership/region reads, mesh build
 * and transport) is supplied by a deterministic fixture; candidate mapping, clearance, continuity,
 * session commit and frame-publication rollback are the production implementations. No game boot. */
public final class WalkDiscoveryRuntimeTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final String DIM="minecraft:overworld";
    private static final BlockPos FLOOR=new BlockPos(100,63,200),FEET=FLOOR.up(),HEAD=FEET.up();
    private static final Vec3d SOURCE=new Vec3d(100.5,64,200.5),PLAYER=new Vec3d(.5,1,.5);
    private static final Box BODY=new Box(.2,1,.2,.8,2.8,.8),UNIT=new Box(0,0,0,1,1,1);
    private static final TerrainMeshFrame.Material STONE=new TerrainMeshFrame.Material("minecraft:stone",Map.of());
    private static final SourceMeshBuilder.Tile SOLID=new SourceMeshBuilder.Tile(STONE,List.of(UNIT),0xffffff,0xf000f0,false,true);
    private static final SourceMeshBuilder.Tile AIR=new SourceMeshBuilder.Tile(new TerrainMeshFrame.Material("minecraft:air",Map.of()),List.of(),0xffffff,0xf000f0,true,false);

    private static LandmarkMetadata metadata(String name,long revision,String dimension) {
        BlockPoint anchor=new BlockPoint(100,64,200);String algorithm="walk-runtime-"+name;
        String id=LandmarkIds.seed(dimension,algorithm,Landmark.Kind.CAVE,"minecraft:plains",anchor);
        float[] unit=new float[EmbeddingSpace.DIMENSIONS];unit[0]=1;
        Landmark header=new Landmark(id,dimension,algorithm,Landmark.Kind.CAVE,"minecraft:plains",anchor,
                new Bounds(96,60,196,108,72,208),LandmarkProfiles.wrap(new Vec384f(unit)),.2,
                new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),revision,"walk runtime fixture");
        return new LandmarkMetadata(header,List.of("mysticism.landmark.geometry."+id+"."+revision));
    }
    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d axisZ,String owner) {
        return new TerrainMeshFrame.Cell(key,0,owner,new Vec3d(100,63,200),min,new Vec3d(1,1,1),
                new Vec3d(1,0,0),new Vec3d(0,1,0),axisZ,0xffffff,0xf000f0,1,List.of(UNIT));
    }
    private static TerrainMeshFrame frame(long revision,boolean shallow,List<TerrainMeshFrame.Cell> cells) {
        return new TerrainMeshFrame(revision,shallow,DIM,SOURCE,PLAYER,List.of(STONE),cells);
    }

    private static final class Fixture implements WalkEnvironment {
        final LandmarkMetadata a=WalkDiscoveryRuntimeTest.metadata("A",0,DIM),b=WalkDiscoveryRuntimeTest.metadata("B",0,DIM);
        final Map<String,LandmarkMetadata> metadata=new HashMap<>();
        final Map<BlockPos,String> owners=new HashMap<>();
        final Basis384f grid=new Basis384f();final Vec384f q;
        final Window producer;final Session session;
        boolean ownershipCurrent=true,proofReady=true,failTransport,obstruction,discontinuous,hasGround=true;
        long ownerRevision=7;int proofRequests,published,failures;
        Fixture(boolean regionProducer) {
            float[] values=new float[EmbeddingSpace.DIMENSIONS];values[0]=3;values[1]=4;values[767]=2;q=new Vec384f(values);
            metadata.put(a.id(),a);metadata.put(b.id(),b);
            producer=new Window(DIM,SOURCE,q,grid,a.id());producer.owner=a;
            producer.supportEmbedding=a.header().baseEmbedding().vector();
            session=new Session(regionProducer?new Window(DIM,SOURCE,q,grid,"entry"):producer,PLAYER);
            session.shallow=false;session.anchorDelivered=true;
            if(regionProducer)session.regions.put(a.id(),producer);
            // Non-orthogonal displayed floor, still with a horizontal normal and an exact inverse.
            var floor=cell(11,new Vec3d(-.1,0,0),new Vec3d(.2,0,1),b.id());
            session.frame=frame(3,false,List.of(floor));session.revision=3;session.frameProducers=Map.of(11L,producer);
            owners.put(FLOOR,b.id());owners.put(FEET,b.id());owners.put(HEAD,b.id());
        }
        boolean discover(){return completeWalkDiscovery(session,producer,b,b.header().baseEmbedding().vector());}
        Optional<WalkCandidate> candidate(){return walkCandidate(session,this);}
        WalkSupport support(){return candidate().orElseThrow(()->new AssertionError("discovered exact owner must be a walking candidate")).support();}
        public boolean semanticReady(){return true;}
        public Vec3d position(){return PLAYER;}
        public Box boundingBox(){return BODY;}
        public Basis384f basis(){return grid.clone();}
        public Vec384f coordinate(){return q.clone();}
        public Optional<MeshCollision.Hit> ground(){return hasGround?new MeshCollision.Index(session.frame).sweep(BODY.offset(0,.025,0),new Vec3d(0,-.15,0)):Optional.empty();}
        public Optional<LandmarkMetadata> metadata(String id){return Optional.ofNullable(metadata.get(id));}
        public Optional<String> exactOwner(Window w,Vec3d at){return ownershipCurrent?Optional.ofNullable(owners.get(BlockPos.ofFloored(at))):Optional.empty();}
        public long ownershipRevision(Window w){return ownerRevision;}
        public void ensureProof(Window w,WalkSupport support) {
            if(session.walkProbe!=null)return;
            proofRequests++;
            Window probe=new Window(support.sourceDimension(),support.sourcePosition(),support.sourceCoordinate(),support.sourceBasis(),support.landmarkId());
            probe.owner=metadata.get(support.landmarkId());probe.ready=proofReady;
            probe.tiles.put(FLOOR,SOLID);probe.tiles.put(FEET,AIR);probe.tiles.put(HEAD,AIR);
            session.walkProbe=probe;session.walkField=w.identity;
        }
        public void refreshBody(Window probe,Vec3d at) { /* Fixture region already contains every floor/body sample. */ }
        public List<SourceMeshBuilder.Node> compact(Window w){return SourceMeshBuilder.compact(w.tiles,w.origin,owners);}
        public BuiltMesh build(Session preview,long revision,Basis384f basis) {
            check(preview.shallow && preview.acquiredView && preview.local.id.equals(b.id()),"preview must acquire B, not rebind A");
            check(preview.local.semantic.squareDistance(q)==0,"preview must retain non-unit full native semantic coordinate");
            var cells=new ArrayList<TerrainMeshFrame.Cell>();var producers=new HashMap<Long,Window>();
            for(var node:preview.local.nodes) {
                check(node.ownerId().equals(b.id()),"near compaction retains exact source owner B");
                var source=new Vec3d(node.position().getX(),node.position().getY(),node.position().getZ());
                var c=cell(11,PLAYER.add(source.subtract(preview.local.origin)),new Vec3d(0,0,1),node.ownerId());
                cells.add(c);producers.put(c.key(),preview.local);
            }
            if(obstruction){var c=cell(12,new Vec3d(0,1.1,0),new Vec3d(0,0,1),b.id());cells.add(c);producers.put(12L,preview.local);}
            if(discontinuous){var c=cell(12,new Vec3d(0,-2,0),new Vec3d(0,0,1),b.id());cells.add(c);producers.put(12L,preview.local);}
            return new BuiltMesh(frame(revision,true,cells),Map.copyOf(producers));
        }
        public void accept(BuiltMesh mesh){acceptFrame(session,mesh,f->{if(failTransport)throw new IllegalStateException("transport fixture failure");published++;});}
        public void publicationFailed(){failures++;}
    }

    private static void crossOwnerAcquisition(boolean regionProducer) {
        Fixture f=new Fixture(regionProducer);Window old=f.session.local;
        Vec384f semantic=f.producer.semantic.clone(),q=f.q.clone(),embedding=f.producer.supportEmbedding.clone();
        check(f.candidate().isEmpty(),"undiscovered foreign owner is not yet a candidate");
        check(f.discover(),"discovery completion accepted for retained producer");
        check(f.producer.id.equals(f.a.id()) && f.producer.owner==f.a,"KEEP_BINDING keeps A metadata and identity");
        check(f.producer.semantic.squareDistance(semantic)==0 && f.producer.supportEmbedding.squareDistance(embedding)==0,"B discovery does not move or re-embed A");
        check(f.session.walkTarget.metadata()==f.b && f.session.walkTarget.field()==f.producer,"B is retained separately on its contacted producer");
        WalkSupport support=f.support();
        check(support.landmarkId().equals(f.b.id()) && support.sourcePosition().distanceTo(SOURCE)<1e-9,"inverse affine contact resolves B source feet");
        check(support.sourceCoordinate().squareDistance(q)<1e-12,"candidate uses producer placement, not B embedding attraction");
        check(f.proofRequests==1,"candidate starts real acquisition proof stage once");
        check(acquireCurrentSupport(f.session,f,support),"A-produced B terrain must successfully acquire B after discovery");
        check(f.session.shallow && f.session.acquiredView && f.session.local.id.equals(f.b.id()),"successful transaction enters B shallow source mapping");
        check(f.session.local.origin.distanceTo(SOURCE)<1e-9 && f.session.carrier.equals(PLAYER),"source/carrier mapping stays continuous");
        check(f.producer.id.equals(f.a.id()) && f.producer.owner==f.a,"successful acquisition still does not overwrite A producer");
        check(f.session.regions.containsValue(old),"old local view survives as an independent region");
        check(f.published==1 && f.session.revision==4 && f.session.frame.shallow(),"accepted frame published exactly once");
        check(f.session.frameProducers.get(11L)==f.session.local,"published floor provenance belongs to new B window");
        check(f.session.walkTarget==null && f.session.walkProbe==null,"successful request clears transient candidate/proof");
        check(f.q.squareDistance(q)==0 && f.grid.i.squareDistance(support.sourceBasis().i)==0,"acquisition does not mutate q or basis");
    }

    private static void candidateInvalidation() {
        Fixture f=new Fixture(false);f.discover();check(f.candidate().isPresent(),"setup has a discovered B candidate");
        f.ownershipCurrent=false;check(f.candidate().isEmpty(),"stale ownership cannot authorize discovered target");f.ownershipCurrent=true;
        f.owners.put(FLOOR,f.a.id());check(f.candidate().isEmpty(),"contact and body must agree on one exact owner");f.owners.put(FLOOR,f.b.id());
        f.owners.put(HEAD,f.a.id());check(f.candidate().isEmpty(),"foreign body sample rejects B");f.owners.put(HEAD,f.b.id());
        f.metadata.remove(f.b.id());check(f.candidate().isEmpty(),"removed discovered landmark rejects candidate");
        f.metadata.put(f.b.id(),metadata("B",1,DIM));check(f.candidate().isEmpty(),"changed geometry invalidates completed discovery");
        var fresh=f.metadata.get(f.b.id());check(completeWalkDiscovery(f.session,f.producer,fresh,f.q),"fresh rediscovery can replace only walk target");
        check(f.candidate().isPresent() && f.producer.owner==f.a,"fresh B revision restores candidate without touching A");
        f.hasGround=false;check(f.candidate().isEmpty(),"candidate cannot outlive visible ground contact");f.hasGround=true;
        var original=f.producer;f.session.local=new Window(DIM,SOURCE,f.q,f.grid,f.a.id());
        check(f.candidate().isEmpty(),"held frame cannot borrow an evicted producer's candidate");
        check(!completeWalkDiscovery(f.session,original,f.b,f.q),"late completion for evicted producer is ignored");
        Fixture other=new Fixture(false);check(other.candidate().isEmpty(),"discovery is per-player/session, not global");
        check(!completeWalkDiscovery(other.session,other.producer,metadata("foreign",0,"minecraft:the_nether"),other.q),"wrong source dimension cannot attach");
        other.discover();other.session.walkFuture=new CompletableFuture<>();other.session.walkDiscoveryFuture=new CompletableFuture<>();
        var proof=other.session.walkFuture;var discovery=other.session.walkDiscoveryFuture;
        cancelCurrentSupport(other.session);
        check(other.session.walkTarget==null && other.session.walkProbe==null && proof.isCancelled() && discovery.isCancelled(),"expiry/cancel clears candidate and both pending stages");
        check(other.candidate().isEmpty(),"cancelled target cannot authorize another request");
    }

    private static void rejects(java.util.function.Consumer<Fixture> change,String why) {
        Fixture f=new Fixture(false);f.discover();WalkSupport support=f.support();Window local=f.session.local;
        TerrainMeshFrame frame=f.session.frame;Map<Long,Window> producers=f.session.frameProducers;
        change.accept(f);
        check(!acquireCurrentSupport(f.session,f,support),why);
        check(!f.session.shallow && f.session.local==local && f.published==0,"rejected acquisition leaves deep mapping intact: "+why);
        check(f.session.frame==frame && f.session.frameProducers==producers && f.session.revision==3,"rejection preserves accepted frame/provenance: "+why);
    }
    private static void acquisitionGuardsAndRollback() {
        rejects(f->f.ownerRevision++,"ownership revision changed since current support snapshot");
        rejects(f->f.session.walkProbe.ready=false,"extraction still in flight");
        rejects(f->f.session.walkProbe.owner=metadata("B",1,DIM),"proof geometry no longer matches discovered owner");
        rejects(f->f.session.walkProbe.id=f.a.id(),"proof cannot belong to another owner");
        rejects(f->f.session.walkProbe.tiles.remove(HEAD),"unknown body cell is not clearance");
        rejects(f->f.session.walkProbe.tiles.put(HEAD,SOLID),"solid source body blocks acquisition");
        rejects(f->f.session.walkProbe.tiles.remove(FLOOR),"unknown floor is not support");
        rejects(f->f.session.walkProbe.tiles.put(FLOOR,AIR),"air source floor is not support");
        rejects(f->f.q.add(f.grid.i.clone().mul(.1f)),"semantic coordinate discontinuity blocks acquisition");
        rejects(f->{Vec384f i=f.grid.i;f.grid.i=f.grid.k;f.grid.k=i;},"source grid mismatch blocks acquisition");
        rejects(f->f.obstruction=true,"proposed visible geometry intersects body");
        Fixture transition=new Fixture(false);transition.discover();var support=transition.support();
        var oldFloor=transition.session.frame.cells().getFirst();
        transition.session.frame=frame(3,false,List.of(oldFloor,cell(12,new Vec3d(0,4,0),new Vec3d(0,0,1),transition.b.id())));
        transition.discontinuous=true;
        check(!acquireCurrentSupport(transition.session,transition,support) && !transition.session.shallow,"clear endpoints cannot bypass continuous swept mesh/body guard");
        Fixture rollback=new Fixture(false);rollback.discover();support=rollback.support();
        var oldFrame=rollback.session.frame;var oldProducers=rollback.session.frameProducers;rollback.failTransport=true;
        check(!acquireCurrentSupport(rollback.session,rollback,support),"publication failure is not successful acquisition");
        check(rollback.session.local==rollback.producer && !rollback.session.shallow && rollback.session.regions.isEmpty(),"failed publication rolls back source mapping and regions");
        check(rollback.session.frame==oldFrame && rollback.session.frameProducers==oldProducers && rollback.session.revision==3,"failed publication rolls back accepted mesh/revision/provenance");
        check(rollback.session.walkTarget!=null && rollback.session.walkProbe!=null && rollback.failures==1,"failed transaction retains retryable B target/proof");
        rollback.failTransport=false;
        check(acquireCurrentSupport(rollback.session,rollback,support),"same discovered target succeeds on publication retry, without rediscovery");
    }
    private static void naturalContactBeforeDiscovery() {
        Fixture f = new Fixture(false);
        check(f.ground().isPresent() && f.candidate().isEmpty(),
                "Real displayed support can exist before its walking target is discovered");
        var intent = io.github.mysticism.navigation.NaturalLandingPolicy.evaluate(
                true, true, false, false, false, true,
                io.github.mysticism.navigation.NaturalLandingPolicy.REST_TICKS - 1);
        check(intent == io.github.mysticism.navigation.NaturalLandingPolicy.Action.LAND,
                "Settled mesh contact starts validation even without a current owned candidate");
        check(!f.session.shallow && f.published == 0, "Contact intent alone grants no walking authority");
        check(f.discover(), "Natural contact acquires the real separate source owner");
        var support = f.support();
        check(acquireCurrentSupport(f.session, f, support), "Discovered and proven natural support acquires shallow view");
        check(f.session.local.id.equals(f.b.id()) && f.producer.id.equals(f.a.id()),
                "Natural acquisition retains independent producer ownership and current source target");
    }

    /** Deterministic projection I/O: same source-offset/observer-column map as append(),
     * with a fixed producer placement and q. No hand-authored aligned replacement frame. */
    private static BuiltMesh projectSupport(Fixture f,Basis384f observer) {
        Basis384f source=f.producer.sourceBasis;
        Vec3d ax=new Vec3d(source.i.dot(observer.i),source.i.dot(observer.j),source.i.dot(observer.k)),
                ay=new Vec3d(source.j.dot(observer.i),source.j.dot(observer.j),source.j.dot(observer.k)),
                az=new Vec3d(source.k.dot(observer.i),source.k.dot(observer.j),source.k.dot(observer.k));
        Vec3d sourceMin=new Vec3d(FLOOR.getX(),FLOOR.getY(),FLOOR.getZ()),offset=sourceMin.subtract(f.producer.origin);
        Vec3d min=PLAYER.add(ax.multiply(offset.x)).add(ay.multiply(offset.y)).add(az.multiply(offset.z));
        var floor=new TerrainMeshFrame.Cell(11,0,f.b.id(),sourceMin,min,new Vec3d(1,1,1),
                ax,ay,az,0xffffff,0xf000f0,1,List.of(UNIT));
        return new BuiltMesh(frame(f.session.revision+1,false,List.of(floor)),Map.of(11L,f.producer));
    }

    private static void tiltedContactDoesNotBypassAcquisition() {
        Fixture f=new Fixture(false);
        var source=f.producer.sourceBasis.clone();
        double angle=Math.toRadians(10);float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        f.grid.i=source.i.clone().mul(c).sub(source.j.clone().mul(s));
        f.grid.j=source.i.clone().mul(s).add(source.j.clone().mul(c));
        var from=f.grid.clone();
        // A real .6-wide resting body touches the high edge of the 10-degree floor.
        // Its source feet remain .052 blocks above the floor as the frame levels.
        f.producer.origin=SOURCE.add(0,.3*s,0);
        var initial=projectSupport(f,from);
        acceptFrame(f.session,initial,ignored->{});
        var hit=f.ground().orElseThrow();
        check(Math.abs(hit.normal().y-Math.cos(angle))<1e-6 && hit.normal().y<.99,
                "measured real 10-degree source-flat contact starts below final acquisition normal");
        check(MeshCollision.bodyClear(f.session.frame,BODY),"tilted floor does not actually penetrate resting body");
        check(f.session.frame.cells().getFirst().bounds().intersects(BODY.expand(-1e-5)),
                "regression reproduces the conservative AABB false overlap");
        check(f.discover(),"tilted contact discovers real separate source owner");
        var support=f.support();
        check(!acquireCurrentSupport(f.session,f,support) && !f.session.shallow && f.published==0,
                "discovery/proof alone cannot authorize walking on unaligned support");
        Vec384f unchangedQ=f.q.clone();Vec3d unchangedOrigin=f.producer.origin;
        for(int step=1;step<=40;step++) {
            var proposed=io.github.mysticism.activity.TraversalSteering.blend(from,source,step/40f);
            BuiltMesh built=projectSupport(f,proposed);
            check(canAlign(f.session,built.frame(),BODY),"actual navigation alignment guard advances at step "+step);
            check(MeshCollision.transitionClear(f.session.frame,built.frame(),BODY),
                    "actual publication guard accepts the same continuous step "+step);
            // Basis application follows the guard, then the normal frame-publication transaction.
            f.grid.i=proposed.i;f.grid.j=proposed.j;f.grid.k=proposed.k;
            f.accept(built);
            check(!f.session.shallow && f.ground().isPresent(),"flight retains real support during alignment "+step);
            check(f.support().landmarkId().equals(f.b.id()),"support ownership survives frame transition "+step);
        }
        support=f.support();
        check(support.normal().y>=.99 && f.published==40,"alignment makes progress and publishes before acquisition");
        check(support.sourcePosition().y>64.05 && support.sourcePosition().y<64.12,
                "resting high-edge contact needs the existing full source-floor snap tolerance");
        check(acquireCurrentSupport(f.session,f,support),"guarded tilted-to-aligned transition acquires real support");
        check(f.session.shallow && f.session.local.id.equals(f.b.id()) && f.producer.id.equals(f.a.id()),
                "post-alignment acquisition keeps source ownership independent");
        check(f.published==41 && f.q.squareDistance(unchangedQ)==0 && f.producer.origin.equals(unchangedOrigin),
                "no semantic attraction, source-placement rewrite or skipped publication to force landing");
    }

    private static void sourceFloorContactBandStillNeedsProof() {
        for(double gap:new double[]{.11,.121}) {
            Fixture f=new Fixture(false);
            f.producer.origin=SOURCE.add(0,gap,0);
            acceptFrame(f.session,projectSupport(f,f.grid),ignored->{});
            check(f.ground().isPresent(),"visible mesh ground probe includes source gap "+gap);
            check(f.discover(),"contact-band fixture discovers real owner");
            var support=f.support();
            check(acquireCurrentSupport(f.session,f,support)==(gap<.12),
                    "source-floor proof retains the existing .12 tolerance, not the broader mesh probe: "+gap);
        }
        Fixture unknown=new Fixture(false);
        unknown.producer.origin=SOURCE.add(0,.11,0);
        acceptFrame(unknown.session,projectSupport(unknown,unknown.grid),ignored->{});
        unknown.discover();var support=unknown.support();unknown.session.walkProbe.tiles.remove(FLOOR);
        check(!acquireCurrentSupport(unknown.session,unknown,support),
                "full contact-band lookup does not turn missing source proof into a floor");
        Fixture blocked=new Fixture(false);blocked.discover();support=blocked.support();
        var accepted=blocked.session.frame;var producers=blocked.session.frameProducers;
        var obstacle=cell(12,new Vec3d(0,1.1,0),new Vec3d(0,0,1),blocked.b.id());
        check(!canAlign(blocked.session,frame(4,false,List.of(accepted.cells().getFirst(),obstacle)),BODY),
                "alignment remains held when proposed scene contains a real body obstruction");
        check(blocked.session.frame==accepted && blocked.session.frameProducers==producers && blocked.published==0,
                "held alignment does not advance accepted frame/provenance or publication");
    }

    public static void main(String[] args) {
        tiltedContactDoesNotBypassAcquisition();
        sourceFloorContactBandStillNeedsProof();
        naturalContactBeforeDiscovery();
        crossOwnerAcquisition(false);crossOwnerAcquisition(true);
        candidateInvalidation();acquisitionGuardsAndRollback();
        System.out.println("WalkDiscoveryRuntimeTest: "+checks+" checks passed");
    }
}
