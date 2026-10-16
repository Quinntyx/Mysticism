package io.github.mysticism.net;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import java.util.*;

/** Single-reader bounded terrain reassembly. A delta applies only to its exact received base,
 * never to partial geometry. Display/prediction have a separate GPU-ready revision gate. */
public final class TerrainFrameAssembler {
    public enum Outcome { COMMITTED, IGNORED, DESYNCED }
    private TerrainMeshFrame committed;
    private SpiritFramePayload.Session session;
    private long sequence;
    private SpiritTerrainPayload pending;
    private int nextPart;
    private final Map<Long,TerrainMeshFrame.Cell> assembled=new TreeMap<>();
    private final Set<Long> changed=new HashSet<>();
    private boolean resync;
    public TerrainFrameAssembler(TerrainMeshFrame initial){committed=initial;}
    public TerrainMeshFrame committed(){return committed;}
    /** Remains true until a complete full frame arrives; callers may retry a rate-limited request. */
    public boolean needsResync(){return resync;}
    public boolean incomplete(){return pending!=null;}
    public void reset(){committed=null;session=null;sequence=0;discardPartial();resync=false;}
    /** Missing final fragments must not leave an idle stream permanently stale. */
    public void expirePartial(){if(pending!=null)desync();}
    public Outcome apply(SpiritTerrainPayload packet) {
        Objects.requireNonNull(packet);
        // The active scene guard is owned by the caller; never mix sessions even if it is bypassed.
        if(session!=null && !session.equals(packet.session()))return Outcome.IGNORED;
        if(session==null)session=packet.session();
        if(packet.sequence()<=sequence || pending!=null && packet.sequence()<pending.sequence())return Outcome.IGNORED;
        if(resync && !packet.full())return Outcome.DESYNCED;
        if(pending==null || packet.sequence()!=pending.sequence()) {
            if(packet.part()!=0){desync();return Outcome.DESYNCED;}
            discardPartial();pending=packet;
            if(!packet.full()) {
                var base=committed;
                if(base==null || packet.baseRevision()!=base.revision()
                        || !base.materials().equals(packet.frame().materials())
                        || !base.sourceDimension().equals(packet.frame().sourceDimension())){desync();return Outcome.DESYNCED;}
                base.cells().forEach(c->assembled.put(c.key(),c));
            }
            packet.removed().forEach(assembled::remove);
        }
        var f=packet.frame();var base=pending.frame();
        if(packet.part()!=nextPart || packet.parts()!=pending.parts() || packet.full()!=pending.full()
                || packet.baseRevision()!=pending.baseRevision()
                || f.revision()!=base.revision() || f.shallow()!=base.shallow()
                || !f.materials().equals(base.materials()) || !f.sourceDimension().equals(base.sourceDimension())
                || !f.sourceOrigin().equals(base.sourceOrigin()) || !f.carrierOrigin().equals(base.carrierOrigin())){desync();return Outcome.DESYNCED;}
        for(var cell:f.cells()) {
            if(!changed.add(cell.key()) || pending.removed().contains(cell.key())){desync();return Outcome.DESYNCED;}
            assembled.put(cell.key(),cell);
        }
        if(assembled.size()>TerrainMeshFrame.MAX_CELLS){desync();return Outcome.DESYNCED;}
        nextPart++;
        if(nextPart==packet.parts()) {
            var complete=new TerrainMeshFrame(f.revision(),f.shallow(),f.sourceDimension(),f.sourceOrigin(),f.carrierOrigin(),f.materials(),List.copyOf(assembled.values()));
            // Full recovery may resend the same revision, but may not redefine or regress it.
            if(committed!=null && (complete.revision()<committed.revision()
                    || complete.revision()==committed.revision() && !complete.equals(committed))){desync();return Outcome.DESYNCED;}
            committed=complete;sequence=packet.sequence();discardPartial();resync=false;
            return Outcome.COMMITTED;
        }
        return Outcome.IGNORED;
    }
    private void discardPartial(){pending=null;nextPart=0;assembled.clear();changed.clear();}
    private void desync(){discardPartial();resync=true;}
}
