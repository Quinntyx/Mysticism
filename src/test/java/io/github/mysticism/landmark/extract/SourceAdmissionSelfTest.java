package io.github.mysticism.landmark.extract;

import io.github.mysticism.landmark.*;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import java.util.*;
import java.util.stream.Stream;

/** Actual production edit/queue adapter and journal codec. No server/model/registry bootstrap. */
public final class SourceAdmissionSelfTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ExtractionJournal.Region region(String dimension,int index){return new ExtractionJournal.Region(dimension,8+index*32,-32,8);}
    public static void main(String[] args){
        SourceWorkQueue pending=new SourceWorkQueue(3);int[] invalidations={0};
        for(int i=0;i<1024;i++){
            var generated=region("mysticism:spirit",i);
            check(!pending.edited(generated,()->{invalidations[0]++;pending.add(generated);}),"actual generated-edit adapter rejects before invalidation/enqueue");
            check(!pending.add(generated),"direct enqueue/stitch admission rejects generated work");
        }
        check(pending.isEmpty() && invalidations[0]==0,"generated writes consume zero source queue budget or invalidations");
        List<ExtractionJournal.Region> real=List.of(region("minecraft:overworld",0),region("minecraft:the_nether",0),region("minecraft:the_end",0));
        for(var source:real)check(pending.edited(source,()->{invalidations[0]++;pending.add(source);}),"actual ordinary source-edit adapter admits work");
        check(pending.size()==3 && invalidations[0]==3,"overworld/nether/end enqueue actual source work");
        check(new ArrayList<>(pending).equals(real),"source edit FIFO preserved");
        check(pending.edited(real.getFirst(),()->pending.add(real.getFirst())) && pending.size()==3,"duplicate edit coalesces at capacity");
        check(!pending.add(region("minecraft:overworld",1)),"real queue remains bounded");
        check(!pending.edited(region("mysticism:spirit",0),()->{throw new AssertionError("generated callback ran at capacity");}),"generated work cannot invalidate an active source at capacity");
        pending.remove(real.getFirst());check(pending.add(region("example:source",1)),"ordinary modded source dimensions remain supported");

        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());ExtractionJournal journal=new ExtractionJournal();
        for(var source:real){var e=journal.entry(source);check(e!=null,"real journal admission");journal.reserve(e);journal.reserve(e);
            String id=LandmarkIds.seed(source.dimension(),ExtractionGraph.ALGORITHM,Landmark.Kind.CAVE,"minecraft:lush_caves",new BlockPoint(source.x(),source.y(),source.z()));
            journal.publish(e,List.of(id));journal.completed(e,"0".repeat(64));}
        NbtCompound untouched=journal.writeNbt(new NbtCompound(),lookup);NbtCompound legacy=untouched.copy();NbtList rows=legacy.getList("regions",NbtElement.COMPOUND_TYPE);
        for(int i=rows.size();i<ExtractionJournal.MAX_REGIONS;i++){
            NbtCompound generated=new NbtCompound();generated.putString("dimension","mysticism:spirit");generated.putInt("x",8+i*32);generated.putInt("y",-32);generated.putInt("z",8);generated.putLong("version",Long.MAX_VALUE);
            NbtList ids=new NbtList();ids.add(NbtString.of(LandmarkIds.seed("mysticism:spirit",ExtractionGraph.ALGORITHM,Landmark.Kind.CAVE,"minecraft:plains",new BlockPoint(8+i*32,-32,8))));generated.put("ids",ids);generated.put("seen",ids.copy());rows.add(generated);
        }
        ExtractionJournal restored=ExtractionJournal.fromNbt(legacy,lookup);
        check(restored.isDirty(),"legacy excluded scheduling rows marked for filtered save");
        check(restored.writeNbt(new NbtCompound(),lookup).equals(untouched),"real IDs/versions/fingerprints/history survive full contaminated journal exactly");
        check(restored.entries().size()==3,"stale spirit entries cannot occupy journal region/identity budgets");
        var excluded=region("mysticism:spirit",0);check(restored.entry(excluded)==null && restored.existing(excluded)==null,"direct/restored excluded journal admission impossible");
        SourceWorkQueue boot=new SourceWorkQueue(3);
        for(var e:restored.entries())boot.edited(e.region,()->boot.add(e.region));
        check(new ArrayList<>(boot).equals(real),"actual startup edit/queue adapter restores ONLY real source work");
        SourceWorkQueue stalePending=new SourceWorkQueue(3);
        for(int i=0;i<rows.size();i++){var row=rows.getCompound(i);stalePending.add(new ExtractionJournal.Region(row.getString("dimension"),row.getInt("x"),row.getInt("y"),row.getInt("z")));}
        check(new ArrayList<>(stalePending).equals(real),"old/raw persisted spirit scheduling cannot bypass production pending admission");
        check(restored.entry(region("minecraft:overworld",1))!=null,"excluded stale history leaves room for real discovery");
        System.out.println("SourceAdmissionSelfTest: "+checks+" checks passed");
    }
}
