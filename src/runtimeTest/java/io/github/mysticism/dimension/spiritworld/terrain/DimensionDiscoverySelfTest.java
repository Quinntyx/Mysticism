package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.vector.*;
import java.util.*;

/** Exercises production representative admission/hysteresis with identical foreign vectors. */
public final class DimensionDiscoverySelfTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        check(DimensionDiscoverySelfTest.class.desiredAssertionStatus(),"main-based regression runs with assertions");
        int ownershipChecks=DimensionOwnershipChecks.run();
        String local=DimensionOwnershipChecks.LOCAL,foreign=DimensionOwnershipChecks.FOREIGN;
        check(SpiritTerrainService.sourceNamespaceAllows(local,local,false),"same-dimension discovery projects normally");
        check(!SpiritTerrainService.sourceNamespaceAllows(local,foreign,false),"foreign discovery cannot render/collide or authorize held-frame support");
        check(SpiritTerrainService.sourceNamespaceAllows(local,foreign,true),"explicit validated target keeps its independent source namespace");
        check(!SpiritTerrainService.sourceNamespaceAllows(foreign,local,false),"old local discovery revoked after explicit dimension acquisition");
        var localMetadata=DimensionOwnershipChecks.metadata(DimensionOwnershipChecks.landmark(local,0));
        var foreignMetadata=DimensionOwnershipChecks.metadata(DimensionOwnershipChecks.landmark(foreign,0));
        var q=localMetadata.header().baseEmbedding().vector();var basis=new Basis384f();
        var selection=new MeshRepresentatives();selection.begin(local,q,basis);
        for(int i=0;i<200;i++)selection.offer(DimensionOwnershipChecks.metadata(DimensionOwnershipChecks.landmark(foreign,i*4L)),q,1);
        check(selection.finish().isEmpty(),"foreign-only catalogue has no local representative or nearest support");
        selection.begin(local,q,basis);selection.offer(foreignMetadata,q,1);selection.offer(localMetadata,q,.1);
        check(selection.finish().equals(List.of(localMetadata)),"foreign closest/high importance cannot displace true local support");
        selection.begin(local,q,basis);selection.offer(localMetadata,q,.1);
        check(selection.finish().equals(List.of(localMetadata)),"same-dimension sweep preserves stable medoid");
        selection.begin(foreign,q,basis);selection.offer(localMetadata,q,1);
        check(selection.finish().isEmpty(),"explicit dimension acquisition discards old namespace hysteresis");
        selection.begin(foreign,q,basis);selection.offer(foreignMetadata,q,.1);selection.offer(localMetadata,q,1);
        check(selection.finish().equals(List.of(foreignMetadata)),"new source dimension independently selects its own representative");
        selection.begin(local,q,basis);selection.offer(foreignMetadata,q,1);
        check(selection.finish().isEmpty(),"switching back cannot resurrect other-dimension representative");
        System.out.println("DimensionDiscoverySelfTest: "+(checks+ownershipChecks)+" checks passed (no game/model startup)");
    }
}
