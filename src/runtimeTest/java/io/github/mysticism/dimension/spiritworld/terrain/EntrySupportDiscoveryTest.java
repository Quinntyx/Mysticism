package io.github.mysticism.dimension.spiritworld.terrain;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import io.github.mysticism.landmark.Bounds;
import io.github.mysticism.landmark.LandmarkMetadata;
import io.github.mysticism.landmark.LandmarkStore;
import io.github.mysticism.landmark.SourceOwnership;
import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentStateManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Real empty ownership lookup -> unfinished discovery -> real mesh contact -> entry stance.
 * No server/game bootstrap or model IO. Reflection accesses only existing detached-store/request
 * fixture boundaries; player/Fabric callback wiring is checked separately against production source. */
public final class EntrySupportDiscoveryTest {
    private static int checks;
    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    private record EmptyOwnership(LandmarkStore store, SourceOwnership.Region snapshot, BlockPos feet) {
        boolean pending() {
            // Same snapshot stamp/coverage rule as ownershipPending(player), on our attached store.
            // There is no MinecraftServer in this detached fixture; the store instance is retained.
            return snapshot.ownershipRevision() != store.ownershipRevision()
                    || !snapshot.bounds().contains(feet.getX(), feet.getY(), feet.getZ());
        }
    }

    private static EmptyOwnership completedEmptyOwnership() throws Exception {
        SharedConstants.createGameVersion();
        Path fixtures = Path.of("build", "runtime-test-fixtures").toAbsolutePath();
        Files.createDirectories(fixtures);
        Path path = Files.createTempDirectory(fixtures, "entry-empty-owners-");
        DataFixer identity = new DataFixer() {
            public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> value, int from, int to) { return value; }
            public Schema getSchema(int version) { throw new UnsupportedOperationException(); }
        };
        var manager = new PersistentStateManager(path.toFile(), identity, RegistryWrapper.WrapperLookup.of(Stream.empty()));
        var open = LandmarkStore.class.getDeclaredMethod("open", PersistentStateManager.class, Path.class);
        open.setAccessible(true);
        var store = (LandmarkStore) open.invoke(null, manager, path);
        BlockPos feet = new BlockPos(-18, 65, -35);
        Bounds bounds = new Bounds(-24, 60, -40, -8, 76, -24);
        var ownership = new CompletableFuture<SourceOwnership.Region>();
        Class<?> requestClass = Class.forName("io.github.mysticism.landmark.SourceOwnership$Request");
        var constructor = requestClass.getDeclaredConstructor(LandmarkStore.class, String.class, Bounds.class, CompletableFuture.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance(store, "minecraft:overworld", bounds, ownership);
        var advance = requestClass.getDeclaredMethod("advance");
        advance.setAccessible(true);
        for (int tick = 0; tick < 4 && !ownership.isDone(); tick++) advance.invoke(request);
        check(ownership.isDone() && !ownership.isCompletedExceptionally(), "real ownership request must complete independently of discovery");
        var snapshot = ownership.getNow(null);
        check(snapshot != null && snapshot.owners().isEmpty() && snapshot.ownerAt(feet).isEmpty(),
                "fresh source lookup must produce a real empty snapshot, not a fabricated owner");
        var result = new EmptyOwnership(store, snapshot, feet);
        check(!result.pending(), "the completed empty snapshot is current and covers the entry feet");
        return result;
    }

