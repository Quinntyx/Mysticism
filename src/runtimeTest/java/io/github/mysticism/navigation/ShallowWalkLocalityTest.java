package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;

/** Offline regressions for shallow-walk source-locality attachment and walk-request usability.
 * Pure decision/geometry models only; no server, world, model or GPU assets are involved. */
public final class ShallowWalkLocalityTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double actual,double expected,double epsilon,String message){
        check(Math.abs(actual-expected)<=epsilon,message+" (expected "+expected+", got "+actual+")");
    }

    public static void main(String[] args) {
        attachmentPhase();
        takeoffGrace();
        walkRequestBudgets();
        alignedTracking();
        residualSlide();
        misalignedMovementAndRecovery();
        System.out.println("ShallowWalkLocalityTest: " + checks + " checks passed (offline decision/geometry models)");
    }

    /** Problem 4/1: supported ground with pending ownership is walking, never instant free flight. */
    private static void attachmentPhase() {
        check(WalkPolicy.phase(true,false,false,false)==WalkPolicy.SupportPhase.SUPPORTED,"Owned floor must be SUPPORTED");
        check(WalkPolicy.phase(false,true,true,false)==WalkPolicy.SupportPhase.PENDING,
                "Mesh ground with pending ownership must keep shallow walking (entry must not start flight)");
        check(WalkPolicy.phase(false,true,false,true)==WalkPolicy.SupportPhase.PENDING,
                "Blank mapped id with ground stays attached while discovery publishes");
        check(WalkPolicy.phase(false,true,false,false)==WalkPolicy.SupportPhase.AIRBORNE,
                "Grounded on a genuinely unowned floor is ordinary takeoff, not shallow walking");
        check(WalkPolicy.phase(false,false,true,false)==WalkPolicy.SupportPhase.AIRBORNE,
                "No mesh ground is a real edge/air case even while ownership is pending");
        check(WalkPolicy.phase(true,true,true,false)==WalkPolicy.SupportPhase.SUPPORTED,
                "Semantic support wins over pending classification");
    }

    /** Ascending takeoff keeps jump grace; walking off an edge is immediate freeflight. */
    private static void takeoffGrace() {
        check(!WalkPolicy.takeoffDeep(1,true),"Jump takeoff tick 1 must stay shallow");
        check(!WalkPolicy.takeoffDeep(WalkPolicy.JUMP_GRACE_TICKS,true),"Full jump grace must stay shallow");
        check(WalkPolicy.takeoffDeep(WalkPolicy.JUMP_GRACE_TICKS+1,true),"Beyond jump grace must enter deep");
        check(WalkPolicy.takeoffDeep(1,false),"Non-ascending unsupported tick (edge) must enter deep immediately");
    }

    /** Walk requests must terminate on stalls/failures but never expire while real progress is possible. */
    private static void walkRequestBudgets() {
        check(WalkPolicy.STALL_CANCEL_TICKS>0,"Stall budget must be positive");
        check(WalkPolicy.STALL_CANCEL_TICKS<=200,"Stall cancellation must not be slower than the legacy total budget");
        check(WalkPolicy.MAX_PENDING_TICKS>WalkPolicy.STALL_CANCEL_TICKS,"Total budget must outlive transient stalls");
        check(WalkPolicy.ACQUIRE_RETRY_TICKS>0,"Failed acquisitions need bounded retry pressure");
        check(WalkPolicy.MAX_ACQUIRE_FAILURES>0,"Acquisition failures must eventually end the request");
        check(WalkPolicy.SLIDE_FRACTION>0 && WalkPolicy.SLIDE_FRACTION<=1,"Slide fraction must be a bounded convergence factor");
        check(!WalkPolicy.slideNeeded(0),"Tight residual needs no slide");
        check(WalkPolicy.slideNeeded(1e-6),"Above-gate residual needs a slide");
        check(!WalkPolicy.displacementFatal(0.5),"Half-block drift is recoverable, not fatal");
        check(!WalkPolicy.displacementFatal(4.0),"Multi-block movement during alignment is recoverable");
        check(WalkPolicy.displacementFatal(1000.0),"Pathological scene displacement must not be silently slid over");
    }

    private static Vec384f addScaled(Vec384f v,Vec384f direction,double blocks) {
        return v.add(direction.clone().mul((float)(blocks/ SpiritTerrainService.SCALE)));
    }
    /** Vec384f arithmetic mutates its receiver; always difference against a throwaway copy. */
    private static double worldError(Vec384f coordinate,Vec384f q,Basis384f basis) {
        return WalkPolicy.worldErrorSquared(coordinate.clone().sub(q),basis);
    }

    /** Aligned shallow walking: movement along the source grid keeps the tracked coordinate exactly on q. */
    private static void alignedTracking() {
        Basis384f grid=new Basis384f();
        Vec384f q=Vec384f.ZERO().clone();
        Vec3d p=Vec3d.ZERO;
        double[] dx={.21,-.13,.4,0,0,-.05},dy={0,.02,-.1,.3,0,0},dz={.1,0,.22,0,-.33,.07};
        for(int t=0;t<dx.length;t++) {
            q=addScaled(q,grid.i,dx[t]);q=addScaled(q,grid.j,dy[t]);q=addScaled(q,grid.k,dz[t]);
            p=p.add(dx[t],dy[t],dz[t]);
            // coordinate derived from the physical pose through the SAME grid (shallow invariant)
            Vec384f coordinate=Vec384f.ZERO().clone();
            coordinate=addScaled(coordinate,grid.i,p.x);coordinate=addScaled(coordinate,grid.j,p.y);coordinate=addScaled(coordinate,grid.k,p.z);
            double error=worldError(coordinate,q,grid);
            check(error<=1e-6,"Aligned movement must keep the acquisition gate satisfied at tick "+t+" (error "+error+")");
        }
    }

    /** The validated slide closes a bounded residual while standing still, without overshoot or teleport. */
    private static void residualSlide() {
        Basis384f grid=new Basis384f();
        Vec384f coordinate=Vec384f.ZERO().clone();
        // Start half a block (world) off along each axis: recoverable drift, above the gate.
        Vec384f q=Vec384f.ZERO().clone();
        q=addScaled(q,grid.i,.5);q=addScaled(q,grid.j,.25);q=addScaled(q,grid.k,-.4);
        double initial=worldError(coordinate,q,grid);
        check(WalkPolicy.slideNeeded(initial),"Initial residual must require the slide");
        check(!WalkPolicy.displacementFatal(initial),"Half-block-scale drift must be recoverable");
        double previous=initial,maxStep=0;
        for(int t=0;t<60;t++) {
            Vec384f before=q.clone();
            q=WalkPolicy.slidToward(q,coordinate);
            maxStep=Math.max(maxStep,Math.sqrt(q.squareDistance(before))*SpiritTerrainService.SCALE);
            double error=worldError(coordinate,q,grid);
            check(error<=previous,"Slide must never overshoot or diverge at tick "+t);
            previous=error;
            if(!WalkPolicy.slideNeeded(error))break;
        }
        check(!WalkPolicy.slideNeeded(previous),"Slide must converge inside the acquisition gate while standing still");
        check(maxStep<=.2*1.0+1e-9,"Per-tick slide must stay bounded by the convergence fraction of the residual");
    }

    /** Misaligned deep movement breaks the coordinate gate; alignment plus slides restores it. */
    private static void misalignedMovementAndRecovery() {
        Basis384f grid=new Basis384f();
        Basis384f rotated=new Basis384f(
                new Vec384f(unit(Math.cos(Math.PI/3),0,-Math.sin(Math.PI/3))),
                new Vec384f(unit(0,1,0)),
                new Vec384f(unit(Math.sin(Math.PI/3),0,Math.cos(Math.PI/3))));
        Vec384f q=Vec384f.ZERO().clone();
        Vec3d p=Vec3d.ZERO;
        // Two blocks of deep movement integrated along the rotated basis: the coordinate derived from
        // the source grid moves differently, so the strict acquisition gate cannot be satisfied.
        for(int t=0;t<20;t++) {
            q=addScaled(q,rotated.i,.1);
            p=p.add(.1,0,0);
        }
        Vec384f coordinate=Vec384f.ZERO().clone();
        coordinate=addScaled(coordinate,grid.i,p.x);
        double error=worldError(coordinate,q,grid);
        check(error>1e-6,"Movement with a misaligned basis must reproduce the unreachable acquisition gate");
        // Walk request accepted: integrate along the destination grid and slide the residual closed.
        check(WalkPolicy.integrationBasis(rotated,grid)==grid,"Pending integration must use the destination grid");
        check(WalkPolicy.integrationBasis(rotated,null)==rotated,"Before a destination is known the current basis integrates");
        for(int t=0;t<90 && WalkPolicy.slideNeeded(worldError(coordinate,q,grid));t++)
            q=WalkPolicy.slidToward(q,coordinate);
        double recovered=worldError(coordinate,q,grid);
        check(!WalkPolicy.slideNeeded(recovered),"Slide must make the misaligned landing acquirable (error "+recovered+")");
        check(!WalkPolicy.displacementFatal(recovered),"Recovery must end far inside the displacement limit");
    }

    private static float[] unit(double x,double y,double z) {
        float[] v=new float[io.github.mysticism.vector.EmbeddingSpace.DIMENSIONS];
        double length=Math.sqrt(x*x+y*y+z*z);
        v[0]=(float)(x/length);v[1]=(float)(y/length);v[2]=(float)(z/length);
        return v;
    }
}
