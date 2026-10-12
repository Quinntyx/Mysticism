package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runtime regressions for stable walking across ordinary terrain height changes.
 * Exercises the shared server/prediction movement resolution and the shallow-support
 * ejection policy on real mesh geometry, without booting a Minecraft server. */
public final class MeshSteppingTest {
    private static int checks;
    private static void check(boolean condition,String why) { checks++;if(!condition)throw new AssertionError(why); }
    private static void near(double actual,double expected,double tolerance,String why) {
        checks++;if(Math.abs(actual-expected)>tolerance)throw new AssertionError(why+" (expected "+expected+", got "+actual+")");
    }

    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1,true,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells));
    }
    private static TerrainMeshFrame.Cell cell(long key,int x,int y,int z,List<Box> local) {
        return new TerrainMeshFrame.Cell(key,0,"test",new Vec3d(x,y,z),new Vec3d(x,y,z),new Vec3d(1,1,1),
                new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),0xFFFFFFFF,15,1f,local);
    }
    /** Full source block occupying [x,x+1]x[y,y+1]x[z,z+1]. */
    private static TerrainMeshFrame.Cell block(long key,int x,int y,int z) {
        return cell(key,x,y,z,List.of(new Box(0,0,0,1,1,1)));
    }
    /** Partial-height shape (a slab lip) with its top at y+height inside cell (x,y,z). */
    private static TerrainMeshFrame.Cell slab(long key,int x,int y,int z,double height) {
        return cell(key,x,y,z,List.of(new Box(0,0,0,1,height,1)));
    }
    private static Box body(double x,double y,double z) {
        return new Box(x-.3,y,z-.3,x+.3,y+1.8,z+.3);
    }

    private static final double GRAVITY=-0.0784;

    /** Server/prediction pose for one simulated player. */
    private static final class Walker {
        final MeshCollision.Index index;
        Vec3d pos;boolean onGround=true;double vy=GRAVITY;
        int unsupportedRun,maxUnsupportedRun;double runDrop,maxRunDrop;
        Walker(TerrainMeshFrame frame,Vec3d start) { index=new MeshCollision.Index(frame);pos=start; }
        /** One vanilla-like walking tick with vanilla ground-contact and falling velocity rules. */
        void walk(Vec3d horizontal) {
            double wy=vy;
            Vec3d wanted=new Vec3d(horizontal.x,wy,horizontal.z);
            Vec3d result=MeshCollision.move(index,body(pos.x,pos.y,pos.z),wanted,false,onGround,.6f);
            boolean supported=MeshCollision.ground(index,body(pos.x,pos.y,pos.z).offset(result)).isPresent();
            pos=pos.add(result);
            onGround=wy!=result.y && wy<0; // vanilla Entity.move groundCollision rule
            vy=onGround?GRAVITY:(vy-.08)*.98;
            if(!supported) {
                if(unsupportedRun==0)runDrop=0;
                runDrop+=Math.max(0,-result.y); // actual fall distance this tick
                unsupportedRun++;
                maxUnsupportedRun=Math.max(maxUnsupportedRun,unsupportedRun);
                maxRunDrop=Math.max(maxRunDrop,runDrop);
            } else { unsupportedRun=0;runDrop=0; }
        }
    }

    private static void flatRestingStability() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=7;x++)for(int z=-2;z<=2;z++)cells.add(block(key++,x,-1,z));
        Walker w=new Walker(frame(cells.toArray(TerrainMeshFrame.Cell[]::new)),new Vec3d(0,0,0));
        for(int t=0;t<40;t++)w.walk(new Vec3d(.15,0,0));
        near(w.pos.y,0,1e-9,"resting on flat mesh must not drift vertically (jitter)");
        near(w.pos.x,6.0,1e-6,"unblocked walking must cover the full requested distance");
        check(MeshCollision.bodyClear(frame(cells.toArray(TerrainMeshFrame.Cell[]::new)),body(w.pos.x,w.pos.y,w.pos.z)),
                "resting body must never embed in the floor");
        check(w.onGround,"walking on flat mesh stays grounded");
    }

    private static void stepUpCrossing() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=4;x++)for(int z=-1;z<=1;z++)cells.add(block(key++,x,-1,z));
        for(int x=1;x<=3;x++)for(int z=-1;z<=1;z++)cells.add(slab(key++,x,0,z,.5));
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        Walker w=new Walker(f,new Vec3d(.5,0,0));
        for(int t=0;t<15;t++)w.walk(new Vec3d(.15,0,0));
        near(w.pos.y,.5,1e-4,"a <=step-height lip must be stepped onto, not snagged against");
        near(w.pos.x,.5+15*.15,1e-4,"stepping must preserve the full requested horizontal budget");
        check(MeshCollision.bodyClear(f,body(w.pos.x,w.pos.y,w.pos.z)),"stepped body must stay clear");
        check(w.onGround,"stepping up stays grounded");
        // Ascent never loses mesh ground, so shallow support must never be interrupted while climbing.
        Walker probe=new Walker(f,new Vec3d(.5,0,0));
        for(int t=0;t<15;t++) { probe.walk(new Vec3d(.15,0,0));check(probe.unsupportedRun==0,"step ascent keeps ground support"); }
    }

    private static void stepUpNeverExceedsRequest() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=6;x++)for(int z=-1;z<=1;z++)cells.add(block(key++,x,-1,z));
        for(int x=1;x<=5;x++)for(int z=-1;z<=1;z++)cells.add(slab(key++,x,0,z,.5));
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        Walker w=new Walker(f,new Vec3d(.5,0,0));
        w.walk(new Vec3d(2.5,0,0));
        check(w.pos.x-.5<=2.5+1e-6,"stepping must never displace further than requested");
        near(w.pos.x,3.0,1e-4,"fast travel still covers the full requested distance across the step");
        near(w.pos.y,.5,1e-4,"fast travel still resolves onto the step top");
        check(MeshCollision.bodyClear(f,body(w.pos.x,w.pos.y,w.pos.z)),"fast stepped body must stay clear");
    }

    private static void tallWallBlocks() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=3;x++)for(int z=-1;z<=1;z++)cells.add(block(key++,x,-1,z));
        for(int z=-1;z<=1;z++) { cells.add(block(key++,1,0,z));cells.add(block(key++,1,1,z)); }
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        Walker w=new Walker(f,new Vec3d(.5,0,0));
        for(int t=0;t<10;t++)w.walk(new Vec3d(.15,0,0));
        near(w.pos.x,.7,1e-4,"a wall taller than the step height blocks horizontal travel");
        near(w.pos.y,0,1e-4,"blocked walking must not climb the wall");
        check(MeshCollision.bodyClear(f,body(w.pos.x,w.pos.y,w.pos.z)),"blocked body must stay clear");
        check(w.onGround,"blocked walking stays grounded");
    }

    private static void descentStaysResolvable() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-4;x<=4;x++)for(int z=-1;z<=1;z++)cells.add(block(key++,x,-1,z));
        for(int x=0;x<=3;x++)for(int z=-1;z<=1;z++)cells.add(slab(key++,x,0,z,.5));
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        Walker w=new Walker(f,new Vec3d(3,0.5,0));
        for(int t=0;t<28;t++) {
            w.walk(new Vec3d(-.15,0,0));
            check(MeshCollision.bodyClear(f,body(w.pos.x,w.pos.y,w.pos.z)),"descending a step must never embed the body");
        }
        near(w.pos.y,0,1e-4,"walking off an ordinary step lands on the lower floor");
        check(w.onGround,"the descent ends grounded");
        check(MeshCollision.ground(w.index,body(w.pos.x,w.pos.y,w.pos.z)).isPresent(),"ground support is regained after the descent");
        // The descent DOES transiently lose ground support; the ejection policy must tolerate that
        // window instead of kicking an ordinary step descent into deep flight.
        check(w.maxUnsupportedRun>0,"ordinary step descents transiently lose mesh ground (policy precondition)");
        check(w.maxRunDrop<=1.0,"ordinary step descent drop stays within one block");
        check(!SpiritNavigationService.ejectsToDeep(false,w.maxUnsupportedRun,w.maxRunDrop),
                "an ordinary step descent must not be reclassified as deep freeflight");
    }

    private static void depenetrationIsNeverDiscarded() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)cells.add(block(key++,x,-1,z));
        for(int z=-2;z<=2;z++) { cells.add(block(key++,1,0,z));cells.add(block(key++,1,1,z)); }
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        // Body embedded in the wall face (animated geometry overlap), walking tangentially past it.
        Vec3d start=new Vec3d(.72,0,0);
        Vec3d result=MeshCollision.move(new MeshCollision.Index(f),body(start.x,start.y,start.z),
                new Vec3d(0,GRAVITY,.4),false,true,.6f);
        Vec3d end=start.add(result);
        check(MeshCollision.bodyClear(f,body(end.x,end.y,end.z)),
                "stepping past an embedded start must preserve depenetration, never re-embed the body");
        near(result.z,.4,1e-6,"tangential movement past a wall is not lost by the step resolution");
        check(result.x<=1e-9,"the body must not stay pushed into the wall");
    }

    private static void ejectionPolicy() {
        // Ordinary step descents: a vanilla-gravity fall sequence over a <=1 block lip must stay shallow.
        for (double lip : new double[] {.5,1.0}) {
            int run=0;double drop=0,vy=GRAVITY;boolean eject=false;
            for(int t=0;t<20 && !eject;t++) {
                if(drop>=lip)break; // the lower floor stops the fall; support is regained
                drop+=Math.min(Math.max(0,-vy),lip-drop); // real collision clamps the fall at the lip
                run++;
                eject=SpiritNavigationService.ejectsToDeep(false,run,drop);
                vy=(vy-.08)*.98;
            }
            check(!eject,"a "+lip+" block step descent must stay shallow (run="+run+", drop="+drop+")");
        }
        // A real edge (multi-block drop) still becomes deep freeflight once past an ordinary height.
        check(SpiritNavigationService.ejectsToDeep(false,4,1.066),"cliff fall accumulation ejects after one block");
        check(SpiritNavigationService.ejectsToDeep(false,6,1.9),"deeper falls eject to deep flight");
        // Sustained air and jump grace keep their previous semantics.
        check(SpiritNavigationService.ejectsToDeep(false,SpiritNavigationService.UNSUPPORTED_GRACE_TICKS+1,0),
                "sustained unsupported air still ejects");
        check(!SpiritNavigationService.ejectsToDeep(false,SpiritNavigationService.UNSUPPORTED_GRACE_TICKS,.9),
                "inside the grace window a small drop stays shallow");
        check(!SpiritNavigationService.ejectsToDeep(true,10,2.0),"jump takeoff keeps its tick grace");
        check(SpiritNavigationService.ejectsToDeep(true,SpiritNavigationService.UNSUPPORTED_GRACE_TICKS+1,0),
                "jump grace still ends after its tick budget");
    }

    private static void debugFlat() {
        List<TerrainMeshFrame.Cell> cells=new ArrayList<>();long key=0;
        for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++)cells.add(block(key++,x,-1,z));
        TerrainMeshFrame f=frame(cells.toArray(TerrainMeshFrame.Cell[]::new));
        MeshCollision.Index index=new MeshCollision.Index(f);
        Vec3d pos=new Vec3d(0,0,0);boolean onGround=true;double vy=GRAVITY;
        for(int t=0;t<6;t++) {
            Vec3d wanted=new Vec3d(.15,vy,0);
            var hit=MeshCollision.ground(index,body(pos.x,pos.y,pos.z));
            Vec3d result=MeshCollision.move(index,body(pos.x,pos.y,pos.z),wanted,false,onGround,.6f);
            System.out.printf("t%d pos=%s wanted=%s result=%s groundBefore=%s onGroundIn=%b%n",
                    t,pos,wanted,result,hit, onGround);
            pos=pos.add(result);
            onGround=vy!=result.y && vy<0;
            vy=onGround?GRAVITY:(vy-.08)*.98;
        }
    }

    public static void main(String[] args) {
        if(args.length>0) { debugFlat(); return; }
        flatRestingStability();
        stepUpCrossing();
        stepUpNeverExceedsRequest();
        tallWallBlocks();
        descentStaysResolvable();
        depenetrationIsNeverDiscarded();
        ejectionPolicy();
        System.out.println("MeshSteppingTest passed: "+checks+" checks");
    }
}