    private static boolean meshContact() {
        Vec3d origin = new Vec3d(-18, 64, -35);
        var cell = new TerrainMeshFrame.Cell(1, 0, "", origin, origin,
                new Vec3d(1, 1, 1), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1),
                0xffffff, 0, 1, List.of(new Box(0, 0, 0, 1, 1, 1)));
        var frame = new TerrainMeshFrame(1, true, "minecraft:overworld", origin, origin,
                List.of(new TerrainMeshFrame.Material("minecraft:stone", Map.of())), List.of(cell));
        var collision = new MeshCollision.Index(frame);
        Box body = new Box(-17.8, 65, -34.8, -17.2, 66.8, -34.2);
        return collision.sweep(body.offset(0, .025, 0), new Vec3d(0, -.15, 0))
                .filter(hit -> hit.normal().y > .3).isPresent();
    }

    private static void emptySnapshotBeforeDiscoveryKeepsBoundedWalking() throws Exception {
        var ownership = completedEmptyOwnership();
        var discovery = new CompletableFuture<Optional<LandmarkMetadata>>();
        var lifecycle = new InitialSourceDiscovery();
        var server = new ArrayDeque<Runnable>();
        lifecycle.await(() -> discovery, server::addLast, (result, error) -> {});
        boolean standing = meshContact();
        check(standing, "unowned entry frame must have real visible/collidable floor contact");
        // Reproduces the production ordering: source ownership queue finished, while the separate
        // ensureSourceLocation operation still waits on extraction/model/publication work.
        check(lifecycle.groundPending(ownership.pending()),
                "current EMPTY ownership must not hide an unfinished source-discovery future");
        int unsupported = 0, waited = 0;
        for (int tick = 0; tick < SpiritNavigationService.ENTRY_SUPPORT_GRACE_TICKS; tick++) {
            var step = SpiritNavigationService.entryStance(unsupported, waited, standing, false, lifecycle.groundPending(ownership.pending()));
            check(step.stance() == SpiritNavigationService.Stance.HOLD_ENTRY_SUPPORT,
                    "empty ownership before discovery must keep flight off at tick " + tick);
            unsupported = step.unsupportedTicks(); waited = step.entrySupportTicks();
        }
        var expired = SpiritNavigationService.entryStance(unsupported, waited, standing, false, lifecycle.groundPending(ownership.pending()));
        check(expired.stance() == SpiritNavigationService.Stance.FREE_FALL,
                "never-completing discovery still expires at the entry grace, not permanent walking");
        check(SpiritNavigationService.entryStance(0, 0, false, false, lifecycle.groundPending(ownership.pending())).stance()
                == SpiritNavigationService.Stance.FREE_FALL, "pending discovery never authorizes walking on air");

        discovery.complete(Optional.empty());
        check(lifecycle.groundPending(ownership.pending()), "completed worker still holds until server application");
        server.removeFirst().run();
        check(!lifecycle.groundPending(ownership.pending()),
                "completed-empty discovery with settled empty ownership must stop the hold");
        check(SpiritNavigationService.entryStance(0, 20, standing, false, lifecycle.groundPending(ownership.pending())).stance()
                == SpiritNavigationService.Stance.FREE_FALL, "terminal unowned source is not supported walking");
    }

    private static void terminalDiscoveryAndOwnershipRefresh() {
        var failed = new CompletableFuture<Optional<LandmarkMetadata>>();
        failed.completeExceptionally(new IllegalStateException("source unavailable"));
        var cancelled = new CompletableFuture<Optional<LandmarkMetadata>>();
        cancelled.cancel(false);
        for (var terminal : List.of(failed, cancelled, CompletableFuture.completedFuture(Optional.<LandmarkMetadata>empty()))) {
            var lifecycle = new InitialSourceDiscovery();
            var server = new ArrayDeque<Runnable>();
            lifecycle.await(() -> terminal, server::addLast, (result, error) -> {});
            check(lifecycle.groundPending(false), "terminal worker still waits for server application");
            server.removeFirst().run();
            check(!lifecycle.groundPending(false), "settled failure/cancellation/empty discovery is not pending");
            check(lifecycle.groundPending(true), "ownership refresh has its independent bounded wait");
        }
        check(!new InitialSourceDiscovery().groundPending(false), "no started discovery cannot fabricate a wait");
    }

    /** Both retained parents must track the SAME request through server-thread application:
     * actionable diagnostics cannot settle early while walking loses its pending permission. */
    private static void combinedDiscoveryLifecycle() {
        for (boolean fail : List.of(false,true)) {
            var request = new CompletableFuture<Optional<LandmarkMetadata>>();
            var diagnostic = new SourceDiscoveryAttempt();
            var initial = new InitialSourceDiscovery();
            var server = new ArrayDeque<Runnable>();
            diagnostic.begin(request);
            initial.await(() -> request, server::addLast, (found,error) -> {
                check(initial.groundPending(false), "walking permission spans owner-application callback");
                check(diagnostic.complete(request,found==null || found.isEmpty(),error), "diagnostic applies the exact request once");
            });
            if (fail) request.completeExceptionally(new IllegalStateException("source unavailable"));
            else request.complete(Optional.empty());
            check(request.isDone() && diagnostic.pending() && initial.groundPending(false),
                    "worker completion alone settles neither displayed state nor pending walking");
            check(server.size()==1,"single completion is marshalled to the server");
            server.removeFirst().run();
            check(!diagnostic.pending() && !initial.groundPending(false), "applied terminal state releases both trackers");
            check(fail ? !diagnostic.error().isEmpty() : diagnostic.empty(), "failure/unowned diagnostics remain truthful");
            check(!diagnostic.complete(request,false,null),"a delayed duplicate cannot replace the terminal result");
        }
    }

    private static void productionAdapterUsesTheDiscoveryFuture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String terrainPath = "src/main/java/io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService.java";
        while (root != null && !Files.exists(root.resolve(terrainPath))) root = root.getParent();
        check(root != null, "could not locate production source root");
        String terrain = Files.readString(root.resolve(terrainPath));
        check(terrain.contains("w.initialDiscovery.groundPending(ownershipPendingAt(p,w,source))")
                        && terrain.contains("expected.initialDiscovery.await(()->future,p.getServer(),")
                        && terrain.contains("s.discovery.begin(future)")
                        && terrain.contains("s.discovery.complete(future,"),
                "production must combine exact floor ownership with server-applied discovery lifecycle");
        check(terrain.contains("if(w!=s.local") && terrain.contains("windowFor(s,cell)"),
                "pending ground must require the accepted local producer, never an unrelated representative");
        String nav = Files.readString(root.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java"));
        check(nav.contains("boolean pending = SpiritTerrainService.groundPending(p)"),
                "production shallow stance must use exact-ground discovery/ownership state");
    }

    public static void main(String[] args) throws Exception {
        emptySnapshotBeforeDiscoveryKeepsBoundedWalking();
        terminalDiscoveryAndOwnershipRefresh();
        combinedDiscoveryLifecycle();
        productionAdapterUsesTheDiscoveryFuture();
        System.out.println("EntrySupportDiscoveryTest: " + checks + " checks passed (real empty ownership + future ordering + mesh contact)");
    }
}
