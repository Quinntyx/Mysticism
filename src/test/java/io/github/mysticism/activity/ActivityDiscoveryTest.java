package io.github.mysticism.activity;

import io.github.mysticism.landmark.*;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import net.minecraft.util.math.BlockPos;
import java.io.*;
import java.util.*;

/** Exercises the exact incremental probe wired to END_SERVER_TICK against the real saved store. */
public final class ActivityDiscoveryTest {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private static Optional<LandmarkMetadata> finish(SpiritActivityService.NearbyDiscovery discovery,LandmarkStore store){
        Optional<LandmarkMetadata> found=Optional.empty();int steps=0;
        while(!discovery.done){found=discovery.advance(store);check(discovery.lastScanned<=4,"production tick probe reads <=4 metadata records");check(++steps<200,"bounded full-cycle completion");}
        return found;
    }
    // Verify the compiled public production adapter actually invokes LandmarkMerge.importance,
    // rather than a test-only duplicate formula. No fake MinecraftServer or boot claims.
    private static void delegatedImportance()throws Exception{
        try(var in=new DataInputStream(SpiritActivityService.class.getResourceAsStream("SpiritActivityService.class"))){
            check(in.readInt()==0xcafebabe,"compiled service class");in.readUnsignedShort();in.readUnsignedShort();
            Object[] cp=new Object[in.readUnsignedShort()];
            for(int i=1;i<cp.length;i++){int tag=in.readUnsignedByte();switch(tag){
                case 1->cp[i]=in.readUTF();case 3,4->in.readInt();case 5,6->{in.readLong();i++;}
                case 7,8,16,19,20->cp[i]=in.readUnsignedShort();
                case 9,10,11,12,17,18->cp[i]=new int[]{in.readUnsignedShort(),in.readUnsignedShort()};
                case 15->{in.readUnsignedByte();in.readUnsignedShort();}default->throw new AssertionError("constant tag "+tag);
            }}
            check(Arrays.stream(cp).noneMatch("tryVerifiedLocalMerge"::equals),"obsolete max-base convenience path removed from compiled service");
            in.readUnsignedShort();in.readUnsignedShort();in.readUnsignedShort();int interfaces=in.readUnsignedShort();in.skipNBytes(interfaces*2L);
            for(int pass=0;pass<2;pass++){int count=in.readUnsignedShort();for(int n=0;n<count;n++){
                in.readUnsignedShort();String name=(String)cp[in.readUnsignedShort()];in.readUnsignedShort();int attributes=in.readUnsignedShort();
                for(int a=0;a<attributes;a++){String attribute=(String)cp[in.readUnsignedShort()];int length=in.readInt();byte[] body=in.readNBytes(length);
                    if(pass==1&&name.equals("importance")&&attribute.equals("Code")){
                        var code=new DataInputStream(new ByteArrayInputStream(body));code.readUnsignedShort();code.readUnsignedShort();int size=code.readInt();byte[] bytes=code.readNBytes(size);
                        check(size==6&&bytes[0]==0x2a&&bytes[1]==0x2b&&(bytes[2]&255)==0xb8&&(bytes[5]&255)==0xaf,"public importance is a direct delegate");
                        int[] ref=(int[])cp[((bytes[3]&255)<<8)|(bytes[4]&255)];int[] named=(int[])cp[ref[1]];
                        check(cp[(Integer)cp[ref[0]]].equals("io/github/mysticism/activity/LandmarkMerge")&&cp[named[0]].equals("importance"),"delegate is conserved additive LandmarkMerge importance");return;
                    }
                }
            }}throw new AssertionError("missing importance bytecode");
        }
    }
    public static void main(String[] args)throws Exception{
        var fixture=LandmarkSourceRangePageTest.fixture(LandmarkProfiles.current());var store=fixture.store();Set<String> seen=new HashSet<>();String cursor=null;
        for(int pulse=0;pulse<24;pulse++){
            var discovery=new SpiritActivityService.NearbyDiscovery("minecraft:overworld",BlockPos.ORIGIN,cursor);
            var m=finish(discovery,store).orElseThrow();check(fixture.localIds().contains(m.id()),"strict local radius/dimension excludes foreign/corner/boundary results");
            if(pulse<12)check(seen.add(m.id()),"rotating pulses reach each of >8 local overlaps before repeating");cursor=m.id();
        }
        check(seen.equals(fixture.localIds()),"world >128 does not disable any stable nearby overlap");
        var none=new SpiritActivityService.NearbyDiscovery("minecraft:the_end",BlockPos.ORIGIN,cursor);check(finish(none,store).isEmpty(),"full wrapped scan with no local landmarks terminates");
        var stale=new SpiritActivityService.NearbyDiscovery("minecraft:overworld",BlockPos.ORIGIN,"lm-"+"f".repeat(64));check(finish(stale,store).isPresent(),"seek beyond last record wraps without dropping pulse");
        var retired=store.metadata(cursor).orElseThrow();var deletion=store.stageDelete(new LandmarkRepository.RevisionRef(cursor,retired.revision()));while(!deletion.complete())deletion.advance(8);
        var afterDeletion=new SpiritActivityService.NearbyDiscovery("minecraft:overworld",BlockPos.ORIGIN,cursor);
        check(!finish(afterDeletion,store).orElseThrow().id().equals(cursor),"deleted cached landmark ID does not disable local rotation");
        var first=store.metadata(fixture.localIds().stream().filter(id->!id.equals(retired.id())).findFirst().orElseThrow()).orElseThrow();var second=store.metadata(fixture.localIds().stream().filter(id->!id.equals(first.id())&&!id.equals(retired.id())).findFirst().orElseThrow()).orElseThrow();
        var combined=LandmarkMerge.combine(List.of(first,second),Arrays.asList(null,null),0);
        check(Math.abs(combined.importance(.1,0)-.2)<1e-10,"additive base matters below rendering cap (not old .1 maximum)");
        var state=new LandmarkActivityState();state.publish(first.id(),combined);var loaded=LandmarkActivityState.read(state.writeNbt(new net.minecraft.nbt.NbtCompound(),null),null);
        check(Math.abs(loaded.entries.get(first.id()).importance(.1,0)-.2)<1e-10,"additive delegated score survives state codec");
        delegatedImportance();System.out.println("ActivityDiscoveryTest: "+checks+" checks passed; real compressed source fixture retained at "+fixture.path());
    }
}
