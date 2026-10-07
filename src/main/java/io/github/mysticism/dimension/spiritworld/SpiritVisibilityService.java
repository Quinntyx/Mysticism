package io.github.mysticism.dimension.spiritworld;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.embedding.EmbeddingProfile;
import io.github.mysticism.net.SpiritDeltaPayload;
import io.github.mysticism.vector.*;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Tick only copies player vectors, polls bounded CPU work and sends <=128 member deltas.
 * Catalogue scans/clustering are off-thread, with no world, registry, model IO or inference. */
public final class SpiritVisibilityService {
    private SpiritVisibilityService() {}
    private static boolean initialized;
    private static final Map<MinecraftServer, Runtime> SERVERS = new IdentityHashMap<>();
    private static final class Viewer {
        List<SpiritGlyphSelection.Glyph> selected = List.of();
        final Set<String> sent = new TreeSet<>();
        String protocolFailure;
        boolean fullSnapshotRequired;
        CompletableFuture<List<SpiritGlyphSelection.Glyph>> query;
    }
    static final class Runtime implements AutoCloseable {
        final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), r -> { var t = new Thread(r, "Mysticism-GlyphSelection"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        final Map<UUID, Viewer> viewers = new HashMap<>();
        KnnIndex index;
        int size, ticks;
        CompletableFuture<SpiritGlyphSelection> building;
        SpiritGlyphSelection catalogue;
        /** Unlike supplyAsync, cancelling this future also removes its actual queued runnable. */
        static final class Job<T> extends CompletableFuture<T> implements Runnable {
            final ThreadPoolExecutor owner; final Supplier<T> supplier;
            Job(ThreadPoolExecutor owner, Supplier<T> supplier) { this.owner = owner; this.supplier = supplier; }
            @Override public boolean cancel(boolean interrupt) { owner.remove(this); return super.cancel(false); }
            @Override public void run() {
                if (isDone()) return;
                try { complete(supplier.get()); } catch (Throwable error) { completeExceptionally(error); }
            }
        }
        <T> Job<T> submit(Supplier<T> supplier) {
            Job<T> job = new Job<>(worker, supplier);
            try { worker.execute(job); return job; }
            catch (RejectedExecutionException full) { job.cancel(false); return null; }
        }
        void refreshCatalogue(KnnIndex next) {
            int nextSize = next.size();
            if (next == index && nextSize == size) return;
            if (building != null) building.cancel(false);
            building = null; catalogue = null;
            viewers.values().forEach(v -> {
                if (v.query != null) v.query.cancel(false);
                v.query = null; v.selected = List.of();
            });
            if (nextSize > SpiritGlyphSelection.MAX_CANDIDATES) {
                index = next; size = nextSize; return; // Explicit oversized-generation fail-closed.
            }
            Job<SpiritGlyphSelection> accepted = submit(() -> {
                var snapshot = new TreeMap<String, Vec384f>();
                synchronized (next) {
                    if (next.size() > SpiritGlyphSelection.MAX_CANDIDATES)
                        throw new IllegalArgumentException("Glyph catalogue grew beyond budget");
                    next.forEach((id, vector) -> {
                        if (snapshot.size() >= SpiritGlyphSelection.MAX_CANDIDATES)
                            throw new IllegalArgumentException("Glyph catalogue grew beyond budget");
                        snapshot.put(id, vector);
                    });
                }
                return new SpiritGlyphSelection(snapshot, EmbeddingProfile.current());
            });
            // Never acknowledge a generation until executor admission succeeds.
            if (accepted == null) { index = null; size = -1; return; }
            index = next; size = nextSize; building = accepted;
        }
        void pollCatalogue() {
            if (building == null || !building.isDone()) return;
            try { catalogue = building.getNow(null); }
            catch (CompletionException | CancellationException unavailable) {
                catalogue = null; index = null; size = -1; // Failed build must retry as well.
            }
            building = null;
        }
        @Override public void close() {
            if (building != null) building.cancel(false);
            viewers.values().forEach(v -> { if (v.query != null) v.query.cancel(false); });
            viewers.clear(); catalogue = null; worker.shutdownNow();
        }
    }

    public static boolean isSpiritWorld(ServerPlayerEntity p) {
        return p != null && p.getWorld().getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"));
    }

    public static void tick(MinecraftServer server, int requestedLimit) {
        if (server.isStopping()) return;
        Runtime runtime = SERVERS.computeIfAbsent(server, s -> new Runtime());
        runtime.ticks++;
        // Existing wave-1 index is generation-replaced, synchronized and returns cloned snapshots.
        var state = ItemEmbeddingIndexState.get(server);
        KnnIndex index = state.getIndex();
        if (runtime.ticks % 20 == 1) runtime.refreshCatalogue(index);
        runtime.pollCatalogue();
        var connected = new HashSet<UUID>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid(); connected.add(id);
            if (!isSpiritWorld(player)) {
                Viewer old = runtime.viewers.remove(id);
                if (old != null) {
                    if (old.query != null) old.query.cancel(false);
                    send(player, old, List.of());
                }
                continue;
            }
            Viewer viewer = runtime.viewers.computeIfAbsent(id, ignored -> new Viewer());
            Vec384f current = player.getComponent(MysticismEntityComponents.LATENT_POS).get();
            boolean valid = true;
            try { EmbeddingSpace.requireCurrent(current); valid = current.length() > 0; }
            catch (RuntimeException invalid) { valid = false; }
            if (!valid || runtime.catalogue == null) {
                if (viewer.query != null) viewer.query.cancel(false);
                viewer.query = null; viewer.selected = List.of(); send(player, viewer, List.of()); continue;
            }
            if (viewer.query != null && viewer.query.isDone()) {
                try { viewer.selected = viewer.query.getNow(List.of()); }
                catch (CompletionException | CancellationException unavailable) { viewer.selected = List.of(); }
                viewer.query = null;
            }
            // Re-gate <=128 results against CURRENT, never the asynchronous/target attunement.
            var visible = new ArrayList<SpiritGlyphSelection.Glyph>();
            for (var glyph : viewer.selected)
                if (glyph.embedding().squareDistance(current) < SpiritGlyphSelection.SEMANTIC_RADIUS * SpiritGlyphSelection.SEMANTIC_RADIUS)
                    visible.add(glyph);
            send(player, viewer, visible);
            if (viewer.query == null && runtime.ticks % 10 == 1) {
                var catalogue = runtime.catalogue;
                var query = current.clone();
                var previous = viewer.selected;
                viewer.query = runtime.submit(() ->
                        catalogue.select(query, EmbeddingProfile.current(), requestedLimit, previous));
                // Rejection is bounded backpressure: retain/gate existing members, retry next interval.
            }
        }
        runtime.viewers.entrySet().removeIf(e -> {
            if (connected.contains(e.getKey())) return false;
            if (e.getValue().query != null) e.getValue().query.cancel(false);
            return true;
        });
    }

