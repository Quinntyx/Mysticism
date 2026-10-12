package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.MeshTransitionClearanceTest;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import java.util.List;
import java.util.Random;

/**
 * Walk-request acceptance regressions over the REAL production placement/alignment math.
 *
 * Deep flight accumulates semantic drift outside the source grid span (movement is lifted through
 * the rotating personal basis). Left unrebased, that drift re-projects under every blended basis
 * and TRANSLATES the visible scene during the guarded alignment, sweeping the supporting floor up
 * through the stationary body so the terrain guard refuses every step and every valid walk request
 * expires without an accepted transition. These checks pin the placement-neutral support-pivot
 * re-anchor and the refusal-adaptive guarded alignment schedule.
 */
public final class WalkRequestAcceptanceTest {
    private static final double SCALE = SpiritTerrainService.SCALE;
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value)throw new AssertionError(why); }

    public static void main(String[] args) {
        pivotReanchorPlacementNeutral();
        pivotReanchorKeepsContactAtPlayerDuringAlignment();
        unrebasedDriftTranslatesSceneThroughPlayer();
        pivotSemanticMakesAcquisitionResidualExactlyZero();
        alignmentScheduleConvergesBounded();
        alignmentScheduleAdaptsToRefusals();
        supportedAlignmentPassesProductionContinuityGuard();
        System.out.println("Walk request acceptance self-tests passed: pivot re-anchor, guarded adaptive alignment (" + checks + " checks)");
    }

    /** Deterministic orthonormal 768-dim basis from seeded random vectors (Gram-Schmidt). */
    private static Basis384f basis(Random random) {
        Vec384f i=unit(random),j=orthonormal(random,unit(random),i),k=orthonormal(random,unit(random),i,j);
        return new Basis384f(i,j,k);
    }
    private static Vec384f unit(Random random) {
        float[] v=new float[768];double sum=0;
        for(int d=0;d<v.length;d++){v[d]=(float)random.nextGaussian();sum+=v[d]*(double)v[d];}
        float length=(float)Math.sqrt(sum);
        for(int d=0;d<v.length;d++)v[d]/=length;
        return new Vec384f(v);
    }
    private static Vec384f orthonormal(Random random,Vec384f v,Vec384f... against) {
        Vec384f result=v.clone();
        for(Vec384f a:against) {
            float denom=a.dot(a);
            if(denom>1e-12f)result=result.sub(a.clone().mul(result.dot(a)/denom));
        }
        float length=result.length();
        return length<1e-6f?unit(random):result.mul(1f/length);
    }

    /**
     * A deep observation state: grid-external drift visible to the player's own projection but
     * invisible to the destination grid (the accumulate-while-flying drift the re-anchor removes).
     */
    private static Vec384f driftedSemantic(Vec384f q,Basis384f grid,Basis384f observer,Random random) {
        Vec384f drift=orthonormal(random,observer.i,grid.i,grid.j,grid.k).mul(.15f);
        return q.clone().add(drift);
    }
    /** A moderately rotated personal basis, like a few blocks of deep flight away from the grid. */
    private static Basis384f flownBasis(Random random,Basis384f grid) {
        return TraversalSteering.blend(grid,basis(random),.35f);
    }
    private static Vec3d origin(Random random) {return new Vec3d(random.nextInt(64)-32,64,random.nextInt(64)-32);}
    private static Vec3d player(Random random) {return new Vec3d(random.nextDouble()*8-4,70,random.nextDouble()*8-4);}

    private static Vec3d placed(Vec3d player,Vec384f semantic,Vec384f q,Basis384f observer,Basis384f grid,Vec3d origin,Vec3d source) {
        return SpiritTerrainService.frameCellMin(player,semantic,q,observer,grid,origin,source);
    }

    /** The re-anchor must not move the visible scene at the current basis: placement-neutral. */
    private static void pivotReanchorPlacementNeutral() {
        Random random=new Random(20240517);
        for(int trial=0;trial<8;trial++) {
            Basis384f observer=basis(random),grid=basis(random);
            Vec384f q=unit(random).mul(.5f);
            Vec3d origin=origin(random),player=player(random);
            Vec384f semantic=driftedSemantic(q,grid,observer,random);
            Vec3d feet=SpiritTerrainService.supportContactFeet(semantic,q,grid,observer,origin);
            Vec384f reanchored=SpiritTerrainService.supportPivotSemantic(q,grid,feet,origin);
            for(int dx=-2;dx<=2;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-2;dz<=2;dz++) {
                Vec3d source=new Vec3d(feet.x+dx,feet.y+dy,feet.z+dz);
                check(placed(player,semantic,q,observer,grid,origin,source)
                        .distanceTo(placed(player,reanchored,q,observer,grid,origin,source))<1e-3,
                        "support pivot re-anchor is placement-neutral at the current basis");
            }
            check(placed(player,semantic,q,observer,grid,origin,feet).distanceTo(player)<1e-2,
                    "support contact feet inverts the deep placement at the player");
        }
    }

    /** With the re-anchor, the request-time contact stays exactly at the player for EVERY blended basis. */
    private static void pivotReanchorKeepsContactAtPlayerDuringAlignment() {
        Random random=new Random(20240518);
        for(int trial=0;trial<8;trial++) {
            Basis384f grid=basis(random),observer=flownBasis(random,grid);
            Vec384f q=unit(random).mul(.5f);
            Vec3d origin=origin(random),player=player(random);
            Vec384f semantic=driftedSemantic(q,grid,observer,random);
            Vec3d feet=SpiritTerrainService.supportContactFeet(semantic,q,grid,observer,origin);
            Vec384f reanchored=SpiritTerrainService.supportPivotSemantic(q,grid,feet,origin);
            for(int step=1;step<=SupportAlignment.FULL_STEPS;step++) {
                Basis384f blended=TraversalSteering.blend(observer,grid,step/(float)SupportAlignment.FULL_STEPS);
                check(placed(player,reanchored,q,blended,grid,origin,feet).distanceTo(player)<1e-2,
                        "re-anchored alignment pivots the request-time contact at the player");
            }
        }
    }

    /** The defect these fixes remove: unrebased drift sweeps the contact point away from the player. */
    private static void unrebasedDriftTranslatesSceneThroughPlayer() {
        Random random=new Random(20240519);
        for(int trial=0;trial<8;trial++) {
            Basis384f grid=basis(random),observer=flownBasis(random,grid);
            Vec384f q=unit(random).mul(.5f);
            Vec3d origin=origin(random),player=player(random);
            Vec384f semantic=driftedSemantic(q,grid,observer,random);
            Vec3d feet=SpiritTerrainService.supportContactFeet(semantic,q,grid,observer,origin);
            double worst=0;
            for(int step=0;step<=SupportAlignment.FULL_STEPS;step++) {
                Basis384f blended=TraversalSteering.blend(observer,grid,step/(float)SupportAlignment.FULL_STEPS);
                worst=Math.max(worst,placed(player,semantic,q,blended,grid,origin,feet).distanceTo(player));
            }
            check(worst>1.0,"unrebased semantic drift translates the supporting floor through the player during alignment (worst "+worst+")");
        }
    }

    /** Acquisition's projected 1 mm residual is exactly zero against the re-anchored window. */
    private static void pivotSemanticMakesAcquisitionResidualExactlyZero() {
        Random random=new Random(20240520);
        for(int trial=0;trial<8;trial++) {
            Basis384f grid=basis(random),observer=basis(random);
            Vec384f q=unit(random).mul(.5f);
            Vec3d origin=origin(random);
            Vec384f semantic=driftedSemantic(q,grid,observer,random);
            Vec3d feet=SpiritTerrainService.supportContactFeet(semantic,q,grid,observer,origin);
            Vec384f reanchored=SpiritTerrainService.supportPivotSemantic(q,grid,feet,origin);
            Vec3d offset=feet.subtract(origin);
            Vec384f coordinate=reanchored.clone()
                    .add(grid.i.clone().mul((float)(offset.x/SCALE)))
                    .add(grid.j.clone().mul((float)(offset.y/SCALE)))
                    .add(grid.k.clone().mul((float)(offset.z/SCALE)));
            check(coordinate.squareDistance(q)<1e-9f,"re-anchored support coordinate equals q to float precision, far inside the 1 mm acquisition gate");
        }
    }

    /** Plain guarded alignment converges within the approved 40 full-step equivalents. */
    private static void alignmentScheduleConvergesBounded() {
        Random random=new Random(20240521);
        for(int trial=0;trial<8;trial++) {
            // A moderately rotated basis (a few blocks of deep flight): full 1/40 steps stay inside
            // the production per-tick discontinuity gate for this whole rotation.
            Basis384f destination=basis(random),from=TraversalSteering.blend(destination,basis(random),.5f);
            SupportAlignment alignment=new SupportAlignment();
            Basis384f current=from.clone();int accepted=0;float previous=0;
            while(!alignment.complete() && accepted<1000) {
                float fraction=alignment.nextFraction();
                Basis384f proposed=TraversalSteering.blend(from,destination,fraction);
                check(maxAxisDistance(current,proposed)<=.1f+1e-6f,
                        "guarded proposal stays inside the per-tick discontinuity gate");
                check(fraction>previous,"accepted progress advances monotonically");
                alignment.accept(fraction);current=proposed;accepted++;previous=fraction;
            }
            check(alignment.complete(),"alignment schedule completes for arbitrary rotations");
            check(accepted<=SupportAlignment.FULL_STEPS,
                    "plain alignment accepts at most "+SupportAlignment.FULL_STEPS+" full-step equivalents ("+accepted+")");
            check(current.i.squareDistance(destination.i)+current.j.squareDistance(destination.j)
                    +current.k.squareDistance(destination.k)<1e-8f,"accepted alignment reaches the source grid within the 1e-8 gate");
        }
    }

    /** A refusing guard must yield strictly finer retries that still converge inside the request
     * deadline. The simulated guard tolerates only small per-tick increments (like a supporting
     * floor that must not tilt into a stationary body); hard full rotations may still exhaust the
     * real 200-tick deadline by path geometry, but a guard that admits fine increments must finish. */
    private static void alignmentScheduleAdaptsToRefusals() {
        Random random=new Random(20240522);
        for(int trial=0;trial<8;trial++) {
            Basis384f destination=basis(random),from=basis(random);
            SupportAlignment alignment=new SupportAlignment();
            Basis384f current=from.clone();
            // Simulate the production guards: the per-tick discontinuity gate plus a terrain
            // transition guard that can only absorb increments of at most 1/160 per tick.
            float toleratedIncrement=1f/160;int ticks=0,refusals=0;float refusedFraction=-1;
            while(!alignment.complete() && ticks<200) { // the real request deadline bounds the schedule
                ticks++;
                float fraction=alignment.nextFraction();
                if(refusedFraction>=0)check(fraction<refusedFraction,"a refused step is retried strictly finer, never identically");
                Basis384f proposed=TraversalSteering.blend(from,destination,fraction);
                if(maxAxisDistance(current,proposed)>.1f || fraction-alignment.progress()>toleratedIncrement+1e-7f) {
                    alignment.refuse();refusedFraction=fraction;refusals++;continue;
                }
                alignment.accept(fraction);current=proposed;refusedFraction=-1;
            }
            check(refusals>0,"the scenario actually exercised refused guarded steps");
            check(alignment.complete(),"refusal-adaptive schedule converges inside the 200-tick request deadline");
            check(alignment.step()>=1f/(SupportAlignment.FULL_STEPS*64f),"refusal halving respects the subdivision floor");
            check(alignment.step()<=1f/SupportAlignment.FULL_STEPS,"step recovery never exceeds a full step");
            check(alignment.progress()<=1f && alignment.progress()>=0f,"accepted progress stays a bounded fraction summing to at most one");
        }
    }

    /** Unlike the synthetic increment-limit schedule test, this drives the REAL affine collision
     * guard with the same placement/blend/pivot helpers used by attemptSupport/canAlign. The tilted
     * floor's AABB still intersects the strict body on every first-step subdivision; the old guard
     * never accepted any progress and exhausted all 200 ticks. */
    private static void supportedAlignmentPassesProductionContinuityGuard() {
        for(float degrees:new float[]{-5,5})for(Vec3d player:new Vec3d[]{Vec3d.ZERO,new Vec3d(8123,70,-431.5),new Vec3d(29000000,255,-29000000)}) {
            Basis384f grid=new Basis384f();
            double angle=Math.toRadians(degrees);float sine=(float)Math.sin(angle),cosine=(float)Math.cos(angle);
            Basis384f from=new Basis384f(grid.i.clone().mul(cosine).sub(grid.j.clone().mul(sine)),
                    grid.i.clone().mul(sine).add(grid.j.clone().mul(cosine)),grid.k);
            Vec3d feet=new Vec3d(.5,1+.3*Math.abs(sine),.5);
            Vec384f q=Vec384f.ZERO(),semantic=SpiritTerrainService.supportPivotSemantic(q,grid,feet,Vec3d.ZERO);
            Box body=new Box(player.x-.3,player.y,player.z-.3,player.x+.3,player.y+1.8,player.z+.3);
            TerrainMeshFrame current=placedFloor(player,semantic,q,from,grid);
            check(MeshCollision.bodyClear(current,body),"initial tilted support has SAT body clearance");
            check(current.cells().getFirst().bounds().intersects(body.expand(-1e-5)),"fixture exercises original AABB-union rejection");
            SupportAlignment alignment=new SupportAlignment();Basis384f acceptedBasis=from;int ticks=0,accepted=0;
            while(!alignment.complete() && ticks<200) {
                ticks++;float fraction=alignment.nextFraction();
                Basis384f proposed=TraversalSteering.blend(from,grid,fraction);
                TerrainMeshFrame next=placedFloor(player,semantic,q,proposed,grid);
                if(maxAxisDistance(acceptedBasis,proposed)>.1f || !MeshCollision.transitionClear(current,next,body)) {
                    alignment.refuse();continue;
                }
                check(MeshCollision.bodyClear(next,body),"accepted real alignment never clips floor through player");
                check(new MeshCollision.Index(next).sweep(body.offset(0,.025,0),new Vec3d(0,-.15,0)).isPresent(),
                        "accepted alignment retains real affine ground contact");
                alignment.accept(fraction);acceptedBasis=proposed;current=next;accepted++;
            }
            check(alignment.complete(),"supported walk alignment reaches source grid before 200-tick expiry (ticks="+ticks+")");
            check(accepted<=SupportAlignment.FULL_STEPS,"valid 5-degree support accepts full steps rather than starving at subdivision floor");
            // The deep-to-shallow acquisition uses identity source axes at exactly the same source
            // feet/carrier pose. Its final whole-frame guard must also accept without a teleport.
            TerrainMeshFrame shallow=MeshTransitionClearanceTest.frame(new TerrainMeshFrame.Cell(1,0,"floor",Vec3d.ZERO,
                    player.subtract(feet),new Vec3d(1,1,1),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),
                    -1,0,1,List.of(new Box(0,0,0,1,1,1))));
            check(MeshCollision.bodyClear(shallow,body) && MeshCollision.transitionClear(current,shallow,body),
                    "final walking acquisition geometry passes production body/continuity gates");
        }
    }
    private static TerrainMeshFrame placedFloor(Vec3d player,Vec384f semantic,Vec384f q,Basis384f observer,Basis384f grid) {
        return MeshTransitionClearanceTest.frame(new TerrainMeshFrame.Cell(1,0,"floor",Vec3d.ZERO,
                SpiritTerrainService.frameCellMin(player,semantic,q,observer,grid,Vec3d.ZERO,Vec3d.ZERO),new Vec3d(1,1,1),
                SpiritTerrainService.frameAxis(grid.i,observer),SpiritTerrainService.frameAxis(grid.j,observer),
                SpiritTerrainService.frameAxis(grid.k,observer),-1,0,1,List.of(new Box(0,0,0,1,1,1))));
    }

    private static float maxAxisDistance(Basis384f a,Basis384f b) {
        return (float)Math.sqrt(Math.max(
                Math.max(a.i.squareDistance(b.i),a.j.squareDistance(b.j)),a.k.squareDistance(b.k)));
    }
}
