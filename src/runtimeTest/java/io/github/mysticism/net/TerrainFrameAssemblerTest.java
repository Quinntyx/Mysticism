package io.github.mysticism.net;

import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.*;
import java.util.*;

/** Actual payload codec + bounded multipart reconstruction, with no game or inference bootstrap. */
public final class TerrainFrameAssemblerTest {
    private static int checks;
    private static final SpiritFramePayload.Session SESSION=new SpiritFramePayload.Session(UUID.randomUUID(),UUID.randomUUID(),"mysticism:spirit",1,1);
    private static final List<TerrainMeshFrame.Material> MATERIALS=List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of()));
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static TerrainMeshFrame frame(long rev,int n,int offset) {
        var cells=new ArrayList<TerrainMeshFrame.Cell>();
        for(int i=0;i<n;i++)cells.add(new TerrainMeshFrame.Cell(i,0,"owner",new Vec3d(i,0,0),new Vec3d(i+offset,0,0),
                new Vec3d(1,1,1),new Vec3d(1,0,0),new Vec3d(.2,1,0),new Vec3d(0,0,1),0xffffff,0,1,List.of(new Box(0,0,0,1,1,1))));
        return new TerrainMeshFrame(rev,true,"minecraft:overworld",Vec3d.ZERO,new Vec3d(offset,0,0),MATERIALS,cells);
    }
    private static SpiritTerrainPayload wire(SpiritTerrainPayload packet) {
        var buffer=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
        try{SpiritTerrainPayload.CODEC.encode(buffer,packet);var decoded=SpiritTerrainPayload.CODEC.decode(buffer);check(buffer.readableBytes()==0,"wire fully consumed");check(decoded.equals(packet),"wire roundtrip includes base revision and affine collision");return decoded;}
        finally{buffer.release();}
    }
    private static void deliver(TerrainFrameAssembler receiver,List<SpiritTerrainPayload> packets) {
        for(int i=0;i<packets.size();i++)check(receiver.apply(wire(packets.get(i)))==(i==packets.size()-1?TerrainFrameAssembler.Outcome.COMMITTED:TerrainFrameAssembler.Outcome.IGNORED),"only complete frame commits");
    }
    private static SpiritTerrainPayload replace(SpiritTerrainPayload p,long base,TerrainMeshFrame f) {
        return new SpiritTerrainPayload(p.session(),p.sequence(),p.part(),p.parts(),p.full(),base,p.removed(),f);
    }
    private static void illegal(Runnable operation,String message) {
        try{operation.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) {
        var receiver=new TerrainFrameAssembler(null);var initial=frame(1,600,0);
        var full=SpiritTerrainPayload.batches(SESSION,1,initial,null);
        check(full.size()==3 && full.stream().allMatch(p->p.full() && p.baseRevision()==SpiritTerrainPayload.NO_BASE),"bounded full fragments have no base");
        check(receiver.apply(wire(full.get(0)))==TerrainFrameAssembler.Outcome.IGNORED && receiver.committed()==null,"partial first display remains absent");
        check(receiver.apply(wire(full.get(1)))==TerrainFrameAssembler.Outcome.IGNORED && receiver.committed()==null,"partial frame cannot expose collision");
        check(receiver.apply(wire(full.get(2)))==TerrainFrameAssembler.Outcome.COMMITTED && receiver.committed().equals(initial),"full revision reconstructs exactly");
        var shifted=frame(2,600,7);var delta=SpiritTerrainPayload.batches(SESSION,2,shifted,initial);
        check(delta.stream().allMatch(p->!p.full() && p.baseRevision()==1),"delta declares exact base");
        check(receiver.apply(delta.get(0))==TerrainFrameAssembler.Outcome.IGNORED && receiver.committed().equals(initial),"moving surfaces stay at prior frame while assembling");
        check(receiver.apply(full.get(0))==TerrainFrameAssembler.Outcome.IGNORED && receiver.incomplete(),"stale fragments cannot poison new assembly");
        check(receiver.apply(delta.get(2))==TerrainFrameAssembler.Outcome.DESYNCED && receiver.needsResync(),"missing middle requests full resync");
        check(receiver.committed().equals(initial),"broken stream retains coherent prior frame");
        var recover=SpiritTerrainPayload.batches(SESSION,3,shifted,null);deliver(receiver,recover);
        check(receiver.committed().equals(shifted) && !receiver.needsResync(),"full recovery of idle server frame");
        deliver(receiver,SpiritTerrainPayload.batches(SESSION,4,frame(3,12,7),shifted));
        check(receiver.committed().cells().size()==12,"removal-only delta applied atomically");
        deliver(receiver,SpiritTerrainPayload.batches(SESSION,5,frame(4,0,7),receiver.committed()));
        check(receiver.committed().cells().isEmpty(),"last removed surface leaves no invisible floor");
        var noGeometryChange=frame(5,0,7);deliver(receiver,SpiritTerrainPayload.batches(SESSION,6,noGeometryChange,receiver.committed()));
        check(receiver.committed().revision()==5,"header-only revision updates still commit");
        // Delta's source and projection base must match, even if the palette is unchanged.
        var mismatch=SpiritTerrainPayload.batches(SESSION,7,frame(8,1,0),frame(7,1,0)).getFirst();
        check(receiver.apply(mismatch)==TerrainFrameAssembler.Outcome.DESYNCED,"wrong delta base never composites");
        check(receiver.committed().equals(noGeometryChange),"wrong base preserves last revision");
        deliver(receiver,SpiritTerrainPayload.batches(SESSION,8,noGeometryChange,null));
        check(!receiver.needsResync(),"same-revision full resend clears desync");
        var partial=SpiritTerrainPayload.batches(SESSION,9,frame(6,600,0),receiver.committed());
        receiver.apply(partial.getFirst());receiver.expirePartial();
        check(receiver.needsResync() && !receiver.incomplete(),"incomplete idle stream expires and retries recovery");
        deliver(receiver,SpiritTerrainPayload.batches(SESSION,10,frame(6,600,0),null));
        var next=SpiritTerrainPayload.batches(SESSION,11,frame(7,600,1),receiver.committed());
        receiver.apply(next.getFirst());
        var badBase=replace(next.get(1),5,next.get(1).frame());
        check(receiver.apply(badBase)==TerrainFrameAssembler.Outcome.DESYNCED,"all fragment bases must agree");
        deliver(receiver,SpiritTerrainPayload.batches(SESSION,12,frame(7,600,1),null));
        // Duplicate keys across parts cannot silently overwrite already-staged affine geometry.
        var dup=SpiritTerrainPayload.batches(SESSION,13,frame(8,600,2),receiver.committed());receiver.apply(dup.getFirst());
        var f=dup.get(1).frame();var bad=new TerrainMeshFrame(f.revision(),f.shallow(),f.sourceDimension(),f.sourceOrigin(),f.carrierOrigin(),f.materials(),List.of(dup.get(0).frame().cells().getFirst()));
        check(receiver.apply(replace(dup.get(1),dup.get(1).baseRevision(),bad))==TerrainFrameAssembler.Outcome.DESYNCED,"duplicate multipart keys rejected");
        var other=new SpiritFramePayload.Session(SESSION.connection(),SESSION.player(),SESSION.dimension(),2,2);
        check(receiver.apply(SpiritTerrainPayload.batches(other,1,initial,null).getFirst())==TerrainFrameAssembler.Outcome.IGNORED,"prior assembler lifetime cannot accept a different entry");
        receiver.reset();deliver(receiver,SpiritTerrainPayload.batches(other,1,initial,null));
        check(receiver.committed().equals(initial),"reset permits fresh session/revision sequence");
        check(receiver.apply(full.getFirst())==TerrainFrameAssembler.Outcome.IGNORED,"late old-session terrain cannot replace fresh scene");
        var paletteChange=new TerrainMeshFrame(2,false,"minecraft:the_nether",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:dirt",Map.of())),List.of());
        var changedPalette=SpiritTerrainPayload.batches(other,2,paletteChange,initial);
        check(changedPalette.getFirst().full() && changedPalette.getFirst().baseRevision()==-1,"palette/source changes force baseless full update");
        deliver(receiver,changedPalette);check(receiver.committed().equals(paletteChange),"shallow/source/palette revision coherent");
        var maximum=frame(3,TerrainMeshFrame.MAX_CELLS,0);var maxParts=SpiritTerrainPayload.batches(other,3,maximum,null);
        check(maxParts.size()==8,"maximum frame uses bounded eight-fragment envelope");deliver(receiver,maxParts);
        var one=frame(4,1,0).cells().getFirst();
        var extra=new TerrainMeshFrame.Cell(3000,one.material(),one.landmarkId(),one.sourceMin(),one.min(),one.size(),one.axisX(),one.axisY(),one.axisZ(),one.color(),one.light(),one.opacity(),one.collision());
        var overflow=new TerrainMeshFrame(4,maximum.shallow(),maximum.sourceDimension(),maximum.sourceOrigin(),maximum.carrierOrigin(),maximum.materials(),List.of(extra));
        check(receiver.apply(new SpiritTerrainPayload(other,4,0,1,false,3,List.of(),overflow))==TerrainFrameAssembler.Outcome.DESYNCED,"aggregate delta cannot exceed whole-frame cell budget");
        check(receiver.committed().equals(maximum),"oversized aggregate retains full previous mesh");
        var missingFirst=SpiritTerrainPayload.batches(other,5,frame(5,600,2),null);
        check(receiver.apply(missingFirst.get(1))==TerrainFrameAssembler.Outcome.DESYNCED,"missing first part requests recovery, not silent drop");
        deliver(receiver,SpiritTerrainPayload.batches(other,6,maximum,null));
        check(receiver.apply(SpiritTerrainPayload.batches(other,7,paletteChange,null).getFirst())==TerrainFrameAssembler.Outcome.DESYNCED,"full frame cannot regress an entry's revision");
        var sync=new SpiritTerrainSyncPayload(other.connection(),other.generation());
        var syncBuffer=new RegistryByteBuf(Unpooled.buffer(),DynamicRegistryManager.EMPTY);
        try{SpiritTerrainSyncPayload.CODEC.encode(syncBuffer,sync);check(SpiritTerrainSyncPayload.CODEC.decode(syncBuffer).equals(sync) && syncBuffer.readableBytes()==0,"resync request codec preserves authenticated lifetime");}
        finally{syncBuffer.release();}
        illegal(()->new SpiritTerrainSyncPayload(other.connection(),0),"invalid resync epoch");
        illegal(()->new SpiritTerrainPayload(SESSION,1,0,1,true,0,List.of(),frame(1,0,0)),"full cannot declare delta base");
        illegal(()->new SpiritTerrainPayload(SESSION,1,0,1,false,1,List.of(),frame(1,0,0)),"delta revision must advance");
        illegal(()->new SpiritTerrainPayload(SESSION,1,0,1,true,-1,List.of(1L),frame(1,0,0)),"full cannot carry removals");
        illegal(()->SpiritTerrainPayload.batches(SESSION,1,initial,initial),"server cannot form regressive delta");
        System.out.println("Terrain frame assembly: "+checks+" checks passed (no game/inference)");
    }
}
