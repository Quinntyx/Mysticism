package io.github.mysticism.landmark;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import java.io.DataInputStream;
import java.util.*;
import java.util.function.LongPredicate;

/** Regression checks for backfill discovery of already-loaded source terrain that predates
 * spirit entry. Pure plan contracts plus compiled wiring contracts: no fake server, no boot
 * claims and no fabricated geometry. */
public final class LoadedSourceBackfillTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void fails(Runnable action,String why){
        checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(why);
    }
    private static Set<Long> loaded(long... packed){var set=new HashSet<Long>();for(long p:packed)set.add(p);return set;}
    private static LongPredicate in(Set<Long> set){return set::contains;}

    private static void centerSeedIncludedFirst() {
        var set=loaded(ChunkPos.toLong(4,7),ChunkPos.toLong(5,7),ChunkPos.toLong(6,7));
        var plan=LoadedSourceBackfill.plan(new ChunkPos(5,7),1,8,in(set));
        check(!plan.isEmpty()&&plan.getFirst().equals(new ChunkPos(5,7)),"entry chunk itself is a backfill seed");
        check(plan.size()==3,"every loaded neighbor within radius is planned");
        // Unloaded chunks never appear, not even adjacent ones.
        check(plan.stream().allMatch(p->set.contains(p.toLong())),"plan stays loaded-only");
    }

    private static void closestFirstAndTies() {
        var center=new ChunkPos(0,0);
        var set=loaded(ChunkPos.toLong(0,0),ChunkPos.toLong(2,0),ChunkPos.toLong(0,2),ChunkPos.toLong(1,0),ChunkPos.toLong(0,1),ChunkPos.toLong(1,1));
        var plan=LoadedSourceBackfill.plan(center,2,6,in(set));
        long previous=-1;
        for(var pos:plan){
            long dx=pos.x-center.x,dz=pos.z-center.z,distance=dx*dx+dz*dz;
            check(previous<=distance,"closest-first seed order, got "+distance+" after "+previous);
            previous=distance;
        }
        check(plan.getFirst().equals(center),"distance zero entry chunk first");
        check(plan.get(1).equals(new ChunkPos(0,1))&&plan.get(2).equals(new ChunkPos(1,0)),"equal distance ties break by chunk X then Z");
    }

    private static void capAndCompleteness() {
        var center=new ChunkPos(10,-3);
        var set=new HashSet<Long>();
        for(int dz=-LoadedSourceBackfill.RADIUS;dz<=LoadedSourceBackfill.RADIUS;dz++)
            for(int dx=-LoadedSourceBackfill.RADIUS;dx<=LoadedSourceBackfill.RADIUS;dx++)set.add(ChunkPos.toLong(center.x+dx,center.z+dz));
        var full=LoadedSourceBackfill.plan(center,LoadedSourceBackfill.RADIUS,LoadedSourceBackfill.MAX_SEEDS,in(set));
        check(full.size()==LoadedSourceBackfill.MAX_SEEDS,"production budget plans the whole ("+(2*LoadedSourceBackfill.RADIUS+1)+"^2) loaded neighborhood");
        check(new LinkedHashSet<>(full).size()==full.size(),"plan never duplicates a chunk seed");
        for(var pos:full)check(Math.max(Math.abs(pos.x-center.x),Math.abs(pos.z-center.z))<=LoadedSourceBackfill.RADIUS,"every seed within plan radius");
        check(LoadedSourceBackfill.plan(center,LoadedSourceBackfill.RADIUS,10,in(set)).size()==10,"seed cap is respected");
        check(LoadedSourceBackfill.plan(center,LoadedSourceBackfill.RADIUS,0,in(set)).isEmpty(),"zero cap plans nothing");
        check(LoadedSourceBackfill.plan(center,0,9,in(set)).size()==1,"radius zero plans only the entry chunk");
        // Determinism: identical inputs produce identical plans.
        check(full.equals(LoadedSourceBackfill.plan(center,LoadedSourceBackfill.RADIUS,LoadedSourceBackfill.MAX_SEEDS,in(set))),"plan is deterministic for identical loaded state");
        // Long packing round trip used by the discoverLoaded holder lookup.
        for(var pos:full)check(ChunkPos.getPackedX(pos.toLong())==pos.x&&ChunkPos.getPackedZ(pos.toLong())==pos.z,"packed holder lookup round trips "+pos);
    }

    private static void rejectedArguments() {
        var set=loaded(ChunkPos.toLong(0,0));
        fails(()->LoadedSourceBackfill.plan(new ChunkPos(0,0),-1,4,in(set)),"negative radius rejected");
        fails(()->LoadedSourceBackfill.plan(new ChunkPos(0,0),1,-1,in(set)),"negative seed cap rejected");
        fails(()->LoadedSourceBackfill.plan(null,1,4,in(set)),"null center rejected");
        fails(()->LoadedSourceBackfill.plan(new ChunkPos(0,0),1,4,null),"null loaded predicate rejected");
    }

    private static void compiledWiring() {
        // The production entry point exists with the real server-thread signature.
        try {
            var method=SourceLandmarks.class.getMethod("discoverLoaded",MinecraftServer.class,String.class,BlockPos.class,int.class);
            check(method.getReturnType()==int.class,"discoverLoaded reports hinted seed count");
        } catch(NoSuchMethodException e){throw new AssertionError("SourceLandmarks.discoverLoaded missing",e);}
        try {
            var method=SpiritTerrainService.class.getDeclaredMethod("backfillLoadedSource",ServerPlayerEntity.class,String.class,Vec3d.class);
            method.setAccessible(true);
            check(method.getReturnType()==void.class,"terrain entry backfill helper wired");
        } catch(NoSuchMethodException e){throw new AssertionError("SpiritTerrainService entry backfill helper missing",e);}
        // Entry/restore actually invoke backfill discovery: the call site leaves a constant-pool trace.
        check(references(SpiritTerrainService.class,"discoverLoaded"),"spirit entry invokes source backfill discovery");
        check(references(SpiritTerrainService.class,"backfillLoadedSource"),"spirit entry uses the defensive helper");
        check(references(SourceLandmarks.class,"LoadedSourceBackfill"),"source discovery uses the bounded backfill plan");
    }

    private static boolean references(Class<?> owner,String constant) {
        try(var in=new DataInputStream(owner.getResourceAsStream(owner.getSimpleName()+".class"))){
            var bytes=in.readAllBytes();var pattern=constant.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for(int i=0;i+pattern.length<=bytes.length;i++){
                int j=0;while(j<pattern.length&&bytes[i+j]==pattern[j])j++;
                if(j==pattern.length)return true;
            }
            return false;
        } catch(Exception e){throw new AssertionError("unreadable compiled class "+owner.getSimpleName(),e);}
    }

    public static void main(String[] args) {
        centerSeedIncludedFirst();
        closestFirstAndTies();
        capAndCompleteness();
        rejectedArguments();
        compiledWiring();
        System.out.println("LoadedSourceBackfillTest: "+checks+" checks passed");
    }
}
