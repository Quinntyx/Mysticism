package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import java.util.*;

/** Selection availability for newly discovered landmarks. The per-player sweep used to gate every
 * catalog offer against the sweep-START semantic snapshot: a landmark published mid-sweep (the
 * freshly generated terrain case) outside that stale radius was never offered, never selected and
 * never prepared as a navigation target until a whole later sweep finished. The sweep now retargets
 * to the current observer frame and restarts once the observer leaves the swept locality. */
public final class SelectionAvailabilityTest {
    private static int checks;
    private static final EmbeddingProfile PROFILE=new EmbeddingProfile("sel-test","pinned","tokenizer","prefix",
            Vec384f.ZERO().data().length,EmbeddingProfile.Normalization.NONE,"schema");
    private static final Basis384f IDENTITY=new Basis384f(vector(1,0,0),vector(0,1,0),vector(0,0,1));
    private static final int REPRESENTATIVE_SLOTS=7;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Vec384f vector(double x,double y,double z){
        float[] v=Vec384f.ZERO().data();v[0]=(float)x;v[1]=(float)y;v[2]=(float)z;return new Vec384f(v);
    }
    private static LandmarkMetadata metadata(BlockPoint anchor,double x){
        String id=LandmarkIds.seed("minecraft:overworld","sel-test",Landmark.Kind.BIOME,"minecraft:plains",anchor);
        Landmark header=new Landmark(id,"minecraft:overworld","sel-test",Landmark.Kind.BIOME,"minecraft:plains",anchor,
                Bounds.cube(anchor.x(),anchor.y(),anchor.z(),8),new LandmarkEmbedding(PROFILE,vector(x,0,0)),.5,
                new ActivityMetadata(0,0),new Ownership(List.of()),new SourceGeometry(List.of(),List.of()),0,"test");
        return new LandmarkMetadata(header,List.of());
    }
    private static double radiusSquared(){return MeshRepresentatives.RADIUS*MeshRepresentatives.RADIUS;}

    /** The defect: an offer gated by the stale sweep-start q rejects a landmark that is inside the
     * CURRENT selection radius; retargeting to the live observer frame admits it immediately. */
    private static void midSweepDiscoveryBecomesSelectable() {
        Vec384f sweepStart=vector(0,0,0),flown=vector(2,0,0),published=vector(3.2,0,0);
        check(published.squareDistance(sweepStart)>=radiusSquared(),"precondition: published landmark is outside the stale sweep-start radius");
        check(published.squareDistance(flown)<radiusSquared(),"precondition: published landmark is inside the current selection radius");
        var staleGate=new MeshRepresentatives();
        staleGate.begin(sweepStart,IDENTITY);
        staleGate.offer(metadata(new BlockPoint(0,0,0),3.2),published,.5);
        check(staleGate.finish().isEmpty(),"stale sweep-start gating rejects the freshly published landmark (documented defect)");
        var current=new MeshRepresentatives();
        current.begin(sweepStart,IDENTITY);
        current.retarget(flown,IDENTITY); // the fixed sweep offers against the live observer frame
        current.offer(metadata(new BlockPoint(0,0,0),3.2),published,.5);
        var selected=current.finish();
        check(selected.size()==1 && selected.getFirst().id().equals(LandmarkIds.seed("minecraft:overworld","sel-test",
                Landmark.Kind.BIOME,"minecraft:plains",new BlockPoint(0,0,0))),
                "newly published landmark becomes selectable without restarting or finishing a sweep");
    }

    /** The caller must restart a sweep whose origin the observer has flown away from; offers already
     * passed under the old origin can never cover the current locality again. */
    private static void sweepRestartWhenObserverLeavesSweptLocality() {
        var selection=new MeshRepresentatives();
        selection.begin(vector(0,0,0),IDENTITY);
        check(!selection.sweepStale(vector(0,0,0)),"a fresh sweep is current at its origin");
        check(!selection.sweepStale(vector(.5,0,0)),"small movement keeps the sweep usable");
        Vec384f flown=vector(2,0,0);
        check(flown.squareDistance(vector(0,0,0))>=radiusSquared(),"precondition: observer left the swept locality");
        check(selection.sweepStale(flown),"drifted sweep is reported stale so the caller restarts from a fresh cursor");
        selection.begin(flown,IDENTITY);
        check(!selection.sweepStale(flown),"restarted sweep is current at the new origin");
    }

