package io.github.mysticism.activity;

import io.github.mysticism.embedding.CanonicalDescriptors;
import net.minecraft.SharedConstants;
import net.minecraft.Bootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.item.*;
import net.minecraft.stat.*;

/** Actual Yarn registries, Stats values and production descriptor adapters, not replacement stubs. */
public final class ActivityAdapterTest {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        check(ActivityMath.delta(10000,10001)==1,"lifetime counter delta only");
        check(ActivityMath.delta(10000,10000)==0,"no replay");
        check(ActivityMath.delta(10000,0)==0,"reset ignored");
        check(ActivityMath.delta(Integer.MAX_VALUE,Integer.MAX_VALUE)==0,"vanilla saturation ignored");
        if(!Boolean.getBoolean("mysticism.activity.registryChecks")){
            System.out.println("ActivityAdapterTest: "+checks+" delta checks passed; live registry/Stats adapters NOT RUN (requires Fabric access-widened runtime; enable mysticism.activity.registryChecks)");return;
        }
        SharedConstants.createGameVersion();Bootstrap.initialize();
        StatHandler values=new StatHandler();var mined=Stats.MINED.getOrCreateStat(Blocks.STONE);
        int before=values.getStat(mined);values.increaseStat(null,mined,3);int after=values.getStat(mined);
        check(after-before==3,"actual tracked mined delta");
        ActivityMath.Window window=new ActivityMath.Window();window.add(SpiritActivityService.statDescription(mined),after-before);
        check(window.take().values().iterator().next()==3,"production mined descriptor weighted by delta");
        before=values.getStat(mined);values.increaseStat(null,mined,0);after=values.getStat(mined);
        window.add(SpiritActivityService.statDescription(mined),after-before);check(window.take().isEmpty(),"zero delta does not replay total");
        values.setStat(null,mined,10000);before=values.getStat(mined);values.increaseStat(null,mined,1);after=values.getStat(mined);
        window.add(SpiritActivityService.statDescription(mined),after-before);check(window.take().values().iterator().next()==1,"large old Stats total not repeated");
        values.setStat(null,mined,0);window.add(SpiritActivityService.statDescription(mined),-10001);check(window.take().isEmpty(),"reset never negative influence");
        check(SpiritActivityService.statDescription(Stats.USED.getOrCreateStat(Items.DIAMOND_SWORD)).equals(CanonicalDescriptors.item("minecraft:diamond_sword",java.util.List.of())),"actual most-used item");
        check(SpiritActivityService.statDescription(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS)).contains("deaths"),"deaths tracked");
        check(SpiritActivityService.statDescription(Stats.CUSTOM.getOrCreateStat(Stats.MOB_KILLS)).contains("mob kills"),"mobs killed tracked");
        check(SpiritActivityService.statDescription(Stats.CUSTOM.getOrCreateStat(Stats.PLAYER_KILLS)).contains("player kills"),"players killed tracked");
        check(SpiritActivityService.statDescription(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME))==null,"tick cumulative playtime ignored");
        check(SpiritActivityService.block(Blocks.OAK_PLANKS.getDefaultState()).contains("minecraft:oak_planks"),"real placed material descriptor");
        check(!SpiritActivityService.block(Blocks.OAK_PLANKS.getDefaultState()).equals(SpiritActivityService.block(Blocks.STONE.getDefaultState())),"source material semantic distinction");
        check(SpiritActivityService.item(new ItemStack(Items.DIAMOND)).contains("minecraft:diamond"),"held/inventory real stack");
        check(SpiritActivityService.item(new ItemStack(Items.DIAMOND,64)).equals(SpiritActivityService.item(new ItemStack(Items.DIAMOND,1))),"stack count does not amplify dwell");
        System.out.println("ActivityAdapterTest: "+checks+" checks passed");
    }
}
