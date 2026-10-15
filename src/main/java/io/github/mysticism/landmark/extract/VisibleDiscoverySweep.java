package io.github.mysticism.landmark.extract;

import java.util.LinkedHashMap;

/** Resumable nearest-ring-first IO sampling across the configured visible source radius.
 * Chunk coordinates are IO addresses ONLY, never feature bounds or ownership identities.
 * A later sweep revisits dropped hints/missing chunks; it never loads or generates a chunk. */
public final class VisibleDiscoverySweep {
    public record Sample(String dimension,int chunkX,int chunkZ) {}
    private record Center(String dimension,int x,int z) {}
    private static final class Cursor {int index,radius=-1;}
    private final LinkedHashMap<Center,Cursor> cursors=new LinkedHashMap<>(16,.75f,true);
    public Sample next(DiscoveryPrioritizer.Anchor anchor,int radius) {
        if(radius<0 || radius>32)throw new IllegalArgumentException("source visibility radius");
        Center center=new Center(anchor.dimension(),(int)(anchor.pos().x()>>4),(int)(anchor.pos().z()>>4));
        Cursor cursor=cursors.computeIfAbsent(center,key->new Cursor());
        if(cursors.size()>256)cursors.remove(cursors.keySet().iterator().next());
        if(cursor.radius!=radius){cursor.index=0;cursor.radius=radius;}
        int index=cursor.index++;
        if(cursor.index>=(2*radius+1)*(2*radius+1))cursor.index=0;
        if(index==0)return new Sample(center.dimension(),center.x(),center.z());
        int ring=(int)Math.ceil((Math.sqrt(index+1)-1)/2);
        int edge=2*ring,offset=index-(2*ring-1)*(2*ring-1);
        int side=offset/edge,along=offset%edge;
        int dx=switch(side){case 0->-ring+along;case 1->ring;case 2->ring-along;default->-ring;};
        int dz=switch(side){case 0->-ring;case 1->-ring+along;case 2->ring;default->ring-along;};
        return new Sample(center.dimension(),center.x()+dx,center.z()+dz);
    }
    public void removeDimension(String dimension) {cursors.keySet().removeIf(key->key.dimension().equals(dimension));}
    public void clear() {cursors.clear();}
}
