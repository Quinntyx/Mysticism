package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.RepresentativeSelector.*;

import java.util.*;

/** Frozen-placement adaptation of the wave-1 bounded selector; no core API changes.
 * Spatial gates, quotas, distortion and crowding use authoritative physical transforms.
 * Bounded deterministic radius-gated clustering/weighted medoids, NOT nearest-K.
 * Input order cannot affect seed IDs, ties, medoids or quotas. No connectivity merges occur.
 */
final class FrozenTerrainSelector {
    private record Candidate(Landmark landmark,Point3 projected,double score,boolean pinned,float[] semantic) { String id() { return landmark.id(); } }
    private record Cost(double value,double distortion) {}
    private record SpatialCell(long x,long y,long z) {}
    public record SelectionStats(long semanticDistanceEvaluations,long medoidComponentOperations) {}
    private long distanceEvaluations,medoidComponents;
    public SelectionStats lastStats() { return new SelectionStats(distanceEvaluations,medoidComponents); }
    private final Config config;
    FrozenTerrainSelector(TerrainConfig terrain) {
        this.config=new Config(terrain.semanticRadius(),terrain.clusterRadius(),terrain.catalogLimit(),
                terrain.representatives(),32,2,.7,.5,.2,.08,1);
    }
    public RepresentativeSet select(Collection<Landmark> input,LandmarkEmbedding current,long currentRevision,
                                    ProjectionFrame frame,Point3 player,FogHorizons horizons,ImportancePolicy importance,
                                    long tick,long clusterEpoch,RepresentativeSet previous,Set<String> pinnedIds,java.util.function.Function<Landmark,Placement> placements) {
        distanceEvaluations=0; medoidComponents=0;
        current.profile().requireCompatible(frame.semanticOrigin().profile());
        if(previous!=null) previous.profile().requireCompatible(current.profile());
        TreeMap<String,Candidate> eligible=new TreeMap<>();
        Set<String> seen=new HashSet<>();
        for(Landmark l:input) {
            if(!seen.add(l.id())) throw new IllegalArgumentException("duplicate candidate");
            current.profile().requireCompatible(l.baseEmbedding().profile());
            distanceEvaluations++; double distance=l.baseEmbedding().distanceSquared(current);
            // Both hard gates MUST precede importance, pinning, clustering and replacement costs.
            if(distance>=config.semanticRadius()*config.semanticRadius()) continue;
            Placement placement=Objects.requireNonNull(placements.apply(l));
            if(!horizons.shouldPrefetch(placement.projectedBounds(l.bounds()).distanceSquared(player))) continue;
            double score=importance.score(distance,config.semanticRadius(),l.baseImportance(),l.activity(),tick);
            eligible.put(l.id(),new Candidate(l,placement.realmAnchor(),score,pinnedIds.contains(l.id()),l.baseEmbedding().data()));
        }
        if(eligible.values().stream().filter(Candidate::pinned).count()>config.maxRepresentatives())
            throw new IllegalArgumentException("pin budget exceeded");
        // Deterministic weighted budget, not distance truncation. Pins cannot bypass the hard gates.
        List<Candidate> candidates=eligible.values().stream().sorted(Comparator.comparing(Candidate::pinned).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed()).thenComparing(Candidate::id)).limit(config.maxCandidates()).toList();
        TreeMap<String,Candidate> pool=new TreeMap<>(); for(Candidate c:candidates) pool.put(c.id(),c);
        List<Candidate> seeds=new ArrayList<>();
        Map<String,String> prior=new TreeMap<>();
        boolean priorValid=previous!=null && previous.clusterEpoch()==clusterEpoch && previous.projectionEpoch()==frame.epoch();
        if(priorValid) for(Representative rep:previous.representatives()) prior.put(rep.seedId(),rep.landmarkId());
        // Retain a pinned medoid's prior seed identity when the seed still exists in range.
        Map<String,String> pinnedBySeed=new TreeMap<>();
        for(Candidate c:candidates) if(c.pinned) {
            Candidate seed=c;
            for(var entry:prior.entrySet()) if(entry.getValue().equals(c.id()) && pool.containsKey(entry.getKey())) {
                Candidate oldSeed=pool.get(entry.getKey());
                if(distance(oldSeed,c)<config.clusterRadius()*config.clusterRadius()) seed=oldSeed;
                break;
            }
            if(pinnedBySeed.putIfAbsent(seed.id(),c.id())!=null) throw new IllegalArgumentException("conflicting cluster pins");
            seeds.add(seed);
        }
        if(seeds.size()>config.maxRepresentatives()) throw new IllegalArgumentException("pin budget exceeded");
        for(String id:prior.keySet()) if(pool.containsKey(id) && !seeds.contains(pool.get(id)) && seeds.size()<config.maxRepresentatives()) seeds.add(pool.get(id));
        Map<String,Double> nearestById=new HashMap<>();
        for(Candidate c:candidates) {
            double nearest=Double.POSITIVE_INFINITY;
            for(Candidate s:seeds) nearest=Math.min(nearest,distance(c,pool.get(pinnedBySeed.getOrDefault(s.id(),s.id()))));
            nearestById.put(c.id(),nearest);
        }
        while(seeds.size()<config.maxRepresentatives()) {
            Candidate best=null; double bestCoverage=-1;
            for(Candidate c:candidates) {
                if(seeds.contains(c)) continue;
                double nearest=nearestById.get(c.id());
                if(!seeds.isEmpty() && nearest<config.clusterRadius()*config.clusterRadius()) continue;
                double coverage=seeds.isEmpty()?c.score:nearest*(0.25+0.75*c.score);
                if(best==null || coverage>bestCoverage || coverage==bestCoverage && c.id().compareTo(best.id())<0) { best=c; bestCoverage=coverage; }
            }
            if(best==null) break; seeds.add(best);
            for(Candidate c:candidates) nearestById.put(c.id(),Math.min(nearestById.get(c.id()),distance(c,best)));
        }
        seeds.sort(Comparator.<Candidate,Boolean>comparing(c->pinnedBySeed.containsKey(c.id())).reversed().thenComparing(Candidate::id));
        Map<String,List<Candidate>> groups=new TreeMap<>(); for(Candidate s:seeds) groups.put(s.id(),new ArrayList<>());
        for(Candidate c:candidates) {
            String forcedSeed=null;
            for(var pin:pinnedBySeed.entrySet()) if(pin.getValue().equals(c.id())) { forcedSeed=pin.getKey(); break; }
            if(forcedSeed==null && groups.containsKey(c.id())) forcedSeed=c.id();
            if(forcedSeed!=null) { groups.get(forcedSeed).add(c); continue; }
            Candidate closest=null; double nearest=Double.POSITIVE_INFINITY;
            for(Candidate s:seeds) {
                Candidate center=pool.get(pinnedBySeed.getOrDefault(s.id(),s.id()));
                double d=distance(c,center);
                if(d<nearest || d==nearest && (closest==null || s.id().compareTo(closest.id())<0)) { nearest=d; closest=s; }
            }
            // A cluster has a strict reconstruction radius; uncovered points remain non-represented.
            if(closest!=null && (nearest<config.clusterRadius()*config.clusterRadius() || closest.id().equals(c.id()))) groups.get(closest.id()).add(c);
        }
        List<Representative> selected=new ArrayList<>(); List<Candidate> chosen=new ArrayList<>(); Map<SpatialCell,Integer> counts=new HashMap<>();
        for(Candidate seed:seeds) {
            List<Candidate> group=groups.get(seed.id()); Candidate best=null; Cost bestCost=null;
            String previousId=prior.get(seed.id());
            Candidate retained=previousId==null?null:pool.get(previousId);
            String pin=pinnedBySeed.get(seed.id());
            Candidate center=pin==null?seed:pool.get(pin);
            group.sort(Comparator.comparing(Candidate::id));
            Moments moments=new Moments(group);
            for(Candidate c:group) {
                if(pin!=null && !c.id().equals(pin)) continue;
                if(!certified(c,center,moments)) continue;
                if(chosen.contains(c) || counts.getOrDefault(cell(c),0)>=config.perCellQuota()) continue;
                Cost cost=cost(c,moments,chosen,retained,frame);
                if(best==null || cost.value<bestCost.value || cost.value==bestCost.value && c.id().compareTo(best.id())<0) { best=c; bestCost=cost; }
            }
            if(retained!=null && group.contains(retained) && pin==null && !chosen.contains(retained)
                    && counts.getOrDefault(cell(retained),0)<config.perCellQuota() && best!=null && certified(retained,center,moments)) {
                Cost oldCost=cost(retained,moments,chosen,retained,frame);
                if(oldCost.value<=bestCost.value+config.hysteresis()) { best=retained; bestCost=oldCost; }
            }
            if(best==null) { if(pin!=null) throw new IllegalArgumentException("pin spatial quota exceeded"); continue; }
            counts.merge(cell(best),1,Integer::sum); chosen.add(best);
            selected.add(new Representative(seed.id(),best.id(),best.score,bestCost.distortion));
        }
        selected.sort(Comparator.comparing(Representative::seedId));
        return new RepresentativeSet(currentRevision,frame.epoch(),clusterEpoch,current.profile(),selected);
    }
    private SpatialCell cell(Candidate c) {
        return new SpatialCell((long)Math.floor(c.projected.x()/config.spatialCellBlocks()),(long)Math.floor(c.projected.y()/config.spatialCellBlocks()),(long)Math.floor(c.projected.z()/config.spatialCellBlocks()));
    }
    private double distance(Candidate a,Candidate b) {
        distanceEvaluations++; double sum=0;
        for(int i=0;i<a.semantic.length;i++) { double d=(double)a.semantic[i]-b.semantic[i]; sum+=d*d; }
        return sum;
    }
    /** Weighted Welford moments about a local origin: stable even for large common offsets. */
    private final class Moments {
        final float[] origin;
        final double[] mean,min,max;
        final Point3 projectedOrigin;
        final double[] projectedMean=new double[3];
        double variance,projectedVariance,weight;
        Moments(List<Candidate> group) {
            Candidate first=group.getFirst(); origin=first.semantic; projectedOrigin=first.projected;
            mean=new double[origin.length]; min=new double[origin.length]; max=new double[origin.length];
            Arrays.fill(min,Double.POSITIVE_INFINITY); Arrays.fill(max,Double.NEGATIVE_INFINITY);
            for(Candidate c:group) {
                double w=0.01+c.score,next=weight+w;
                for(int i=0;i<mean.length;i++) {
                    medoidComponents++;
                    double value=(double)c.semantic[i]-origin[i],delta=value-mean[i];
                    mean[i]+=delta*w/next; variance+=w*delta*(value-mean[i]);
                    min[i]=Math.min(min[i],c.semantic[i]); max[i]=Math.max(max[i],c.semantic[i]);
                }
                double[] projected={c.projected.x()-projectedOrigin.x(),c.projected.y()-projectedOrigin.y(),c.projected.z()-projectedOrigin.z()};
                for(int i=0;i<3;i++) {
                    double delta=projected[i]-projectedMean[i]; projectedMean[i]+=delta*w/next;
                    projectedVariance+=w*delta*(projected[i]-projectedMean[i]);
                }
                weight=next;
            }
        }
    }
    /** A conservative AABB certificate avoids all-pairs distance work.
     * The actual assignment center is known feasible from strict membership gates.
     * Other medoids must prove radius for every member; valid medoids may be conservatively rejected.
     */
    private boolean certified(Candidate c,Candidate center,Moments moments) {
        if(c.id().equals(center.id())) return true;
        double upper=0;
        for(int i=0;i<c.semantic.length;i++) {
            medoidComponents++;
            double a=c.semantic[i]-moments.min[i],b=c.semantic[i]-moments.max[i]; upper+=Math.max(a*a,b*b);
        }
        return upper<config.clusterRadius()*config.clusterRadius();
    }
    private Cost cost(Candidate candidate,Moments moments,List<Candidate> chosen,Candidate retained,ProjectionFrame frame) {
        double total=moments.variance/moments.weight;
        for(int i=0;i<candidate.semantic.length;i++) {
            medoidComponents++; double delta=(double)candidate.semantic[i]-moments.origin[i]-moments.mean[i]; total+=delta*delta;
        }
        double[] projected={candidate.projected.x()-moments.projectedOrigin.x(),candidate.projected.y()-moments.projectedOrigin.y(),candidate.projected.z()-moments.projectedOrigin.z()};
        double projectedError=moments.projectedVariance/moments.weight;
        for(int i=0;i<3;i++) { double delta=projected[i]-moments.projectedMean[i]; projectedError+=delta*delta; }
        projectedError/=frame.blocksPerSemanticUnit()*frame.blocksPerSemanticUnit();
        double distortion=Math.max(0,total-projectedError)/(config.semanticRadius()*config.semanticRadius());
        total=Math.max(0,total)/(config.semanticRadius()*config.semanticRadius());
        double crowding=0;
        for(Candidate other:chosen) crowding+=Math.exp(-candidate.projected.distanceSquared(other.projected)/(config.spatialCellBlocks()*config.spatialCellBlocks()));
        double replacement=retained!=null && !retained.id().equals(candidate.id())?config.replacementPenalty():0;
        return new Cost(total+config.distortionPenalty()*distortion+config.crowdingPenalty()*crowding+replacement,distortion);
    }
}
