package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.*;
import java.util.function.Predicate;

/** Per-entry bounded read-only cursor over existing owned floors, independent of stamping. */
final class TerrainLandingSearch {
    static final int PROBES_PER_PLAYER=64;
    record Landing(OverlayLedger.Pos floor,String owner) {}
    private Landing cached;
    private int regionIndex,cellIndex;
    private int lastProbes;
    int lastProbes() { return lastProbes; }
    private static <S> boolean owned(OverlayLedger<S> ledger,Landing candidate,Set<String> selected) {
        var entry=ledger.entry(candidate.floor());
        return selected.contains(candidate.owner()) && entry!=null && !entry.protectedEdit()
                && entry.owner().equals(candidate.owner());
    }
    <S> Optional<Landing> find(OverlayLedger<S> ledger,Set<String> selected,Predicate<Landing> safe) {
        lastProbes=0;
        if(selected.isEmpty()) { cached=null; return Optional.empty(); }
        if(cached!=null) {
            if(owned(ledger,cached,selected) && safe.test(cached))return Optional.of(cached);
            cached=null;
        }
        // At most 128 indexed owned regions; irrelevant/protected owners cannot starve discovery.
        // Never a scan/copy of the 65536-entry ledger.
        var regions=new ArrayList<>(ledger.ownedRegions(selected));
        if(regions.isEmpty())return Optional.empty();
        for(int i=0;i<PROBES_PER_PLAYER;i++) {
            if(regionIndex>=regions.size())regionIndex=0;
            var origin=regions.get(regionIndex).origin(); int cell=cellIndex++;
            if(cellIndex==512) { cellIndex=0; regionIndex++; }
            var pos=new OverlayLedger.Pos(origin.x()+(cell&7),origin.y()+(cell>>>6),origin.z()+((cell>>>3)&7));
            lastProbes++;
            var entry=ledger.entry(pos);
            if(entry==null || entry.protectedEdit() || !selected.contains(entry.owner()))continue;
            var candidate=new Landing(pos,entry.owner());
            if(safe.test(candidate)) { cached=candidate; return Optional.of(candidate); }
        }
        return Optional.empty();
    }
}