    /** Existing guarantee preserved: retargeting must not loosen the CURRENT-radius admission gate,
     * so candidates offered near an abandoned position never activate around the new position. */
    private static void staleCandidatesCannotActivateOutsideCurrentRadius() {
        var selection=new MeshRepresentatives();
        selection.begin(vector(0,0,0),IDENTITY);
        selection.offer(metadata(new BlockPoint(1,0,0),0),vector(0,0,0),.5);
        selection.retarget(vector(4,0,0),IDENTITY); // observer flew far during the sweep
        check(selection.finish().isEmpty(),"stale candidates cannot activate outside the current radius");
    }

    /** Retargeting must not discard accumulated cluster statistics: previously selected landmarks
     * stay selectable alongside the newly published one. */
    private static void publishedOfferKeepsAccumulatedSelection() {
        var selection=new MeshRepresentatives();
        Vec384f q=vector(0,0,0);
        selection.begin(q,IDENTITY);
        selection.offer(metadata(new BlockPoint(1,0,0),0),vector(0,0,0),.5);
        selection.offer(metadata(new BlockPoint(2,0,0),0),vector(.8,0,0),.5); // beyond one cluster width
        selection.retarget(q,IDENTITY);
        // A distinct newly published location: outside every existing cluster, inside the radius.
        check(vector(0,.8,0).squareDistance(vector(0,0,0))>=MeshRepresentatives.CLUSTER*MeshRepresentatives.CLUSTER,
                "precondition: published location is a distinct cluster candidate");
        selection.offer(metadata(new BlockPoint(3,0,0),0),vector(0,.8,0),.5);
        var selected=selection.finish();
        check(selected.size()==3,"accumulated selection and the newly published landmark are both selectable");
        check(selection.finish().size()==3,"finish is stable and repeatable within one sweep");
    }

    /** Reservoir bounds are population-independent even when generation publishes many landmarks. */
    private static void selectionRemainsBoundedUnderPublicationBursts() {
        var selection=new MeshRepresentatives();
        selection.begin(vector(0,0,0),IDENTITY);
        for(int n=0;n<30;n++) {
            double a=n*2.399963; // golden-angle spread inside the selection radius
            double x=.3*n/30.0*Math.cos(a),y=.3*n/30.0*Math.sin(a),z=(n%7-3)*.15;
            selection.offer(metadata(new BlockPoint(n,0,0),x),vector(x,y,z),.5);
        }
        check(selection.finish().size()<=REPRESENTATIVE_SLOTS+1,"publication bursts stay inside the bounded representative slots");
    }

    /** Integration of the reported review defect: publication arrives while a sweep is in flight
     * and the sweep COMPLETES right after. Completion recomputes the selected set from the reservoir
     * and cancels prepared windows outside it, so a landmark offered against the PREVIOUS observer
     * frame (the commit listener runs before this tick's scan retarget) is rejected, dropped from
     * selected at completion, and delayed until another full sweep. Retarget-before-offer must keep
     * it in the reservoir so completion retains it. */
    private static void publicationImmediatelyBeforeSweepCompletion() {
        var selection=new MeshRepresentatives();
        Vec384f sweepStart=vector(0,0,0);
        selection.begin(sweepStart,IDENTITY);
        // Catalog region already passed by the in-flight sweep.
        selection.offer(metadata(new BlockPoint(1,0,0),0),vector(0,0,0),.5);
        // Observer flies on; publication lands just before sweep completion.
        Vec384f current=vector(2,0,0),published=vector(3.2,0,0);
        check(published.squareDistance(sweepStart)>=radiusSquared(),"precondition: published landmark outside the previous observer frame");
        check(published.squareDistance(current)<radiusSquared(),"precondition: published landmark inside the CURRENT radius");
        selection.retarget(current,IDENTITY); // the fixed publication path retargets before offering
        selection.offer(metadata(new BlockPoint(2,0,0),3.2),published,.5);
        var completed=selection.finish(); // sweep completion recomputes selected from the reservoir
        check(completed.size()==1 && completed.getFirst().id().equals(LandmarkIds.seed("minecraft:overworld","sel-test",
                Landmark.Kind.BIOME,"minecraft:plains",new BlockPoint(2,0,0))),
                "publication immediately before sweep completion survives the completion recompute");
        // The already-passed catalog region must not reactivate around the abandoned position.
        check(completed.stream().noneMatch(m->m.header().anchor().x()==1),"passed catalog region stays excluded at completion");
    }

    public static void main(String[] args){
        midSweepDiscoveryBecomesSelectable();
        publicationImmediatelyBeforeSweepCompletion();
        sweepRestartWhenObserverLeavesSweptLocality();
        staleCandidatesCannotActivateOutsideCurrentRadius();
        publishedOfferKeepsAccumulatedSelection();
        selectionRemainsBoundedUnderPublicationBursts();
        System.out.println("PASS selection availability: "+checks+" checks");
    }
}
