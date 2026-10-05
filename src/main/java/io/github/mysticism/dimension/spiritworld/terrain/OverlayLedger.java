package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.*;

/** Compare-and-restore overlay ownership, also used by the actual ServerWorld adapter. */
public final class OverlayLedger<S> {
    public record Pos(int x, int y, int z) {
        public Region region() { return new Region(Math.floorDiv(x,8),Math.floorDiv(y,8),Math.floorDiv(z,8)); }
    }
    public record Region(int x,int y,int z) implements Comparable<Region> {
        public Pos origin() { return new Pos(Math.multiplyExact(x,8),Math.multiplyExact(y,8),Math.multiplyExact(z,8)); }
        public int compareTo(Region r) { int c=Integer.compare(x,r.x); if(c==0)c=Integer.compare(y,r.y); return c==0?Integer.compare(z,r.z):c; }
    }
    public record Entry<S>(S original,S generated,String owner,boolean protectedEdit) {
        public Entry { Objects.requireNonNull(original); Objects.requireNonNull(generated); Objects.requireNonNull(owner); }
    }
    public record Desired<S>(S state,String owner) {}
    public interface WorldAccess<S> {
        boolean loaded(Region region);
        boolean mayChange(Region region);
        S get(Pos pos);
        boolean replaceable(Pos pos,S state);
        boolean set(Pos pos,S state);
    }
    public interface Sampler<S> { Desired<S> sample(Pos pos); }
    private record Change<S>(Pos pos,S before,S after) {}
    private final int limit;
    private final Map<Pos,Entry<S>> entries=new HashMap<>();
    private final NavigableSet<Region> regions=new TreeSet<>();
    private final Map<String,Integer> ownerCounts=new HashMap<>();
    public int ownedCount(String owner) { return ownerCounts.getOrDefault(owner,0); }
    private void count(Entry<S> e,int delta) {
        if(e!=null && !e.protectedEdit)ownerCounts.compute(e.owner,(k,v)->{ int n=(v==null?0:v)+delta; return n==0?null:n; });
    }
    public OverlayLedger(int limit) { if(limit<512 || limit>65536)throw new IllegalArgumentException("ledger budget"); this.limit=limit; }
    public Map<Pos,Entry<S>> entries() { return Collections.unmodifiableMap(entries); }
    public Set<Region> regions() { return Collections.unmodifiableSet(regions); }
    public Entry<S> entry(Pos p) { return entries.get(p); }
    public void restore(Map<Pos,Entry<S>> saved, Collection<Region> savedRegions) {
        if(!entries.isEmpty() || !regions.isEmpty() || saved.size()>limit || savedRegions.size()>limit/512)
            throw new IllegalArgumentException("saved ledger bounds");
        entries.putAll(saved); regions.addAll(savedRegions); saved.values().forEach(e->count(e,1));
    }
    public void protect(Pos p) {
        var e=entries.get(p); if(e!=null) { count(e,-1); entries.put(p,new Entry<>(e.original,e.generated,e.owner,true)); }
    }
    /** Preflight the entire 8^3 region before any writes. World must not dispatch neighbors
     * during set; production uses FORCE_STATE + NOTIFY_LISTENERS, on the server thread.
     * Rollback is best-effort if a foreign world adapter itself fails during rollback. */
    public boolean reconcile(Region region, WorldAccess<S> world, Sampler<S> sampler) {
        if(!world.loaded(region) || !world.mayChange(region))return false;
        if(!regions.contains(region) && regions.size()>=limit/512)return false;
        Map<Pos,Entry<S>> next=new HashMap<>(); List<Change<S>> changes=new ArrayList<>();
        int delta=0; Pos o=region.origin();
        for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++) {
            Pos p=new Pos(o.x+x,o.y+y,o.z+z); S current=world.get(p); Entry<S> old=entries.get(p);
            Desired<S> desired=sampler.sample(p);
            if(old!=null && (old.protectedEdit || !old.generated.equals(current))) {
                next.put(p,new Entry<>(old.original,old.generated,old.owner,true)); continue;
            }
            if(desired==null) {
                if(old!=null) { if(!current.equals(old.original))changes.add(new Change<>(p,current,old.original)); delta--; }
                continue;
            }
            if(old==null && !world.replaceable(p,current))continue; // Never excavate/overwrite base terrain.
            S base=old==null?current:old.original;
            next.put(p,new Entry<>(base,desired.state,desired.owner,false)); if(old==null)delta++;
            if(!current.equals(desired.state))changes.add(new Change<>(p,current,desired.state));
        }
        if(entries.size()+delta>limit)return false;
        int written=0;
        try {
            for(var change:changes) { if(!world.set(change.pos,change.after))throw new IllegalStateException("block write rejected"); written++; }
        } catch(RuntimeException failure) {
            for(int i=written-1;i>=0;i--)world.set(changes.get(i).pos,changes.get(i).before);
            throw failure;
        }
        for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++) {
            Pos p=new Pos(o.x+x,o.y+y,o.z+z); count(entries.remove(p),-1);
        }
        entries.putAll(next); next.values().forEach(e->count(e,1)); regions.add(region); return true;
    }
    public boolean forgetEmpty(Region region) {
        Pos o=region.origin();
        for(int y=0;y<8;y++)for(int z=0;z<8;z++)for(int x=0;x<8;x++)
            if(entries.containsKey(new Pos(o.x+x,o.y+y,o.z+z)))return false;
        return regions.remove(region);
    }
}