    private static void send(ServerPlayerEntity player, Viewer viewer, List<SpiritGlyphSelection.Glyph> selected) {
        if (!isSpiritWorld(player)) { viewer.sent.clear(); return; }
        var frame = io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService.projectionFrame(player.getServer());
        if (frame.isEmpty()) return; // Terrain initializes asynchronously; no invented fallback frame.
        try {
            boolean bootstrap = io.github.mysticism.net.SpiritProjectionService.activate(player, frame.get());
            if (bootstrap) viewer.fullSnapshotRequired = true;
            boolean full = viewer.fullSnapshotRequired;
            var current = new TreeSet<String>();
            var added = new ArrayList<SpiritDeltaPayload.Added>();
            for (var glyph : selected) {
                current.add(glyph.id());
                if (full || !viewer.sent.contains(glyph.id()))
                    added.add(SpiritDeltaPayload.Added.of(glyph.id(), glyph.embedding()));
            }
            var removed = new ArrayList<String>();
            if (!full) for (String id : viewer.sent) if (!current.contains(id)) removed.add(id);
            if (!added.isEmpty() || !removed.isEmpty())
                io.github.mysticism.net.SpiritProjectionService.send(player, added, removed);
            // Publication succeeded. A failed bootstrap/delta must never advance this snapshot.
            viewer.sent.clear(); viewer.sent.addAll(current); viewer.protocolFailure = null;
            viewer.fullSnapshotRequired = false;
        } catch (RuntimeException unavailable) {
            String message = unavailable.getMessage() == null ? unavailable.getClass().getSimpleName() : unavailable.getMessage();
            if (!Objects.equals(message, viewer.protocolFailure)) {
                viewer.protocolFailure = message;
                player.sendMessage(net.minecraft.text.Text.literal("[Spirit projection] " + message), true);
            }
        }
    }

    private static void forget(MinecraftServer server, ServerPlayerEntity player, boolean notify) {
        Runtime runtime = SERVERS.get(server);
        if (runtime == null) return;
        Viewer old = runtime.viewers.remove(player.getUuid());
        if (old == null) return;
        if (old.query != null) old.query.cancel(false);
        if (notify) send(player, old, List.of());
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick(server, SpiritGlyphSelection.MAX_VISIBLE));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(server, handler.player, false));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                forget(destination.getServer(), player, true));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                forget(newPlayer.getServer(), newPlayer, true));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Runtime runtime = SERVERS.remove(server);
            if (runtime != null) runtime.close();
        });
    }
}
