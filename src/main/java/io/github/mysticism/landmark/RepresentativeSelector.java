package io.github.mysticism.landmark;

import java.util.*;

/** Bounded deterministic radius-gated clustering/weighted medoids, NOT nearest-K.
 * Input order cannot affect seed IDs, ties, medoids or quotas. No connectivity merges occur.
 */
public final class RepresentativeSelector {
    public record Config(double semanticRadius,double clusterRadius,int maxCandidates,int maxRepresentatives,
                         double spatialCellBlocks,int perCellQuota,double distortionPenalty,double crowdingPenalty,
                         double replacementPenalty,double hysteresis,double blocksPerSourceBlock) {
        public Config {
            for(double d:new double[]{semanticRadius,clusterRadius,spatialCellBlocks,blocksPerSourceBlock})
                if(!Double.isFinite(d) || d<=0 || !Double.isFinite(d*d) || d*d==0) throw new IllegalArgumentException("selector scale");
            for(double d:new double[]{distortionPenalty,crowdingPenalty,replacementPenalty,hysteresis})
                if(!Double.isFinite(d) || d<0) throw new IllegalArgumentException("selector penalty");
            if(maxCandidates<1 || maxCandidates>4096 || maxRepresentatives<1 || maxRepresentatives>maxCandidates || maxRepresentatives>128 || perCellQuota<1)
                throw new IllegalArgumentException("selector budget");
        }
    }
    public record Representative(String seedId,String landmarkId,double importance,double distortion) {}
    public record RepresentativeSet(long attunementRevision,long projectionEpoch,long clusterEpoch,
                                    EmbeddingProfile profile,List<Representative> representatives) {
        public RepresentativeSet {
            if(attunementRevision<0 || projectionEpoch<0 || clusterEpoch<0) throw new IllegalArgumentException("selection revision");
            Objects.requireNonNull(profile); representatives=List.copyOf(representatives);
            if(representatives.stream().map(Representative::seedId).distinct().count()!=representatives.size()
                    || representatives.stream().map(Representative::landmarkId).distinct().count()!=representatives.size()) throw new IllegalArgumentException("duplicate representative");
        }
    }
    private record Candidate(Landmark landmark,Point3 projected,double score,double importance,double proximity,boolean pinned,float[] semantic) { String id() { return landmark.id(); } }
    private record Cost(double value,double distortion) {}
    private record SpatialCell(long x,long y,long z) {}
    public record SelectionStats(long semanticDistanceEvaluations,long medoidComponentOperations) {}
    private long distanceEvaluations,medoidComponents;
    public SelectionStats lastStats() { return new SelectionStats(distanceEvaluations,medoidComponents); }
    private final Config config;
    public RepresentativeSelector(Config config) { this.config=Objects.requireNonNull(config); }
    public RepresentativeSet select(Collection<Landmark> input,LandmarkEmbedding current,long currentRevision,
                                    ProjectionFrame frame,Point3 player,FogHorizons horizons,ImportancePolicy importance,
                                    long tick,long clusterEpoch,RepresentativeSet previous,Set<String> pinnedIds) {
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
            if(distance>=config.semanticRadius*config.semanticRadius) continue;
            Placement placement=frame.place(l,config.blocksPerSourceBlock);
            if(!horizons.shouldPrefetch(placement.projectedBounds(l.bounds()).distanceSquared(player))) continue;
            double bounded=importance.score(distance,config.semanticRadius,l.baseImportance(),l.activity(),tick);
            double score=proximityPriority(distance,config.semanticRadius,bounded);
            eligible.put(l.id(),new Candidate(l,placement.realmAnchor(),score,bounded,distance,pinnedIds.contains(l.id()),l.baseEmbedding().data()));
        }
        // The nearest inner-five-percent target supersedes stale cluster locks (even at zero).
        Candidate nearestTarget=eligible.values().stream().min(Comparator.comparingDouble(Candidate::proximity).thenComparing(Candidate::id)).orElse(null);
        if(nearestTarget!=null&&nearestTarget.proximity<=.0025*config.semanticRadius*config.semanticRadius){
            var keepPins=eligible.values().stream().filter(Candidate::pinned).filter(c->!c.id().equals(nearestTarget.id())).sorted(Comparator.comparingDouble(Candidate::proximity).thenComparing(Candidate::id)).limit(config.maxRepresentatives-1L).map(Candidate::id).collect(java.util.stream.Collectors.toSet());
            keepPins.add(nearestTarget.id());for(var entry:new ArrayList<>(eligible.entrySet())){Candidate c=entry.getValue();eligible.put(entry.getKey(),new Candidate(c.landmark,c.projected,c.score,c.importance,c.proximity,keepPins.contains(c.id()),c.semantic));}
        }
        if(eligible.values().stream().filter(Candidate::pinned).count()>config.maxRepresentatives)
            throw new IllegalArgumentException("pin budget exceeded");
        // Deterministic weighted budget, not distance truncation. Pins cannot bypass the hard gates.
        List<Candidate> candidates=eligible.values().stream().sorted(Comparator.comparing(Candidate::pinned).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed()).thenComparing(Candidate::id)).limit(config.maxCandidates).toList();
        TreeMap<String,Candidate> pool=new TreeMap<>(); for(Candidate c:candidates) pool.put(c.id(),c);
        List<Candidate> seeds=new ArrayList<>();
        Map<String,String> prior=new TreeMap<>();
        boolean priorValid=previous!=null && previous.clusterEpoch==clusterEpoch && previous.projectionEpoch==frame.epoch();
        if(priorValid) for(Representative rep:previous.representatives) prior.put(rep.seedId,rep.landmarkId);
        // Retain a pinned medoid's prior seed identity when the seed still exists in range.
        Map<String,String> pinnedBySeed=new TreeMap<>();
        for(Candidate c:candidates) if(c.pinned) {
            Candidate seed=c;
            for(var entry:prior.entrySet()) if(entry.getValue().equals(c.id()) && pool.containsKey(entry.getKey())) {
                Candidate oldSeed=pool.get(entry.getKey());
                if(distance(oldSeed,c)<config.clusterRadius*config.clusterRadius) seed=oldSeed;
                break;
            }
            if(pinnedBySeed.putIfAbsent(seed.id(),c.id())!=null) throw new IllegalArgumentException("conflicting cluster pins");
            seeds.add(seed);
        }
        if(seeds.size()>config.maxRepresentatives) throw new IllegalArgumentException("pin budget exceeded");
        for(String id:prior.keySet()) if(pool.containsKey(id) && !seeds.contains(pool.get(id)) && seeds.size()<config.maxRepresentatives) seeds.add(pool.get(id));
        Map<String,Double> nearestById=new HashMap<>();
        for(Candidate c:candidates) {
            double nearest=Double.POSITIVE_INFINITY;
            for(Candidate s:seeds) nearest=Math.min(nearest,distance(c,pool.get(pinnedBySeed.getOrDefault(s.id(),s.id()))));
            nearestById.put(c.id(),nearest);
        }
        while(seeds.size()<config.maxRepresentatives) {
            Candidate best=null; double bestCoverage=-1;
            for(Candidate c:candidates) {
                if(seeds.contains(c)) continue;
                double nearest=nearestById.get(c.id());
                if(!seeds.isEmpty() && nearest<config.clusterRadius*config.clusterRadius) continue;
                double coverage=seeds.isEmpty()?c.score:nearest*(0.25+0.75*c.score);
                if(best==null || coverage>bestCoverage || coverage==bestCoverage && c.id().compareTo(best.id())<0) { best=c; bestCoverage=coverage; }
            }
            if(best==null) break; seeds.add(best);
            for(Candidate c:candidates) nearestById.put(c.id(),Math.min(nearestById.get(c.id()),distance(c,best)));
        }
        seeds.sort(Comparator.<Candidate,Boolean>comparing(c->pinnedBySeed.containsKey(c.id())).reversed().thenComparingDouble(Candidate::proximity).thenComparing(Candidate::id));
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
            if(closest!=null && (nearest<config.clusterRadius*config.clusterRadius || closest.id().equals(c.id()))) groups.get(closest.id()).add(c);
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
                if(chosen.contains(c) || counts.getOrDefault(cell(c),0)>=config.perCellQuota) continue;
                Cost cost=cost(c,moments,chosen,retained,frame);
                if(best==null || cost.value<bestCost.value || cost.value==bestCost.value && c.id().compareTo(best.id())<0) { best=c; bestCost=cost; }
            }
            if(retained!=null && group.contains(retained) && pin==null && !chosen.contains(retained)
                    && counts.getOrDefault(cell(retained),0)<config.perCellQuota && best!=null && certified(retained,center,moments)) {
                Cost oldCost=cost(retained,moments,chosen,retained,frame);
                if(oldCost.value<=bestCost.value+config.hysteresis) { best=retained; bestCost=oldCost; }
            }
            if(best==null) { if(pin!=null) throw new IllegalArgumentException("pin spatial quota exceeded"); continue; }
            counts.merge(cell(best),1,Integer::sum); chosen.add(best);
            selected.add(new Representative(seed.id(),best.id(),best.importance,bestCost.distortion));
        }
        selected.sort(Comparator.comparing(Representative::seedId));
        return new RepresentativeSet(currentRevision,frame.epoch(),clusterEpoch,current.profile(),selected);
    }
    /** Finite inverse-square priority; importance stays bounded, outside radius contributes zero.
     * At five percent radius the proximity term is 2; exact coincidence is deterministic and finite. */
    public static double proximityPriority(double distanceSquared,double radius,double importance){
        if(!Double.isFinite(radius)||radius<=0||!Double.isFinite(radius*radius)||radius*radius==0||!Double.isFinite(distanceSquared)||distanceSquared<0||!Double.isFinite(importance)||importance<0||importance>1)throw new IllegalArgumentException("selection priority");
        double radiusSquared=radius*radius;if(distanceSquared>=radiusSquared)return 0;
        return .025+importance+Math.min(1e6,.005*radiusSquared/Math.max(distanceSquared,1e-12*radiusSquared));
    }
    private SpatialCell cell(Candidate c) {
        return new SpatialCell((long)Math.floor(c.projected.x()/config.spatialCellBlocks),(long)Math.floor(c.projected.y()/config.spatialCellBlocks),(long)Math.floor(c.projected.z()/config.spatialCellBlocks));
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
        return upper<config.clusterRadius*config.clusterRadius;
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
        double distortion=Math.max(0,total-projectedError)/(config.semanticRadius*config.semanticRadius);
        total=Math.max(0,total)/(config.semanticRadius*config.semanticRadius);
        double crowding=0;
        for(Candidate other:chosen) crowding+=Math.exp(-candidate.projected.distanceSquared(other.projected)/(config.spatialCellBlocks*config.spatialCellBlocks));
        double replacement=retained!=null && !retained.id().equals(candidate.id())?config.replacementPenalty:0;
        return new Cost(total+config.distortionPenalty*distortion+config.crowdingPenalty*crowding+replacement,distortion);
    }
}
