package io.github.mysticism.landmark;

import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.landmark.extract.LandmarkProfiles;
import io.github.mysticism.vector.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentStateManager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Real source topology, native profile codecs and compressed PersistentState restart.
 * Observations/vectors are deterministic test inputs; no game boot or model inference. */
public final class DiscoveredLandmarkPersistenceTest {
    private static final String DIMENSION="minecraft:overworld";
    private static final RegistryWrapper.WrapperLookup LOOKUP=RegistryWrapper.WrapperLookup.of(Stream.empty());
    private static int checks;
    private static void check(boolean condition,String message) {
        checks++; if(!condition) throw new AssertionError(message);
    }
    private static void fails(Runnable action,String message) {
        checks++;
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static PersistentStateManager manager(Path directory) {
        DataFixer identity=new DataFixer() {
            @Override public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) { return input; }
            @Override public Schema getSchema(int version) { throw new UnsupportedOperationException(); }
        };
        return new PersistentStateManager(directory.toFile(),identity,LOOKUP);
    }
    private static LandmarkEmbedding embedding(EmbeddingProfile profile) {
        float[] values=new float[EmbeddingSpace.NATIVE_DIMENSIONS];
        for(int i=0;i<values.length;i++) values[i]=(i%17+1)*(i%2==0?1:-1);
        return new LandmarkEmbedding(profile,new Vec384f(new Vec384f(values).norm()));
    }
    private static Landmark discover(EmbeddingProfile profile,String floor,int width,List<Landmark> parents) {
        List<SourceLandmarks.Cell> cells=new ArrayList<>();
        for(int x=0;x<width;x++) for(int y=0;y<3;y++) {
            cells.add(new SourceLandmarks.Cell(new BlockPoint(x,y,0),
                    new BlockPalette.State(y==0?floor:"minecraft:air",Map.of()),"minecraft:plains",y>0));
        }
        var region=new SourceLandmarks.Region(DIMENSION,new Bounds(0,0,0,width,3,1),cells,true);
        var prepared=SourceLandmarks.prepare(region,parents,new BlockPos(0,1,0),null,false,20,63,Map.of());
        check(prepared!=null,"actual source observation produces connected topology");
        Landmark prior=prepared.prior();
        return new Landmark(prepared.id(),DIMENSION,SourceLandmarks.ALGORITHM,prepared.kind(),prepared.biome(),
                prepared.anchor(),OwnershipGeometry.bounds(prepared.anchor(),prepared.geometry()),embedding(profile),.08,
                prior==null?new ActivityMetadata(0,20):prior.activity(),
                prior==null?new Ownership(List.of()):prior.ownership(),prepared.geometry(),
                prior==null?0:prior.revision()+1,"test observation through production source topology");
    }
    private static void publish(LandmarkStore.PendingMutation mutation) {
        for(int i=0;!mutation.complete();i++) {
            check(i<1000,"bounded mutation terminates");
            check(mutation.advance(1,2)<=1,"one page/publication per step");
        }
    }
    private static Landmark hydrate(LandmarkStore store,String id) {
        var read=store.beginGeometryRead(id); List<GeometryPage> pages=new ArrayList<>();
        for(int i=0;!read.complete();i++) {
            check(i<1000,"bounded geometry read terminates");
            check(read.advance(1,2)<=2,"leaf reconstruction budget");
            pages.addAll(read.drain());
        }
        return LandmarkNbt.hydrate(read.metadata(),pages);
    }
    private static SourceOwnership.Region owners(LandmarkStore store,Bounds bounds) {
        var future=new CompletableFuture<SourceOwnership.Region>();
        var request=new SourceOwnership.Request(store,DIMENSION,bounds,future);
        for(int i=0;!request.done;i++) { check(i<1000,"ownership admission terminates"); request.advance(); }
        return future.join();
    }
    private static void compatibleRestartAndGrowth() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("build"),"mysticism-discovered-restart-");
        var manager=manager(directory); var store=LandmarkStore.open(manager,directory);
        EmbeddingProfile profile=LandmarkProfiles.current(); store.clearGeneratedIfIncompatible(profile);
        Landmark discovered=discover(profile,"minecraft:stone",2,List.of());
        var mutation=store.stagePut(discovered,-1);
        mutation.advance(1,2); manager.save();
        var partial=LandmarkStore.open(manager(directory),directory);
        partial.clearGeneratedIfIncompatible(profile);
        check(partial.ids().isEmpty(),"unpublished observation is never fabricated on restart");
        publish(mutation); manager.save();
        manager=manager(directory); store=LandmarkStore.open(manager,directory);
        check(store.usesNativeSourceProfile(profile),"real native manifest admitted after disk reload");
        NbtCompound before=store.writeNbt(new NbtCompound(),LOOKUP);
        store.clearGeneratedIfIncompatible(profile);
        check(before.equals(store.writeNbt(new NbtCompound(),LOOKUP))&&!store.isDirty(),"compatible startup preserves manifest and dirty state");
        check(store.cacheStats().reconstructedLeaves()==0,"startup performs no geometry hydration");
        check(store.sourceRangePage(DIMENSION,new Bounds(0,0,0,3,3,1),null,1,1).landmarks().getFirst().id().equals(discovered.id()),"saved discovery available to source admission");
        check(store.semanticRangePage(discovered.baseEmbedding(),.01,null,1,1).landmarks().getFirst().id().equals(discovered.id()),"saved discovery available to semantic terrain admission");
        var ownership=owners(store,new Bounds(0,0,0,3,3,1));
        check(ownership.ownerAt(new BlockPos(0,0,0)).orElseThrow().equals(discovered.id()),"solid ownership survives cold restart");
        check(ownership.ownerAt(new BlockPos(0,1,0)).orElseThrow().equals(discovered.id()),"known air ownership survives cold restart");
        check(ownership.ownerAt(new BlockPos(2,1,0)).isEmpty(),"bounding box/unknown cells do not fabricate ownership");
        Landmark restored=hydrate(store,discovered.id());
        check(restored.equals(discovered),"complete discovery, palette, frontiers and native vector survive compressed restart");
        check(Arrays.equals(restored.baseEmbedding().vector().toBits(),discovered.baseEmbedding().vector().toBits()),"all native vector coordinates retained bit-for-bit");
        check(restored.baseEmbedding().data().length==768&&restored.baseEmbedding().data()[767]!=0,"native v2 tail is not truncated");
        Landmark grown=discover(profile,"minecraft:dirt",3,List.of(restored));
        check(grown.id().equals(discovered.id())&&grown.anchor().equals(discovered.anchor()),"reload growth retains source-coordinate seed identity");
        publish(store.stagePut(grown,restored.revision()));
        var claims=new Ownership(List.of(new Ownership.Claim(UUID.fromString("00000000-0000-0000-0000-000000000001"),30)));
        long ownershipRevision=store.ownershipRevision();
        publish(store.stageActivity(new LandmarkRepository.RevisionRef(grown.id(),grown.revision()),new ActivityMetadata(.4,30),claims));
        check(store.ownershipRevision()==ownershipRevision,"activity publication does not invalidate source ownership");
        manager.save(); store=LandmarkStore.open(manager(directory),directory); store.clearGeneratedIfIncompatible(profile);
        Landmark finalValue=hydrate(store,grown.id());
        check(finalValue.geometry().equals(grown.geometry())&&finalValue.ownership().equals(claims)&&finalValue.activity().equals(new ActivityMetadata(.4,30)),"grown material mask and claims survive second restart");
        check(owners(store,new Bounds(0,0,0,4,3,1)).ownerAt(new BlockPos(2,0,0)).orElseThrow().equals(discovered.id()),"newly grown cells remain available after save/load");
    }
    private static void profileResetDoesNotPoisonRediscovery() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("build"),"mysticism-discovery-profile-reset-");
        var current=LandmarkProfiles.current();
        // A different *native* profile; never a synthetic v1 catalogue or migrated vector.
        var previous=new EmbeddingProfile(current.model(),"other-native-revision",current.tokenizer(),current.prefixPolicy(),
                current.dimensions(),current.normalization(),"other-native-descriptor");
        var manager=manager(directory); var store=LandmarkStore.open(manager,directory);
        store.clearGeneratedIfIncompatible(previous);
        Landmark old=discover(previous,"minecraft:stone",2,List.of()); publish(store.stagePut(old,-1)); manager.save();
        Map<Path,byte[]> oldPages=new HashMap<>();
        try(var files=Files.list(directory)) {
            for(Path path:files.filter(p->!p.getFileName().toString().equals(LandmarkStore.SAVE_KEY+".dat")).toList()) oldPages.put(path,Files.readAllBytes(path));
        }
        manager=manager(directory); store=LandmarkStore.open(manager,directory);
        check(!store.usesNativeSourceProfile(current),"different native profile requires fresh discovery");
        var staleRead=store.beginGeometryRead(old.id());
        staleRead.advance(1,1);
        long revision=store.ownershipRevision(); store.clearGeneratedIfIncompatible(current);
        check(store.ids().isEmpty()&&store.ownershipRevision()>revision,"fresh-profile reset removes references, not source blocks");
        check(!staleRead.isCurrent()&&store.geometryReadAvailable(),"profile reset invalidates and releases in-flight geometry reads");
        fails(()->staleRead.advance(1,1),"cancelled old geometry cannot be read after reset");
        manager.save(); manager=manager(directory); store=LandmarkStore.open(manager,directory);
        store.clearGeneratedIfIncompatible(current);
        check(store.usesNativeSourceProfile(current),"empty fresh-profile generation survives restart before rediscovery");
        check(store.ids().isEmpty(),"empty fresh-profile catalogue survives restart");
        check(store.semanticRangePage(embedding(current),.1,null,4,4).landmarks().isEmpty(),"reset does not promote prior profile entries");
        Landmark fresh=discover(current,"minecraft:dirt",2,List.of());
        check(old.id().equals(fresh.id())&&old.geometry().pages().getFirst().id().equals(fresh.geometry().pages().getFirst().id()),"real rediscovery reuses deterministic source seed and page ID");
        publish(store.stagePut(fresh,-1)); // pre-fix: immutable page version conflict with retained old source geometry
        manager.save();
        for(var entry:oldPages.entrySet()) check(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey())),"reset/rediscovery never overwrites retained immutable pages");
        manager=manager(directory); store=LandmarkStore.open(manager,directory); store.clearGeneratedIfIncompatible(current);
        check(hydrate(store,fresh.id()).equals(fresh),"new profile/material discovery survives real disk restart");
        check(!staleRead.isCurrent(),"same source seed and revision do not resurrect a pre-reset read");
        check(owners(store,new Bounds(0,0,0,2,3,1)).owners().size()==6,"fresh exact source ownership available without model readiness");
        // Cycling profiles must not reuse any previous immutable namespace, even with the same seed/revision.
        store.clearGeneratedIfIncompatible(previous);
        Landmark cycled=discover(previous,"minecraft:sandstone",2,List.of()); publish(store.stagePut(cycled,-1)); manager.save();
        store=LandmarkStore.open(manager(directory),directory); store.clearGeneratedIfIncompatible(previous);
        check(hydrate(store,cycled.id()).equals(cycled),"profile cycle is fresh observation, not old page resurrection");
    }
    private static void priorNativeSourceFormatAndFreshRules() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("build"),"mysticism-native-pre-epoch-");
        var current=LandmarkProfiles.current(); var manager=manager(directory);
        var store=LandmarkStore.open(manager,directory); store.clearGeneratedIfIncompatible(current);
        // Write a genuine native source-v2 observation in the previously shipped, bare-key
        // format. Only the optional new namespace field is absent; no v1 data is invented.
        NbtCompound empty=store.writeNbt(new NbtCompound(),LOOKUP); empty.remove("geometryEpoch");
        manager.set(LandmarkStore.SAVE_KEY,LandmarkStore.fromNbt(empty,LOOKUP));
        store=LandmarkStore.open(manager,directory);
        Landmark observed=discover(current,"minecraft:stone",2,List.of()); publish(store.stagePut(observed,-1)); manager.save();
        manager=manager(directory); store=LandmarkStore.open(manager,directory);
        NbtCompound before=store.writeNbt(new NbtCompound(),LOOKUP); store.clearGeneratedIfIncompatible(current);
        check(before.equals(store.writeNbt(new NbtCompound(),LOOKUP))&&!store.isDirty(),"existing native source-v2 discoveries need no rewrite or migration");
        check(hydrate(store,observed.id()).equals(observed),"existing bare-key native geometry remains usable");
        check(owners(store,new Bounds(0,0,0,2,3,1)).owners().size()==6,"existing native geometry retains exact air/solid ownership");
        Landmark grown=discover(current,"minecraft:dirt",3,List.of(observed)); publish(store.stagePut(grown,0)); manager.save();
        manager=manager(directory); store=LandmarkStore.open(manager,directory); store.clearGeneratedIfIncompatible(current);
        check(hydrate(store,grown.id()).equals(grown),"existing native source-v2 can grow and save without geometry key migration");
        // The native vector alone is NOT sufficient: the fresh source-generation marker
        // must be present. Existing codecs decode this fixture, then startup rejects it.
        NbtCompound missingMarker=store.writeNbt(new NbtCompound(),LOOKUP); missingMarker.remove("sourceGeneration");
        var replacement=LandmarkStore.fromNbt(missingMarker,LOOKUP); replacement.markDirty();
        manager.set(LandmarkStore.SAVE_KEY,replacement); manager.save();
        manager=manager(directory); store=LandmarkStore.open(manager,directory);
        check(!store.usesNativeSourceProfile(current),"native vector does not fabricate fresh source provenance");
        store.clearGeneratedIfIncompatible(current); check(store.ids().isEmpty(),"missing generation marker forces actual new discovery");
        Landmark fresh=discover(current,"minecraft:sandstone",3,List.of()); publish(store.stagePut(fresh,-1)); manager.save();
        store=LandmarkStore.open(manager(directory),directory); store.clearGeneratedIfIncompatible(current);
        check(hydrate(store,fresh.id()).equals(fresh),"fresh discovery does not conflict with retained bare geometry");
        NbtCompound manifest=store.writeNbt(new NbtCompound(),LOOKUP);
        NbtCompound malformed=manifest.copy(); malformed.putString("geometryEpoch","../other-state");
        fails(()->LandmarkStore.fromNbt(malformed,LOOKUP),"malformed namespace is rejected, not silently reset");
        NbtCompound wrongType=manifest.copy(); wrongType.putInt("geometryEpoch",2);
        fails(()->LandmarkStore.fromNbt(wrongType,LOOKUP),"wrong namespace NBT type rejected");
        LandmarkMetadata metadata=store.metadata(fresh.id()).orElseThrow(); String key=metadata.geometryKeys().getFirst();
        String duplicate=key.substring(0,key.indexOf(".e-"))+".e-"+"0".repeat(32);
        fails(()->new LandmarkMetadata(metadata.header(),List.of(key,duplicate)),"namespace change cannot disguise duplicate octree page identities");
        String malformedKey=key.replace(".e-",".e-/");
        fails(()->new LandmarkMetadata(metadata.header(),List.of(malformedKey)),"malformed geometry reference rejected");
        GeometryPage page=fresh.geometry().pages().getFirst();
        check(!LandmarkNbt.geometryReferenceMatches(key,page.id(),page.revision()+1),"namespace never hides wrong page revision");
        check(!LandmarkNbt.geometryReferenceMatches(key,LandmarkIds.seed(DIMENSION,"other",Landmark.Kind.BIOME,"minecraft:plains",new BlockPoint(9,9,9)),page.revision()),"namespace never hides wrong source page identity");
        Path pageFile=directory.resolve(key+".dat");
        NbtCompound badPage=NbtIo.readCompressed(pageFile,NbtSizeTracker.ofUnlimitedBytes());
        badPage.getCompound("data").getCompound("payload").putLong("revision",page.revision()+1);
        NbtIo.writeCompressed(badPage,pageFile);
        var corrupted=LandmarkStore.open(manager(directory),directory);
        var skipped=corrupted.beginGeometryRead(fresh.id(),new Bounds(100,100,100,101,101,101));
        fails(()->skipped.advance(1,1),"out-of-range geometry still rejects a corrupt namespaced page identity");
        skipped.cancel();
        check(corrupted.cacheStats().reconstructedLeaves()==0,"corrupt header is rejected before any leaf reconstruction");
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        compatibleRestartAndGrowth(); profileResetDoesNotPoisonRediscovery(); priorNativeSourceFormatAndFreshRules();
        System.out.println("Discovered landmark persistence: "+checks+" checks passed (disk/topology; no game/model launch)");
    }
}
