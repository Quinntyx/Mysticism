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
    private record Candidate(Landmark landmark,Point3 projected,double score,boolean pinned) { String id() { return landmark.id(); } }
    private record Cost(double value,double distortion) {}
    private record SpatialCell(long x,long y,long z) {}
    private final Config config;
    public RepresentativeSelector(Config config) { this.config=Objects.requireNonNull(config); }
    public RepresentativeSet select(Collection<Landmark> input,LandmarkEmbedding current,long currentRevision,
                                    ProjectionFrame frame,Point3 player,FogHorizons horizons,ImportancePolicy importance,
                                    long tick,long clusterEpoch,RepresentativeSet previous,Set<String> pinnedIds) {
        current.profile().requireCompatible(frame.semanticOrigin().profile());
        if(previous!=null) previous.profile().requireCompatible(current.profile());
        TreeMap<String,Candidate> eligible=new TreeMap<>();
        Set<String> seen=new HashSet<>();
        for(Landmark l:input) {
            if(!seen.add(l.id())) throw new IllegalArgumentException("duplicate candidate");
            current.profile().requireCompatible(l.baseEmbedding().profile());
            double distance=l.baseEmbedding().distanceSquared(current);
            // Both hard gates MUST precede importance, pinning, clustering and replacement costs.
            if(distance>=config.semanticRadius*config.semanticRadius) continue;
            Placement placement=frame.place(l,config.blocksPerSourceBlock);
            if(!horizons.shouldPrefetch(placement.projectedBounds(l.bounds()).distanceSquared(player))) continue;
            double score=importance.score(distance,config.semanticRadius,l.baseImportance(),l.activity(),tick);
            eligible.put(l.id(),new Candidate(l,placement.realmAnchor(),score,pinnedIds.contains(l.id())));
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
                if(oldSeed.landmark.baseEmbedding().distanceSquared(c.landmark.baseEmbedding())<config.clusterRadius*config.clusterRadius) seed=oldSeed;
                break;
            }
            if(pinnedBySeed.putIfAbsent(seed.id(),c.id())!=null) throw new IllegalArgumentException("conflicting cluster pins");
            seeds.add(seed);
        }
        if(seeds.size()>config.maxRepresentatives) throw new IllegalArgumentException("pin budget exceeded");
        for(String id:prior.keySet()) if(pool.containsKey(id) && !seeds.contains(pool.get(id)) && seeds.size()<config.maxRepresentatives) seeds.add(pool.get(id));
        while(seeds.size()<config.maxRepresentatives) {
            Candidate best=null; double bestCoverage=-1;
            for(Candidate c:candidates) {
                if(seeds.contains(c)) continue;
                double nearest=Double.POSITIVE_INFINITY;
                for(Candidate s:seeds) nearest=Math.min(nearest,c.landmark.baseEmbedding().distanceSquared(s.landmark.baseEmbedding()));
                if(!seeds.isEmpty() && nearest<config.clusterRadius*config.clusterRadius) continue;
                double coverage=seeds.isEmpty()?c.score:nearest*(0.25+0.75*c.score);
                if(best==null || coverage>bestCoverage || coverage==bestCoverage && c.id().compareTo(best.id())<0) { best=c; bestCoverage=coverage; }
            }
            if(best==null) break; seeds.add(best);
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
                double d=c.landmark.baseEmbedding().distanceSquared(s.landmark.baseEmbedding());
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
            for(Candidate c:group) {
                if(pin!=null && !c.id().equals(pin)) continue;
                if(chosen.contains(c) || counts.getOrDefault(cell(c),0)>=config.perCellQuota) continue;
                Cost cost=cost(c,group,chosen,retained,frame);
                if(best==null || cost.value<bestCost.value || cost.value==bestCost.value && c.id().compareTo(best.id())<0) { best=c; bestCost=cost; }
            }
            if(retained!=null && group.contains(retained) && pin==null && !chosen.contains(retained)
                    && counts.getOrDefault(cell(retained),0)<config.perCellQuota && best!=null) {
                Cost oldCost=cost(retained,group,chosen,retained,frame);
                if(oldCost.value<=bestCost.value+config.hysteresis) { best=retained; bestCost=oldCost; }
            }
            if(best==null) { if(pin!=null) throw new IllegalArgumentException("pin spatial quota exceeded"); continue; }
            counts.merge(cell(best),1,Integer::sum); chosen.add(best);
            selected.add(new Representative(seed.id(),best.id(),best.score,bestCost.distortion));
        }
        selected.sort(Comparator.comparing(Representative::seedId));
        return new RepresentativeSet(currentRevision,frame.epoch(),clusterEpoch,current.profile(),selected);
    }
    private SpatialCell cell(Candidate c) {
        return new SpatialCell((long)Math.floor(c.projected.x()/config.spatialCellBlocks),(long)Math.floor(c.projected.y()/config.spatialCellBlocks),(long)Math.floor(c.projected.z()/config.spatialCellBlocks));
    }
    private Cost cost(Candidate candidate,List<Candidate> group,List<Candidate> chosen,Candidate retained,ProjectionFrame frame) {
        double total=0,distortion=0,weight=0;
        for(Candidate member:group) {
            double w=0.01+member.score;
            double semantic=candidate.landmark.baseEmbedding().distanceSquared(member.landmark.baseEmbedding());
            double projected=candidate.projected.distanceSquared(member.projected)/(frame.blocksPerSemanticUnit()*frame.blocksPerSemanticUnit());
            total+=w*semantic; distortion+=w*Math.abs(semantic-projected); weight+=w;
        }
        total=total/weight/(config.semanticRadius*config.semanticRadius);
        distortion=distortion/weight/(config.semanticRadius*config.semanticRadius);
        double crowding=0;
        for(Candidate other:chosen) crowding+=Math.exp(-candidate.projected.distanceSquared(other.projected)/(config.spatialCellBlocks*config.spatialCellBlocks));
        double replacement=retained!=null && !retained.id().equals(candidate.id())?config.replacementPenalty:0;
        return new Cost(total+config.distortionPenalty*distortion+config.crowdingPenalty*crowding+replacement,distortion);
    }
}
