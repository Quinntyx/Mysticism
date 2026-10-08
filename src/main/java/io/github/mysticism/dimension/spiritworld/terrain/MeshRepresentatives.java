package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.LandmarkMetadata;
import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.Vec384f;
import java.util.*;

/** Streaming fixed-size cluster reservoir; every radius candidate is considered without hydrating the catalog.
 * Cluster slots do not depend on population. Medoids minimize reconstruction plus lost projection variance. */
final class MeshRepresentatives {
    static final double RADIUS=1.5, CLUSTER=.4;
    private static final int SLOTS=7;
    private final List<Cluster> clusters=new ArrayList<>();
    private LandmarkMetadata closest;
    private Vec384f closestVector;
    private double closestDistance=Double.POSITIVE_INFINITY;
    private Vec384f q,sweepOrigin;
    private Basis384f basis;
    private static final class Cluster {
        final String seed;final Vec384f center;
        LandmarkMetadata best,observed;Vec384f bestVector,observedVector;boolean bestSeen;double observedImportance;
        final double[] sum;double weight,norm;double importance;
        Cluster(LandmarkMetadata m,Vec384f v,double importance){seed=m.id();center=v.clone();best=m;bestVector=v.clone();sum=new double[v.data().length];this.importance=importance;}
        void observe(Vec384f v,double w){float[] d=v.data();for(int i=0;i<d.length;i++)sum[i]+=d[i]*w;weight+=w;norm+=v.l2sq()*w;}
    }
    void begin(Vec384f q,Basis384f basis) {
        this.q=q.clone();sweepOrigin=q.clone();this.basis=basis.clone();closest=null;closestVector=null;closestDistance=Double.POSITIVE_INFINITY;
        for(Cluster c:clusters){Arrays.fill(c.sum,0);c.weight=0;c.norm=0;c.bestSeen=false;c.observed=null;c.observedVector=null;}
    }
    /** A long sweep must gate offers against the player's CURRENT observer frame, not the
     * sweep-start snapshot; otherwise landmarks discovered after the sweep began (exactly the
     * freshly generated terrain case) are never offered. Accumulated cluster statistics stay valid. */
    void retarget(Vec384f current,Basis384f currentBasis) {
        if(current!=null)this.q=current.clone();
        if(currentBasis!=null)this.basis=currentBasis.clone();
    }
    /** True when the observer moved far enough from the sweep origin that catalog regions the
     * sweep already passed can never be offered again this pass; the caller restarts the sweep. */
    boolean sweepStale(Vec384f current) {
        return sweepOrigin==null || current==null || current.squareDistance(sweepOrigin)>=RADIUS*RADIUS;
    }
    void offer(LandmarkMetadata metadata,Vec384f vector,double importance) {
        double distance=vector.squareDistance(q);
        if(!Double.isFinite(distance) || distance>=RADIUS*RADIUS)return;
        importance=Math.max(0,Math.min(1,importance));
        if(distance<closestDistance || distance==closestDistance && (closest==null || metadata.id().compareTo(closest.id())<0)) {closest=metadata;closestVector=vector.clone();closestDistance=distance;}
        Cluster nearest=null;double separation=Double.POSITIVE_INFINITY;
        for(Cluster c:clusters){double d=vector.squareDistance(c.center);if(d<separation){separation=d;nearest=c;}}
        if(nearest==null || separation>CLUSTER*CLUSTER) {
            Cluster added=new Cluster(metadata,vector,importance);
            if(clusters.size()<SLOTS){clusters.add(added);nearest=added;}
            else {
                // Population-independent weighted novelty, not "keep the densest seven".
                Cluster weakest=clusters.stream().min(Comparator.comparingDouble(c->priority(c.bestVector,c.importance))).orElseThrow();
                if(priority(vector,importance)+Math.min(1,separation)/(RADIUS*RADIUS)>priority(weakest.bestVector,weakest.importance)+.18) {
                    clusters.remove(weakest);clusters.add(added);nearest=added;
                } else return;
            }
        }
        nearest.observe(vector,1+importance);
        double candidate=cost(nearest,vector)-.08*importance;
        double existing=cost(nearest,nearest.bestVector)-.08*nearest.importance;
        if(nearest.observed==null || candidate<cost(nearest,nearest.observedVector)-.08*nearest.observedImportance) {
            nearest.observed=metadata;nearest.observedVector=vector.clone();nearest.observedImportance=importance;
        }
        if(metadata.id().equals(nearest.best.id()) || candidate+.015<existing) {
            nearest.best=metadata;nearest.bestVector=vector.clone();nearest.importance=importance;nearest.bestSeen=true;
        }
    }
    private double priority(Vec384f v,double importance) {
        // At the inner 5% the proximity term exceeds the entire bounded importance range.
        return importance+Math.min(64,Math.pow(.05*RADIUS,2)/Math.max(1e-9,v.squareDistance(q)));
    }
    private double cost(Cluster c,Vec384f candidate) {
        if(c.weight==0)return 0;
        float[] v=candidate.data(),i=basis.i.data(),j=basis.j.data(),k=basis.k.data();
        double dot=0,di=0,dj=0,dk=0,si=0,sj=0,sk=0;
        for(int n=0;n<v.length;n++){dot+=v[n]*c.sum[n];di+=v[n]*i[n];dj+=v[n]*j[n];dk+=v[n]*k[n];si+=c.sum[n]*i[n];sj+=c.sum[n]*j[n];sk+=c.sum[n]*k[n];}
        double error=Math.max(0,c.norm-2*dot+candidate.l2sq()*c.weight);
        double projected=c.weight*(di*di+dj*dj+dk*dk)-2*(di*si+dj*sj+dk*sk);
        // Candidate-dependent part of semantic error minus projected error; missing variance is constant per cluster.
        return (error+Math.max(0,error-projected))/(c.weight*RADIUS*RADIUS);
    }
    List<LandmarkMetadata> finish() {
        var selected=new LinkedHashMap<String,LandmarkMetadata>();
        // Exact/very close location always receives a slot, never excluded by stale cluster hysteresis.
        // The slot still honors the CURRENT radius: a retargeted long sweep must not reactivate a
        // candidate offered near an abandoned position.
        if(closest!=null && closestVector!=null && closestVector.squareDistance(q)<RADIUS*RADIUS)selected.put(closest.id(),closest);
        for(Cluster c:clusters) {
            if(!c.bestSeen && c.observed!=null){c.best=c.observed;c.bestVector=c.observedVector;c.importance=c.observedImportance;}
            if(c.weight>0 && c.bestVector.squareDistance(q)<RADIUS*RADIUS)selected.put(c.best.id(),c.best);
        }
        clusters.removeIf(c->c.weight==0 || c.bestVector.squareDistance(q)>=RADIUS*RADIUS);
        return List.copyOf(selected.values());
    }
}
