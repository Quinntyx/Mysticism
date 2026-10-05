package io.github.mysticism.landmark;

import net.minecraft.SharedConstants;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.PersistentStateManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Real Yarn NBT and PersistentStateManager; only existing Minecraft runtime dependencies. */
public final class LandmarkPersistenceSelfTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void fails(Class<? extends Throwable> type,Runnable call,String message) {
        checks++; try { call.run(); } catch(Throwable e) { if(type.isInstance(e)) return; throw new AssertionError(message,e); }
        throw new AssertionError(message+" did not fail");
    }
    private static Landmark withGeometry(long x) {
        BlockPoint anchor=new BlockPoint(x,0,0); Bounds bounds=Bounds.cube(x,0,0,4);
        BlockPalette palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        SparseOctree<BlockSample> cells=SparseOctree.<BlockSample>empty(bounds,1,4)
                .with(Bounds.cube(x,0,0,1),new BlockSample(BlockSample.Occupancy.SOLID,1),32)
                .with(Bounds.cube(x+1,0,0,1),new BlockSample(BlockSample.Occupancy.AIR,0),32);
        GeometryPage page=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",anchor,4),0,bounds,palette,cells);
        return LandmarkCoreSelfTest.landmark(anchor,bounds,LandmarkCoreSelfTest.embedding(0.25,0,0,0),0.7,
                new SourceGeometry(List.of(page),List.of(new FrontierFace("minecraft:overworld",Bounds.cube(x+4,0,0,1),FrontierFace.Direction.EAST,0,"resume:"+x))),0,
                new Ownership(List.of(new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000001"),1))));
    }
    private static void roundtripAndMalformed() {
        Landmark original=withGeometry(-4); GeometryPage page=original.geometry().pages().getFirst();
        NbtCompound geometry=LandmarkNbt.encodeGeometry(page);
        GeometryPage decoded=LandmarkNbt.decodeGeometry(geometry);
        check(decoded.id().equals(page.id()) && decoded.revision()==0 && decoded.knownCells().equals(page.knownCells()),"sparse geometry NBT roundtrip");
        check(decoded.cells().sample(-2,0,0)==null,"NBT unknown remains unobserved");
        check(decoded.cells().sample(-3,0,0).occupancy()==BlockSample.Occupancy.AIR,"NBT known air retained");
        String key="mysticism.landmark.geometry."+page.id()+".0";
        NbtCompound n=LandmarkNbt.encodeLandmark(original,List.of(key));
        Landmark restored=LandmarkNbt.decodeLandmark(n,k->{ check(k.equals(key),"geometry reference exact"); return decoded; });
        check(restored.id().equals(original.id()) && restored.baseEmbedding().equals(original.baseEmbedding()) && restored.geometry().frontiers().equals(original.geometry().frontiers()),"immutable landmark metadata NBT");
        check(restored.ownership().equals(original.ownership()) && restored.geometry().queryMaterials(original.bounds(),10).equals(original.geometry().queryMaterials(original.bounds(),10)),"palette and ownership roundtrip");
        check(restored.equals(original) && restored.hashCode()==original.hashCode(),"complete immutable value equality after NBT roundtrip");
        NbtCompound corrupt=n.copy(); corrupt.putIntArray("embedding",new int[]{0});
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(corrupt,k->decoded),"reject wrong vector length before geometry load");
        NbtCompound newer=n.copy(); newer.putInt("schema",LandmarkNbt.SCHEMA+1);
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(newer,k->decoded),"future schema never empty fallback");
        NbtCompound overlap=geometry.copy(); NbtList leaves=overlap.getList("leaves",NbtElement.COMPOUND_TYPE); leaves.add(leaves.getCompound(0).copy());
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeGeometry(overlap),"overlapping encoded leaves rejected");
        NbtCompound rectangle=geometry.copy();
        LandmarkNbt.putBounds(rectangle.getList("leaves",NbtElement.COMPOUND_TYPE).getCompound(0),"bounds",new Bounds(-4,0,0,-2,1,1));
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeGeometry(rectangle),"rectangles cannot evade decoded leaf budget");
        NbtCompound unaligned=geometry.copy();
        LandmarkNbt.putBounds(unaligned.getList("leaves",NbtElement.COMPOUND_TYPE).getCompound(0),"bounds",Bounds.cube(-3,0,0,2));
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeGeometry(unaligned),"unaligned cubes cannot expand decoded leaf budget");
        NbtCompound brokenType=n.copy(); brokenType.put("geometry",new NbtList()); brokenType.put("claims",new NbtList()); brokenType.putString("frontiers","unknown");
        fails(IllegalArgumentException.class,()->LandmarkNbt.decodeLandmark(brokenType,k->decoded),"strict NBT field types");
    }
    private static java.util.Optional<Landmark> hydrated(LandmarkStore store,String id) {
        if(store.metadata(id).isEmpty()) return java.util.Optional.empty();
        var read=store.beginGeometryRead(id); List<GeometryPage> pages=new java.util.ArrayList<>();
        do { read.advance(1,256); pages.addAll(read.drain()); } while(!read.complete());
        return java.util.Optional.of(LandmarkNbt.hydrate(read.metadata(),pages));
    }
    private static PersistentStateManager manager(Path path,RegistryWrapper.WrapperLookup lookup) {
        // Identity DFU is a deterministic test dependency, not a replacement for the server's fixer.
        DataFixer identity=new DataFixer() {
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) { return input; }
            @Override public Schema getSchema(int version) { throw new UnsupportedOperationException("schema unnecessary for current-version fixture"); }
        };
        return new PersistentStateManager(path.toFile(),identity,lookup);
    }
    private static void pagingAliasesRestart() throws Exception {
        SharedConstants.createGameVersion();
        RegistryWrapper.WrapperLookup lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        Path dir=Files.createTempDirectory("landmark-persistent-state-");
        PersistentStateManager manager=manager(dir,lookup); LandmarkStore store=LandmarkStore.open(manager,dir);
        Landmark a=withGeometry(-4),b=withGeometry(4);
        var put=store.stagePut(a,-1);
        check(put.remainingPages()==3 && !put.complete(),"geometry + metadata + catalog page budget");
        check(put.advance(1)==1 && put.remainingPages()==2,"one page of incremental work");
        check(hydrated(store,a.id()).isEmpty(),"geometry staged but invisible");
        manager.save();
        check(LandmarkStore.open(manager(dir,lookup),dir).ids().isEmpty(),"restart cannot see partially published catalog");
        check(put.advance(1)==1 && hydrated(store,a.id()).isEmpty(),"metadata staged but invisible");
        check(put.advance(1)==1 && put.complete() && put.advance(1)==0,"single bounded final publication");
        check(hydrated(store,a.id()).orElseThrow().geometry().sample(-3,0,0).occupancy()==BlockSample.Occupancy.AIR,"published usable geometry");
        store.stagePut(b,-1).advance(8);
        manager.save();
        PersistentStateManager reloadedManager=manager(dir,lookup);
        LandmarkStore loaded=LandmarkStore.open(reloadedManager,dir);
        check(loaded.ids().size()==2 && hydrated(loaded,a.id()).orElseThrow().baseEmbedding().equals(a.baseEmbedding()),"real compressed PersistentState disk reload");
        LandmarkRepository.VerifiedConnectivity proof=new LandmarkRepository.VerifiedConnectivity(List.of(new LandmarkRepository.RevisionRef(a.id(),0),new LandmarkRepository.RevisionRef(b.id(),0)),"loaded-six-neighbour-proof");
        hydrated(loaded,b.id()); // optional compatibility read; topology does not require residency
        var merge=loaded.stageMerge(proof,1,new ImportancePolicy(0.8,20,0.01,1));
        check(merge.advance(1)==1 && loaded.ids().size()==2,"merge aliases unpublished while paging");
        while(!merge.complete()) check(merge.advance(1)==1,"bounded merge dirty pages");
        String canonical=List.of(a.id(),b.id()).stream().min(String::compareTo).orElseThrow();
        String alias=canonical.equals(a.id())?b.id():a.id();
        check(loaded.resolve(alias).equals(canonical) && hydrated(loaded,alias).orElseThrow().id().equals(canonical),"published stable alias");
        reloadedManager.save();
        LandmarkStore aliasRestart=LandmarkStore.open(manager(dir,lookup),dir);
        check(aliasRestart.resolve(alias).equals(canonical) && hydrated(aliasRestart,alias).orElseThrow().revision()==1,"alias topology actual disk restart");
        NbtCompound manifest=loaded.writeNbt(new NbtCompound(),lookup);
        LandmarkStore decodedManifest=LandmarkStore.fromNbt(manifest,lookup);
        check(decodedManifest.writeNbt(new NbtCompound(),lookup).equals(manifest),"manifest aliases deterministic NBT roundtrip");
        NbtCompound cycle=manifest.copy(); NbtCompound aliases=cycle.getCompound("aliases"); aliases.putString("cycle-a","cycle-b"); aliases.putString("cycle-b","cycle-a");
        fails(IllegalArgumentException.class,()->LandmarkStore.fromNbt(cycle,lookup),"persistent alias cycle rejected");
        fails(IllegalArgumentException.class,()->loaded.stagePut(canonical.equals(a.id())?b:a,-1),"alias never reused as live ID");
        var cancelled=loaded.stagePut(hydrated(loaded,canonical).orElseThrow().withActivity(new ActivityMetadata(0.6,2),new Ownership(List.of())),1);
        check(cancelled.advance(1)==1,"unchanged geometry versions bounded scan"); cancelled.cancel();
        check(hydrated(loaded,canonical).orElseThrow().revision()==1,"cancel preserves prior revision");
        fails(IllegalStateException.class,()->cancelled.advance(1),"cancel cannot publish");
        AtomicReference<Throwable> threadFailure=new AtomicReference<>(); Thread thread=new Thread(()->{try { loaded.ids(); } catch(Throwable e) { threadFailure.set(e); }});
        thread.start(); thread.join(); check(threadFailure.get() instanceof IllegalStateException,"off-server-thread store access rejected");
        Landmark parent=hydrated(loaded,canonical).orElseThrow();
        Landmark left=LandmarkCoreSelfTest.landmark(new BlockPoint(-3,0,0),Bounds.cube(-3,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        Landmark right=LandmarkCoreSelfTest.landmark(new BlockPoint(5,0,0),Bounds.cube(5,0,0,1),parent.baseEmbedding(),0.5,new SourceGeometry(List.of(),List.of()),2,new Ownership(List.of()));
        var split=loaded.stageSplit(new LandmarkRepository.RevisionRef(alias,1),List.of(right,left));
        while(!split.complete()) split.advance(1);
        check(hydrated(loaded,alias).isEmpty() && hydrated(loaded,canonical).isEmpty() && loaded.tombstones().containsKey(canonical),"split aliases resolve tombstone, not arbitrary child");
        check(loaded.lineage().get(canonical).equals(List.of(left.id(),right.id()).stream().sorted().toList()),"persisted deterministic one-to-many split lineage");
        reloadedManager.save(); LandmarkStore splitRestart=LandmarkStore.open(manager(dir,lookup),dir);
        check(hydrated(splitRestart,alias).isEmpty() && splitRestart.ids().size()==2 && splitRestart.lineage().equals(loaded.lineage()),"split tombstones and children actual disk restart");
        fails(IllegalStateException.class,()->loaded.stageDelete(new LandmarkRepository.RevisionRef(left.id(),1)),"stale store revision rejected");
        var delete=loaded.stageDelete(new LandmarkRepository.RevisionRef(left.id(),2)); check(delete.advance(1)==1 && hydrated(loaded,left.id()).isEmpty(),"delete single manifest page");
        check(LandmarkStore.fromNbt(loaded.writeNbt(new NbtCompound(),lookup),lookup).writeNbt(new NbtCompound(),lookup).equals(loaded.writeNbt(new NbtCompound(),lookup)),"tombstones/lineage manifest reload");
        System.out.println("PersistentState scratch directory: "+dir);
    }
    private static void corruptPagesNeverReplaced() throws Exception {
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        for(String kind:List.of("geometry","record")) for(boolean compressed:List.of(true,false)) {
            Path dir=Files.createTempDirectory("landmark-corrupt-"+kind+"-");
            var originalManager=manager(dir,lookup); var initial=LandmarkStore.open(originalManager,dir);
            Landmark value=withGeometry(-4); initial.stagePut(value,-1).advance(8); originalManager.save();
            String id=kind.equals("geometry")?value.geometry().pages().getFirst().id():value.id();
            String savedKey=kind.equals("geometry")?"mysticism.landmark.geometry."+id+".0":initial.writeNbt(new NbtCompound(),lookup).getCompound("records").getCompound(id).getString("key");
            Path file=dir.resolve(savedKey+".dat");
            if(compressed) {
                NbtCompound malformed=new NbtCompound(); malformed.put("data",new NbtCompound());
                malformed.putInt("DataVersion",SharedConstants.getGameVersion().getSaveVersion().getId());
                NbtIo.writeCompressed(malformed,file);
            } else Files.write(file,new byte[]{1,2,3,4});
            byte[] corrupt=Files.readAllBytes(file);
            var reloadedManager=manager(dir,lookup); var store=LandmarkStore.open(reloadedManager,dir);
            NbtCompound before=store.writeNbt(new NbtCompound(),lookup);
            Landmark updated=value.withActivity(new ActivityMetadata(0.6,1),value.ownership());
            if(kind.equals("geometry")) {
                var pending=store.stagePut(updated,0);
                fails(IllegalStateException.class,()->pending.advance(8),"existing corrupt geometry is not absent"); pending.cancel();
                var activity=store.stageActivity(new LandmarkRepository.RevisionRef(value.id(),0),new ActivityMetadata(0.7,2),value.ownership());
                fails(IllegalStateException.class,()->activity.advance(8),"metadata-only update rejects unreadable retained page"); activity.cancel();
            } else {
                fails(IllegalStateException.class,()->store.metadata(value.id()),"existing unreadable record fails metadata lookup");
                fails(IllegalStateException.class,()->store.stagePut(updated,0),"existing unreadable record fails CAS staging");
            }
            check(store.writeNbt(new NbtCompound(),lookup).equals(before),"failed corruption read leaves catalog clean");
            reloadedManager.save();
            check(Arrays.equals(corrupt,Files.readAllBytes(file)),"corrupt page not dirtied or overwritten on save");
            check(store.writeNbt(new NbtCompound(),lookup).getCompound("records").getCompound(value.id()).getLong("revision")==0,"no replacement record published after corruption");
        }
    }
    private static Landmark checkerboard(long x) {
        Bounds bounds=Bounds.cube(x,0,0,32);
        BlockPalette palette=new BlockPalette(List.of(new BlockPalette.State("minecraft:air",Map.of()),new BlockPalette.State("minecraft:stone",Map.of())));
        SparseOctree<BlockSample> tree=SparseOctree.empty(bounds,1,32);
        for(int dx=0;dx<32;dx++) for(int y=0;y<32;y++) for(int z=0;z<32;z++) {
            boolean solid=((dx+y+z)&1)==0;
            tree=tree.with(Bounds.cube(x+dx,y,z,1),new BlockSample(solid?BlockSample.Occupancy.SOLID:BlockSample.Occupancy.AIR,solid?1:0),128);
        }
        GeometryPage page=new GeometryPage(LandmarkIds.geometryPage("minecraft:overworld",new BlockPoint(x,0,0),32),0,bounds,palette,tree);
        return LandmarkCoreSelfTest.landmark(new BlockPoint(x,0,0),bounds,LandmarkCoreSelfTest.embedding(0.25,0,0,0),0.5,new SourceGeometry(List.of(page),List.of()),0,new Ownership(List.of()));
    }
    private static void metadataAndBudgetedReads() throws Exception {
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty()); Path dir=Files.createTempDirectory("landmark-budgeted-");
        var m=manager(dir,lookup); var original=LandmarkStore.open(m,dir);
        List<Landmark> dense=List.of(checkerboard(0),checkerboard(32),checkerboard(64));
        for(Landmark l:dense) original.stagePut(l,-1).advance(8);
        for(int i=0;i<9;i++) original.stagePut(withGeometry(128+i*4),-1).advance(8);
        m.save(); var reopenedManager=manager(dir,lookup); var store=LandmarkStore.open(reopenedManager,dir);
        long start=System.nanoTime();
        for(int i=0;i<30;i++) check(store.metadata(dense.getFirst().id()).orElseThrow().header().geometry().pages().isEmpty(),"metadata never substitutes reconstructed pages");
        check(store.sourceRange("minecraft:overworld",Bounds.cube(0,0,0,128),3,12).size()==3,"metadata-only source query");
        check(store.semanticRange(LandmarkCoreSelfTest.embedding(0.25,0,0,0),0.1,12,12).size()==12,"metadata-only semantic query");
        check(store.cacheStats().reconstructedLeaves()==0 && store.cacheStats().pages()==0,"lookup and range never reconstruct geometry");
        System.out.println("30 checkerboard metadata lookups and ranges: "+((System.nanoTime()-start)/1_000_000)+" ms");
        fails(IllegalStateException.class,()->store.find(dense.getFirst().id()),"find never silently hydrates a cache miss");
        Landmark target=dense.getFirst();
        var activity=store.stageActivity(new LandmarkRepository.RevisionRef(target.id(),0),new ActivityMetadata(0.7,1),target.ownership());
        while(!activity.complete()) activity.advance(1);
        check(store.metadata(target.id()).orElseThrow().revision()==1 && store.cacheStats().reconstructedLeaves()==0,"activity update retains opaque geometry without reconstruction");
        reopenedManager.save();
        for(Landmark l:dense) {
            var read=store.beginGeometryRead(l.id());
            long leavesBefore=store.cacheStats().reconstructedLeaves(); int pages=0;
            while(!read.complete()) {
                int worked=read.advance(1,257); check(worked<=257,"leaf reconstruction budget obeyed");
                var batch=read.drain(); check(batch.size()<=1,"decoded page batch obeys page budget"); pages+=batch.size();
                for(GeometryPage page:batch) check(page.knownCells().size()==32768 && page.cells().sample(page.bounds().minX(),0,0).occupancy()==BlockSample.Occupancy.SOLID,"checkerboard geometry/material survives incremental read");
            }
            check(pages==1 && store.cacheStats().reconstructedLeaves()-leavesBefore==32768,"all leaves eventually read, none truncated");
            check(store.cacheStats().pages()<=LandmarkStore.DECODED_PAGE_LIMIT && store.cacheStats().leaves()<=LandmarkStore.DECODED_LEAF_LIMIT,"bounded decoded leaf cache");
            long reconstructed=store.cacheStats().reconstructedLeaves();
            for(int i=0;i<10;i++) check(store.find(l.id()).orElseThrow().geometry().pages().size()==1,"resident find reuses immutable geometry");
            check(store.cacheStats().reconstructedLeaves()==reconstructed,"resident find performs no reconstruction");
        }
        check(store.cacheStats().pages()==2,"third full checkerboard evicts least recently used decoded leaves");
        for(int i=0;i<9;i++) hydrated(store,withGeometry(128+i*4).id());
        check(store.cacheStats().pages()==LandmarkStore.DECODED_PAGE_LIMIT,"decoded page count LRU cap");
        var cancelled=store.beginGeometryRead(target.id()); cancelled.advance(1,1); cancelled.cancel();
        fails(IllegalStateException.class,()->cancelled.advance(1,1),"cancelled geometry cursor cannot advance");
        var stale=store.beginGeometryRead(target.id());
        var next=store.stageActivity(new LandmarkRepository.RevisionRef(target.id(),1),new ActivityMetadata(0.7,2),target.ownership()); next.advance(8);
        check(!stale.isCurrent(),"geometry cursor exposes metadata revision guard"); stale.cancel();
        System.out.println("Budgeted PersistentState scratch directory: "+dir);
    }
    private static void cancellationDoesNotReserveCasRevision() throws Exception {
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        for(boolean restart:List.of(false,true)) {
            Path dir=Files.createTempDirectory("landmark-cancel-metadata-");
            var manager=manager(dir,lookup); var store=LandmarkStore.open(manager,dir); Landmark initial=withGeometry(0);
            store.stagePut(initial,-1).advance(8); manager.save();
            var a=store.stageActivity(new LandmarkRepository.RevisionRef(initial.id(),0),new ActivityMetadata(0.3,1),initial.ownership());
            check(a.advance(2)==2 && !a.complete(),"activity A metadata written, catalog not published");
            a.cancel(); manager.save();
            check(store.metadata(initial.id()).orElseThrow().revision()==0,"cancel after metadata write keeps committed CAS revision");
            Map<Path,byte[]> previous=new HashMap<>();
            try(var files=Files.list(dir)) { for(Path file:files.toList()) previous.put(file,Files.readAllBytes(file)); }
            if(restart) { manager=manager(dir,lookup); store=LandmarkStore.open(manager,dir); }
            var b=store.stageActivity(new LandmarkRepository.RevisionRef(initial.id(),0),new ActivityMetadata(0.8,2),initial.ownership());
            check(b.advance(2)==2 && !b.complete(),"different activity B can stage same CAS revision after cancellation/restart");
            manager.save();
            for(var entry:previous.entrySet()) check(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey())),"no committed or abandoned immutable page overwritten");
            try(var files=Files.list(dir)) { check(files.filter(p->p.getFileName().toString().startsWith("mysticism.landmark.record.")).count()==3,"distinct immutable content versions for two revision-1 attempts"); }
            check(b.advance(1)==1 && b.complete(),"B publishes independently of cancelled A"); manager.save();
            var reopened=LandmarkStore.open(manager(dir,lookup),dir);
            check(reopened.metadata(initial.id()).orElseThrow().header().activity().equals(new ActivityMetadata(0.8,2)),"B activity survives real disk restart");
            check(reopened.metadata(initial.id()).orElseThrow().geometryKeys().equals(store.metadata(initial.id()).orElseThrow().geometryKeys()),"cancelled metadata attempts never change geometry versions");
            // Schema 1 manifests with legacy revision-only storage keys remain readable.
            NbtCompound legacy=store.writeNbt(new NbtCompound(),lookup);
            NbtCompound ref=legacy.getCompound("records").getCompound(initial.id()); String hashed=ref.getString("key");
            String key="mysticism.landmark.record."+initial.id()+".1"; Files.copy(dir.resolve(hashed+".dat"),dir.resolve(key+".dat")); ref.putString("key",key);
            var legacyManager=manager(dir,lookup); legacyManager.set(LandmarkStore.SAVE_KEY,LandmarkStore.fromNbt(legacy,lookup));
            check(LandmarkStore.open(legacyManager,dir).metadata(initial.id()).orElseThrow().revision()==1,"legacy metadata key is backward compatible");
        }
    }
    private static Landmark packed(List<GeometryPage> pages,long revision,long anchorX) {
        Bounds bounds=pages.getFirst().bounds(); for(GeometryPage page:pages) bounds=bounds.union(page.bounds());
        return LandmarkCoreSelfTest.landmark(new BlockPoint(anchorX,0,0),bounds,LandmarkCoreSelfTest.embedding(0.25,0,0,0),0.5,new SourceGeometry(pages,List.of()),revision,new Ownership(List.of()));
    }
    private static LandmarkMetadata metadataOf(Landmark l) {
        return LandmarkNbt.decodeMetadata(LandmarkNbt.encodeLandmark(l,l.geometry().pages().stream().map(p->"mysticism.landmark.geometry."+p.id()+"."+p.revision()).toList()));
    }
    private static void largeTopologyWithoutResidency() throws Exception {
        var lookup=RegistryWrapper.WrapperLookup.of(Stream.empty());
        List<GeometryPage> sparse=new ArrayList<>(),dense=new ArrayList<>();
        for(int i=0;i<9;i++) sparse.add(withGeometry(i*4).geometry().pages().getFirst());
        for(int i=0;i<3;i++) dense.add(checkerboard(i*32).geometry().pages().getFirst());
        for(boolean leafLimit:List.of(false,true)) for(boolean reconciliation:List.of(false,true)) {
            List<GeometryPage> pages=leafLimit?dense:sparse;
            Landmark big=packed(pages,0,1),small=withGeometry(leafLimit?96:36);
            Path dir=Files.createTempDirectory("landmark-large-topology-"); var manager=manager(dir,lookup); var initial=LandmarkStore.open(manager,dir);
            initial.stagePut(big,-1).advance(32); initial.stagePut(small,-1).advance(8); manager.save();
            var reloadManager=manager(dir,lookup); var store=LandmarkStore.open(reloadManager,dir);
            var read=store.beginGeometryRead(big.id()); int streamed=0;
            while(!read.complete()) { read.advance(1,1024); streamed+=read.drain().size(); }
            check(streamed==pages.size(),"large landmark fully streamed without truncation");
            fails(IllegalStateException.class,()->store.find(big.id()),"large geometry cannot be forced into bounded resident cache");
            var proof=new LandmarkRepository.VerifiedConnectivity(List.of(new LandmarkRepository.RevisionRef(big.id(),0),new LandmarkRepository.RevisionRef(small.id(),0)),"loaded-face-proof");
            List<GeometryPage> combined=new ArrayList<>(pages); combined.addAll(small.geometry().pages()); SourceGeometry geometry=new SourceGeometry(combined,List.of());
            if(reconciliation) {
                var invalid=store.stageMerge(proof,1,new ImportancePolicy(0.8,20,0.01,1),new SourceGeometry(List.of(),List.of()));
                fails(IllegalArgumentException.class,()->invalid.advance(1,1),"streamed reconciliation rejects discarded prior known material"); invalid.cancel();
                check(store.ids().size()==2 && store.aliases().isEmpty(),"failed reconciliation cannot partially publish aliases");
            }
            long cacheLeaves=store.cacheStats().reconstructedLeaves();
            var merge=reconciliation?store.stageMerge(proof,1,new ImportancePolicy(0.8,20,0.01,1),geometry):store.stageMerge(proof,1,new ImportancePolicy(0.8,20,0.01,1));
            while(!merge.complete()) {
                long before=merge.validatedLeaves(); check(merge.advance(1,257)<=1,"large merge shares bounded page work across validation/write/publication");
                check(merge.validatedLeaves()-before<=257,"reconciliation respects old-leaf validation budget");
                check(store.cacheStats().pages()<=8 && store.cacheStats().leaves()<=65536,"topology never expands decoded cache");
            }
            check(store.cacheStats().reconstructedLeaves()==cacheLeaves,"merge does not reconstruct old geometry into resident cache");
            String canonical=List.of(big.id(),small.id()).stream().min(String::compareTo).orElseThrow();
            String alias=canonical.equals(big.id())?small.id():big.id();
            check(store.ids().equals(List.of(canonical)) && store.resolve(alias).equals(canonical),"large merge publishes records and aliases atomically");
            check(store.metadata(canonical).orElseThrow().geometryKeys().size()==combined.size(),"large merge preserves all immutable geometry refs");
            if(reconciliation) check(merge.validatedLeaves()==pages.stream().mapToLong(p->p.knownCells().size()).sum()+2,"all old known leaves validated, none truncated");
            else check(merge.validatedLeaves()==0,"opaque nonoverlapping merge needs no leaf reconstruction");
            reloadManager.save(); var restartManager=manager(dir,lookup); var restarted=LandmarkStore.open(restartManager,dir);
            check(restarted.resolve(alias).equals(canonical),"large merge aliases survive restart");
            List<GeometryPage> ordered=combined.stream().sorted(Comparator.comparingLong(p->p.bounds().minX())).toList(); int middle=ordered.size()/2;
            Landmark left=packed(ordered.subList(0,middle),2,ordered.getFirst().bounds().minX()+2);
            Landmark right=packed(ordered.subList(middle,ordered.size()),2,ordered.get(middle).bounds().minX()+2);
            if(!reconciliation) {
                fails(IllegalStateException.class,()->restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,0),List.of(metadataOf(right),metadataOf(left))),"metadata-only split retains stale CAS protection");
                LandmarkMetadata leftMetadata=metadataOf(left),rightMetadata=metadataOf(right);
                LandmarkMetadata incomplete=new LandmarkMetadata(rightMetadata.header(),List.of());
                fails(IllegalArgumentException.class,()->restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,1),List.of(leftMetadata,incomplete)),"opaque split cannot discard parent geometry pages");
                LandmarkMetadata foreign=new LandmarkMetadata(rightMetadata.header(),List.of("mysticism.landmark.geometry."+withGeometry(4096).geometry().pages().getFirst().id()+".0"));
                fails(IllegalArgumentException.class,()->restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,1),List.of(leftMetadata,foreign)),"opaque split rejects references not owned by parent");
                LandmarkMetadata duplicate=new LandmarkMetadata(rightMetadata.header(),leftMetadata.geometryKeys());
                fails(IllegalArgumentException.class,()->restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,1),List.of(leftMetadata,duplicate)),"opaque split cannot duplicate geometry into two children");
                // Swapping partitions covers every parent page but violates child bounds.
                var misplaced=restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,1),List.of(new LandmarkMetadata(leftMetadata.header(),rightMetadata.geometryKeys()),new LandmarkMetadata(rightMetadata.header(),leftMetadata.geometryKeys())));
                fails(IllegalArgumentException.class,()->misplaced.advance(1,1),"budgeted split validates referenced page bounds before publication"); misplaced.cancel();
                check(restarted.ids().equals(List.of(canonical)) && restarted.lineage().isEmpty(),"invalid opaque split never partially publishes topology");
            }
            // Both the existing geometry-supplied interface and opaque partition interface work cold.
            var split=reconciliation?restarted.stageSplit(new LandmarkRepository.RevisionRef(alias,1),List.of(right,left)):
                restarted.stageSplitMetadata(new LandmarkRepository.RevisionRef(alias,1),List.of(metadataOf(right),metadataOf(left)));
            while(!split.complete()) check(split.advance(1,257)<=1,"large split validation/publication remains page budgeted");
            check(restarted.metadata(alias).isEmpty() && restarted.tombstones().containsKey(canonical),"large split tombstones parent/alias instead of arbitrary child");
            check(restarted.lineage().get(canonical).equals(List.of(left.id(),right.id()).stream().sorted().toList()),"large split publishes deterministic lineage with children");
            check(restarted.cacheStats().pages()==0 && restarted.cacheStats().reconstructedLeaves()==0,"cold split never hydrates complete parent");
            restartManager.save(); var finalStore=LandmarkStore.open(manager(dir,lookup),dir);
            check(finalStore.ids().size()==2 && finalStore.lineage().equals(restarted.lineage()),"large split children and lineage survive disk restart");
            int references=finalStore.ids().stream().map(finalStore::metadata).mapToInt(m->m.orElseThrow().geometryKeys().size()).sum();
            check(references==combined.size(),"split never loses or silently truncates pages");
        }
    }
    public static void main(String[] args) throws Exception {
        roundtripAndMalformed(); pagingAliasesRestart(); corruptPagesNeverReplaced(); metadataAndBudgetedReads();
        cancellationDoesNotReserveCasRevision(); largeTopologyWithoutResidency();
        System.out.println("LandmarkPersistenceSelfTest PASS ("+checks+" explicit checks)");
    }
}
