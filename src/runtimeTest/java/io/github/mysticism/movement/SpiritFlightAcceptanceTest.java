package io.github.mysticism.movement;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.MeshMovementValidation;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.github.mysticism.navigation.SpiritMovementAcceptance;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises validation -> provisional Entity.move sweep -> final acceptance together. The actual
 * acceptance/flight/collision mixins are jointly applied by SpiritIntegratedMixinApplicationTest;
 * these entity-free fixtures execute their production geometry, replay and pose arbitration cores.
 * Neither check constitutes a live multiplayer/gameplay acceptance run. */
public final class SpiritFlightAcceptanceTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    private static final Box BODY=new Box(1,.5,1,1.6,2.3,1.6);
    private static Vec3d feet(Box body) { return new Vec3d(body.getCenter().x,body.minY,body.getCenter().z); }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells));
    }
    private static TerrainMeshFrame wall(double x) {
        return frame(new TerrainMeshFrame.Cell(1,0,"real-source",Vec3d.ZERO,new Vec3d(x,0,0),new Vec3d(1,3,3),
                new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),0xffffffff,0,1,
                List.of(new Box(0,0,0,1,3,3))));
    }
    private static Vec3d sweep(TerrainMeshFrame current,Box body,Vec3d claim) {
        // Packet coordinates are already prediction-resolved. Entity.move nevertheless sweeps
        // that delta as raw movement against the current server frame before the collision gate.
        return feet(body).add(MeshCollision.move(new MeshCollision.Index(current),body,claim.subtract(feet(body)),true,0,false));
    }
    private static void historicalReplaySurvivesFinalAcceptance() {
        UUID id=UUID.randomUUID();
        var older=frame(); var current=wall(2);
        try {
            MeshMovementValidation.record(id,older); MeshMovementValidation.record(id,current);
            Vec3d claim=feet(BODY).add(4,0,0), provisional=sweep(current,BODY,claim);
            var decision=MeshMovementValidation.validate(id,BODY,claim,true);
            check(decision.allowed(),"old published frame reproduces flight claim");
            check(SpiritMovementAcceptance.accept(claim,provisional).correctionRequired(),
                    "negative control: pre-repair acceptance discards valid replay and teleports");
            var accepted=SpiritMovementAcceptance.accept(claim,provisional,decision);
            check(accepted.position().distanceTo(claim)<1e-9 && !accepted.correctionRequired(),
                    "historical accepted frame remains authoritative through the final pose write");
            check(MeshCollision.bodyClear(older,BODY.offset(accepted.position().subtract(feet(BODY)))),
                    "accepted pose is physically clear under the selected visible frame");
            // History may grow/evict during a handoff; the already validated packet retains its
            // immutable decision rather than reselecting a different frame at final acceptance.
            for(int n=0;n<MeshMovementValidation.HISTORY+1;n++)MeshMovementValidation.record(id,wall(2));
            check(!MeshMovementValidation.validate(id,BODY,claim,true).allowed(),"evicted frame cannot authorize a NEW claim");
            check(!SpiritMovementAcceptance.accept(claim,provisional,decision).correctionRequired(),
                    "an existing packet decision is not recomputed against later history");
            check(MeshMovementValidation.historySize(id)==MeshMovementValidation.HISTORY,"history remains bounded");
        } finally { MeshMovementValidation.clear(id); }
    }
    private static void closestReplayNotFirstToleratedReplay() {
        UUID id=UUID.randomUUID();
        var older=wall(2); var current=wall(1.9);
        try {
            MeshMovementValidation.record(id,older); MeshMovementValidation.record(id,current);
            Vec3d claim=new Vec3d(1.65,.5,1.3);
            Vec3d provisional=sweep(current,BODY,claim);
            check(claim.distanceTo(provisional)>.04 && claim.distanceTo(provisional)<.25,
                    "newest replay is inside tolerance but still differs enough to cause rubber banding");
            var accepted=SpiritMovementAcceptance.accept(claim,provisional,MeshMovementValidation.validate(id,BODY,claim,true));
            check(accepted.position().distanceTo(claim)<1e-9 && !accepted.correctionRequired(),
                    "exact older replay wins over a newer tolerated near miss");
        } finally { MeshMovementValidation.clear(id); }
    }
    private static void wallsAndFallbacksRemainAuthoritative() {
        UUID id=UUID.randomUUID(),other=UUID.randomUUID();
        var current=wall(2);
        try {
            MeshMovementValidation.record(id,current); MeshMovementValidation.record(other,frame());
            Vec3d crossing=feet(BODY).add(4,0,0), clipped=sweep(current,BODY,crossing);
            var rejected=MeshMovementValidation.validate(id,BODY,crossing,true);
            check(!rejected.allowed(),"a wall crossed under EVERY own frame remains rejected");
            check(MeshMovementValidation.validate(other,BODY,crossing,true).allowed(),"other player's projection really differs");
            var finalPose=SpiritMovementAcceptance.accept(crossing,clipped,rejected);
            check(finalPose.position().equals(clipped) && finalPose.correctionRequired(),"rejected decision cannot overwrite the swept wall stop");
            // A tolerance-admitted endpoint must still retain the selected frame's clipping.
            Vec3d near=new Vec3d(1.9,.5,1.3);
            var nearDecision=MeshMovementValidation.validate(id,BODY,near,true);
            check(nearDecision.allowed(),"small residual reaches vanilla's final acceptance branch");
            var nearFinal=SpiritMovementAcceptance.accept(near,sweep(current,BODY,near),nearDecision);
            check(nearFinal.correctionRequired() && nearFinal.position().x<1.701,
                    "sub-tolerance clipping does not turn into permission to enter the wall");
            check(MeshCollision.bodyClear(current,BODY.offset(nearFinal.position().subtract(feet(BODY)))),"selected replay remains collision clear");
            var foreign=SpiritMovementAcceptance.accept(crossing,clipped,nearDecision);
            check(foreign.position().equals(clipped),"another packet's coordinates cannot reuse a decision");
            MeshMovementValidation.clear(id);
            var empty=MeshMovementValidation.validate(id,BODY,crossing,true);
            check(empty.allowed() && !empty.replays(crossing),"missing history grants no invented replay authority");
            check(SpiritMovementAcceptance.accept(crossing,clipped,empty).position().equals(clipped),
                    "missing published frame keeps the available current sweep instead of bypassing walls");
        } finally { MeshMovementValidation.clear(id); MeshMovementValidation.clear(other); }
    }
    private static void correctionAccountingUsesTheSameReplay() {
        UUID id=UUID.randomUUID(),other=UUID.randomUUID();
        var older=wall(0); var current=wall(1);
        Box embedded=new Box(1.2,.5,.5,1.8,2.3,1.1);
        try {
            MeshMovementValidation.record(id,older); MeshMovementValidation.record(id,current);
            Vec3d claim=feet(embedded).add(0,0,.08);
            var decision=MeshMovementValidation.validate(id,embedded,claim,true);
            check(decision.replays(claim) && decision.correction().lengthSquared()==0,
                    "older visible frame reproduces movement without the newer frame's depenetration");
            var prior=new Vec3d(0,.02,0); var otherCorrection=new Vec3d(.03,0,0);
            MeshCollision.recordCorrection(id,false,prior);
            MeshCollision.recordCorrection(id,true,otherCorrection);
            MeshCollision.recordCorrection(other,false,otherCorrection);
            var before=MeshCollision.correctionSnapshot(id,false);
            var provisionalCorrection=new MeshCollision.Index(current).depenetrate(embedded,.5);
            check(provisionalCorrection.length()>.49,"provisional current sweep produces a real half-block correction");
            MeshCollision.recordCorrection(id,false,provisionalCorrection);
            MeshCollision.acceptReplayCorrection(id,false,before,decision.correction());
            check(MeshCollision.drainCorrection(id,false).equals(prior),
                    "accepted old-frame replay discards only this packet's provisional correction");
            check(MeshCollision.drainCorrection(id,true).equals(otherCorrection),"client correction ledger remains independent");
            check(MeshCollision.drainCorrection(other,false).equals(otherCorrection),"another player's correction evidence remains independent");
            MeshMovementValidation.clear(id); MeshMovementValidation.record(id,current);
            var predicted=MeshCollision.predicted(new MeshCollision.Index(current),embedded,Vec3d.ZERO,true,false,0);
            claim=feet(embedded).add(predicted);
            decision=MeshMovementValidation.validate(id,embedded,claim,true);
            check(decision.replays(claim) && decision.correction().distanceTo(predicted)<1e-9,
                    "matching current frame retains its real correction, not a doubled raw-input replay");
            var accepted=SpiritMovementAcceptance.accept(claim,sweep(current,embedded,claim),decision);
            check(accepted.position().distanceTo(claim)<1e-9 && !accepted.correctionRequired(),
                    "depenetration already included by prediction is accepted once");
            MeshCollision.acceptReplayCorrection(id,false,prior,decision.correction());
            check(MeshCollision.drainCorrection(id,false).distanceTo(prior.add(predicted))<1e-9,
                    "chosen replay correction, not provisional sweep, reaches motion integration");
        } finally { MeshMovementValidation.clear(id); MeshCollision.clear(id); MeshCollision.clear(other); }
    }
    private static void sustainedFlightAndDirectionChanges() {
        UUID id=UUID.randomUUID();
        var visible=wall(2); var current=wall(1.5); var index=new MeshCollision.Index(visible);
        Box body=BODY; int divergences=0,contacts=0;
        try {
            for(int segment=0;segment<6;segment++)for(int tick=0;tick<32;tick++) {
                MeshMovementValidation.record(id,visible); MeshMovementValidation.record(id,current);
                Vec3d input=segment%2==0 ? new Vec3d(.08,.01,.01) : new Vec3d(-.08,-.01,-.01);
                Vec3d prediction=MeshCollision.predicted(index,body,input,true,false,0);
                if(prediction.x<input.x-1e-6)contacts++;
                Vec3d claim=feet(body).add(prediction), provisional=sweep(current,body,claim);
                if(claim.distanceTo(provisional)>1e-4)divergences++;
                var decision=MeshMovementValidation.validate(id,body,claim,true);
                check(decision.allowed(),"sustained/reversed prediction passes collision gate: "+segment+"/"+tick);
                var accepted=SpiritMovementAcceptance.accept(claim,provisional,decision);
                check(!accepted.correctionRequired() && accepted.position().distanceTo(claim)<1e-9,
                        "final acceptance neither resets prediction nor rolls flight back: "+segment+"/"+tick);
                body=body.offset(accepted.position().subtract(feet(body)));
                check(MeshCollision.bodyClear(visible,body),"accepted movement stays outside the selected frame's wall");
            }
            check(divergences>0 && contacts>0,"fixture actually diverges across frames and reaches a real mesh wall");
        } finally { MeshMovementValidation.clear(id); }
    }
    public static void main(String[] args) {
        historicalReplaySurvivesFinalAcceptance();
        closestReplayNotFirstToleratedReplay();
        wallsAndFallbacksRemainAuthoritative();
        correctionAccountingUsesTheSameReplay();
        sustainedFlightAndDirectionChanges();
        System.out.println("SpiritFlightAcceptanceTest: "+checks+" checks passed (validation, sweep, final acceptance, correction accounting)");
    }
}
