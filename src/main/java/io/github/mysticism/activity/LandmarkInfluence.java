package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.Vec384f;
import java.util.*;

/** The production event reducer. Publication is deliberately left to the core's guarded plan. */
final class LandmarkInfluence {
    static final ImportancePolicy POLICY=new ImportancePolicy(1,24000,0.00002,0.35);
    record Reduced(LandmarkActivityState.Influence influence,ActivityMetadata activity,Ownership ownership){}
    static Reduced reduce(Landmark header,LandmarkActivityState.Influence old,Vec384f stimulus,BlockPoint point,
                          UUID owner,double strength,boolean claim,int owned,long now){
        LandmarkProfiles.current().requireCompatible(header.baseEmbedding().profile());
        if(!Double.isFinite(strength)||strength<0||strength>1||now<0||owned<0)throw new IllegalArgumentException("stimulus");
        double relevance=ActivityMath.relevance(header.bounds().distanceSquared(new Point3(point.x(),point.y(),point.z())),24);
        Vec384f base=old==null?header.baseEmbedding().vector():old.vector;
        var next=new LandmarkActivityState.Influence(ActivityMath.drift(base,stimulus,0.02*relevance),
                Math.min(0.35,(old==null?0:old.level(now))+0.002*strength*relevance),now);
        next.claims=old==null?null:old.claims;if(old!=null)next.owners.addAll(old.owners);
        Ownership ownership=header.ownership();
        boolean existing=owner!=null&&ownership.claims().stream().anyMatch(c->c.player().equals(owner));
        if(relevance>0&&claim&&owner!=null&&header.bounds().contains(point.x(),point.y(),point.z())
                &&(ownership.claims().size()<16||existing)&&(next.owners.size()<16||next.owners.contains(owner.toString()))&&(owned<8||existing)){
            try{
                var tree=next.claims==null?SparseOctree.<String>empty(Bounds.cube(Math.floorDiv(point.x(),8)*8,Math.floorDiv(point.y(),8)*8,Math.floorDiv(point.z(),8)*8,8),1,512):next.claims;
                var expanded=tree.with(Bounds.cube(point.x(),point.y(),point.z(),1),owner.toString(),256);
                expanded.cells(LandmarkActivityState.CELL_LIMIT);
                next.claims=expanded;next.owners.add(owner.toString());ownership=ownership.merge(new Ownership(List.of(new Ownership.Claim(owner,now))));
            }catch(IllegalArgumentException budget){ /* Retain old immutable claims; influence still advances. */ }
        }
        var activity=header.activity().advance(Math.max(now,header.activity().evaluatedTick()),strength*relevance,POLICY);
        return new Reduced(next,activity,ownership);
    }
}
