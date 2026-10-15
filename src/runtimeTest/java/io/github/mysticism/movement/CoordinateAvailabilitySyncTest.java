package io.github.mysticism.movement;

import io.github.mysticism.client.spiritworld.ClientPoseSync;
import io.github.mysticism.component.*;
import io.github.mysticism.vector.*;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;

/** Actual CCA packet decoding + installed prediction filter: unavailable is NOT the valid
 * native origin, including when readiness/nav packets arrive after the ordered pose stream.
 * No game bootstrap or model inference. */
public final class CoordinateAvailabilitySyncTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Vec384f moved(double blocks){
        var q=Vec384f.ZERO();
        io.github.mysticism.activity.TraversalSteering.advance(q,new Basis384f(),blocks,0,0);
        return q;
    }
    private static NbtCompound tag(Vec384f coordinate){
        var tag=new NbtCompound();new LatentPos(coordinate).writeToNbt(tag,null);return tag;
    }
    private static void apply(LatentPos target,int sequence,NbtCompound tag){
        var buf=new RegistryByteBuf(Unpooled.buffer(),null);
        try {buf.writeVarInt(sequence);buf.writeNbt(tag);target.applySyncPacket(buf);}
        finally {buf.release();}
    }
    private static void checkAvailability(boolean filtered){
        var pos=new LatentPos(moved(2));var basis=new LatentBasis();var nav=new SpiritNavigation();
        if(filtered){ClientPoseSync.install();ClientPoseSync.preparePrediction(pos,basis,nav,true);}
        try {
            // Unknown -> valid native ZERO -> stale unknown -> authoritative reset.
            apply(pos,1,tag(null));
            check(!pos.hasCoordinate(),"wire reset must not manufacture an origin (filter="+filtered+")");
            ClientPoseSync.preparePrediction(pos,basis,nav,true); // even if old nav still claims ready
            apply(pos,2,tag(Vec384f.ZERO()));
            check(pos.hasCoordinate()&&pos.get().squareDistance(Vec384f.ZERO())==0,"explicit valid zero initializes exactly");
            apply(pos,1,tag(null));
            check(pos.hasCoordinate(),"reordered unknown cannot erase newer initialized authority");
            if(filtered)ClientPoseSync.preparePrediction(pos,basis,nav,true);
            var predicted=moved(4);pos.set(predicted);
            ClientPoseSync.recordPredictionPose(predicted,basis.get());
            apply(pos,3,tag(null));
            check(!pos.hasCoordinate()&&pos.get().squareDistance(Vec384f.ZERO())==0,"reset must defeat in-flight lag hold");
            apply(pos,2,tag(moved(1)));
            check(!pos.hasCoordinate(),"late valid pose cannot resurrect readiness after reset");
            check(pos.receivedSequence()==3,"ignored old payload cannot move authority frontier");
            apply(pos,4,tag(Vec384f.ZERO()));
            check(pos.hasCoordinate(),"new exact origin is usable after reset");
            var nbt=new NbtCompound();pos.writeToNbt(nbt,null);var restored=new LatentPos();restored.readFromNbt(nbt,null);
            check(restored.hasCoordinate(),"valid ZERO remains valid through save/reload too");
            var old=tag(moved(1));old.putString("embeddingFingerprint","obsolete-v1");
            apply(pos,5,old);
            check(!pos.hasCoordinate(),"rejected profile is unavailable, never remapped to usable ZERO");
            var malformed=tag(moved(1));malformed.putIntArray("v",new int[384]);
            apply(pos,6,malformed);
            check(!pos.hasCoordinate(),"truncated payload cannot initialize coordinates");
            var values=new float[EmbeddingSpace.DIMENSIONS];values[values.length-1]=.75f;
            var nativeVector=new Vec384f(values);
            apply(pos,7,tag(nativeVector));
            check(pos.hasCoordinate()&&pos.get().data()[767]==.75f,"fresh initialization keeps native v2 tail");
        } finally {ClientPoseSync.clear();LatentSync.clear();}
    }
    public static void main(String[] args){
        checkAvailability(false);checkAvailability(true);
        System.out.println("Coordinate availability sync: "+checks+" checks passed (real packet/filter paths, no game/model launch)");
    }
}
