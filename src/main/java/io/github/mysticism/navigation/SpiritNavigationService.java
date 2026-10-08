package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.component.*;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.landmark.*;
import io.github.mysticism.net.SpiritScenePayload;
import io.github.mysticism.vector.*;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual per-player mode/flight/target controller. Terrain owns meshes, source mapping and collision. */
public final class SpiritNavigationService {
    private static final Map<MinecraftServer, Map<UUID, Session>> SERVERS = new IdentityHashMap<>();
    private static boolean initialized;
    /** Terrain-owned validation; install only with no-snap acquisition and coherent visible/collision frames. */
    public interface LandingSafety {
        boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
        boolean ready(ServerPlayerEntity player, String dimension, String landmarkId, BlockPos block);
    }
    private static LandingSafety landingSafety;
    public static void installLandingSafety(LandingSafety safety) { landingSafety = Objects.requireNonNull(safety); }
    private SpiritNavigationService() {}
    private static final class Session {
        int unsupported, blendTick, landingTick;
        boolean attemptedLanding, semanticReady, checkedRestore, warnedAnchor, prefetched, confirmedOwned, warnedLanding, jumping;
        Vec384f targetSnapshot;
        Basis384f blendFrom, blendTo, landingFrom;
        CompletableFuture<?> capture;
        String captureDimension = "";
        long captureNonce;
    }
    private static Session session(ServerPlayerEntity p) {
        return SERVERS.computeIfAbsent(p.getServer(), s -> new HashMap<>()).computeIfAbsent(p.getUuid(), id -> new Session());
    }
    private static SpiritNavigation state(ServerPlayerEntity p) { return p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION); }
    private static boolean spirit(ServerPlayerEntity p) { return p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD); }
    public static boolean deep(ServerPlayerEntity p) { return spirit(p) && state(p).active() && state(p).deep(); }

    public static void init() {
        if (initialized) return; initialized = true;
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, from, to) -> {
            if (!to.getRegistryKey().equals(SpiritTerrainService.WORLD)) deactivate(p);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((old, p, alive) -> {
            clear(p); if (!spirit(p)) deactivate(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.player));
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            String dimension = world.getRegistryKey().getValue().toString();
            Map<UUID, Session> sessions = SERVERS.get(server);
            if (sessions != null) for (Session s : sessions.values()) {
                if (s.capture != null && (world.getRegistryKey().equals(SpiritTerrainService.WORLD) || s.captureDimension.equals(dimension))) {
                    ++s.captureNonce; s.capture.cancel(false);
                }
            }
            for (var p : server.getPlayerManager().getPlayerList())
                if (spirit(p) && state(p).active() && !state(p).deep() && state(p).sourceDimension().equals(dimension)) enterDeep(p);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Map<UUID, Session> sessions = SERVERS.remove(server);
            if (sessions != null) for (Session s : sessions.values()) if (s.capture != null) s.capture.cancel(false);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, damage) ->
                !(entity instanceof ServerPlayerEntity victim && deep(victim))
                        && !(source.getAttacker() instanceof ServerPlayerEntity attacker && deep(attacker)));
        // Explicit native allowlist. Source ghosts are client snapshots, not server spawned entities.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (world.getRegistryKey().equals(SpiritTerrainService.WORLD)
                    && !(entity instanceof PlayerEntity) && !(entity instanceof ItemEntity)) entity.discard();
        });
    }
    private static void clear(ServerPlayerEntity p) {
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session removed = sessions == null ? null : sessions.remove(p.getUuid());
        if (removed != null && removed.capture != null) removed.capture.cancel(false);
    }
    private static void sync(ServerPlayerEntity p) { MysticismEntityComponents.SPIRIT_NAVIGATION.sync(p); }
    private static void deactivate(ServerPlayerEntity p) {
        var nav = state(p);
        if (nav.hasSavedAbilities()) {
            p.getAbilities().allowFlying = nav.savedAllowFlying(); p.getAbilities().flying = nav.savedFlying();
            p.setNoGravity(nav.savedNoGravity()); nav.clearSavedAbilities(); p.sendAbilitiesUpdate();
        }
        if (nav.active()) { nav.setActive(false); sync(p); }
        clear(p);
    }
    private static void flight(ServerPlayerEntity p, boolean deep) {
        boolean changed = !p.getAbilities().allowFlying || p.getAbilities().flying != deep;
        p.getAbilities().allowFlying = true; p.getAbilities().flying = deep;
        p.setNoGravity(false); p.fallDistance = 0;
        if (changed) p.sendAbilitiesUpdate();
    }

    public static boolean enter(ServerPlayerEntity p) {
        init();
        if (spirit(p)) return false;
        var server = p.getServer(); var world = server.getWorld(SpiritTerrainService.WORLD);
        if (world == null) { p.sendMessage(Text.literal("Spirit dimension unavailable."), false); return false; }
        Vec3d source = p.getPos(); String dimension = p.getWorld().getRegistryKey().getValue().toString();
        var nav = state(p);
        nav.rememberAbilities(p.getAbilities().allowFlying, p.getAbilities().flying, p.hasNoGravity());
        if (!SpiritTerrainService.prepareEnter(p)) { deactivate(p); return false; }
        var mapped = SpiritTerrainService.sourcePosition(p);
        nav.shallow(dimension, mapped.map(SpiritTerrainService.SourcePosition::landmarkId).orElse(""), source);
        Session s = session(p); s.semanticReady = false; s.checkedRestore = true;
        nav.setSemanticReady(false); nav.setLandingApproach(false);
        s.confirmedOwned = false; s.warnedAnchor = false; s.prefetched = false;
        try {
            // Source-identical carrier pose: no shared origin, entry search or replacement floor.
            p.teleport(world, source.x, source.y, source.z, p.getYaw(), p.getPitch());
            SpiritTerrainService.setShallow(p, true); flight(p, false);
            p.setVelocity(Vec3d.ZERO); sync(p);
            if (nav.hasShallowTarget()) {
                SpiritTerrainService.prefetchTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetBlock(),
                        p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(), nav.targetBasis()); s.prefetched = true;
            }
            p.sendMessage(Text.literal("Shallow spirit: walk/jump normally; double-jump flies deep. /spirit leave exits current shallow location."), false);
            return true;
        } catch (RuntimeException failure) {
            SpiritTerrainService.cancelEnter(p); deactivate(p);
            p.sendMessage(Text.literal("Spirit entry failed: " + failure.getMessage()), false); return false;
        }
    }
    private static void anchorSource(ServerPlayerEntity p, Session s, SpiritTerrainService.SourcePosition source) {
        anchorSource(p, source, p.getComponent(MysticismEntityComponents.LATENT_BASIS).get());
    }
    /** Terrain calls this after actual async ownership publication, including while already flying deep.
     * source is the retained physical source pose; sourceBasis is its captured grid, NOT a later camera basis. */
    public static void anchorSource(ServerPlayerEntity p, SpiritTerrainService.SourcePosition source, Basis384f sourceBasis) {
        var nav = state(p); Session s = session(p);
        if (!spirit(p) || !nav.active() || s.semanticReady || source.landmarkId().isEmpty()
                || !source.dimension().equals(nav.sourceDimension())
                || (!nav.landmarkId().isEmpty() && !nav.landmarkId().equals(source.landmarkId()))) return;
        var found = LandmarkStore.get(p.getServer()).metadata(source.landmarkId());
        if (found.isEmpty()) return;
        var metadata = found.get();
        if (!metadata.header().dimension().equals(source.dimension()) || !metadata.header().bounds().contains(
                MathHelper.floor(source.position().x), MathHelper.floor(source.position().y), MathHelper.floor(source.position().z))) return;
        boolean wasDeep = nav.deep();
        nav.shallow(source.dimension(), source.landmarkId(), source.position());
        if (wasDeep) nav.enterDeep(); // Async discovery must not take flight away or silently land.
        s.semanticReady = true; nav.setSemanticReady(true);
        Vec384f q = metadata.header().baseEmbedding().vector();
        Vec3d offset = source.position().subtract(metadata.header().anchor().x(), metadata.header().anchor().y(), metadata.header().anchor().z());
        TraversalSteering.advance(q, sourceBasis, offset.x, offset.y, offset.z);
        p.getComponent(MysticismEntityComponents.LATENT_POS).set(q);
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
        att.set(att.target().length() < 1e-6 ? q : att.target()); // freeze any default personal target at entry
        MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p); MysticismEntityComponents.LATENT_POS.sync(p); sync(p);
    }
    private static boolean live(ServerPlayerEntity p, Session s) {
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        return sessions != null && sessions.get(p.getUuid()) == s
                && p.getServer().getPlayerManager().getPlayer(p.getUuid()) == p;
    }
    public static boolean exit(ServerPlayerEntity p) {
        if (!spirit(p) || !state(p).active() || state(p).deep()) {
            p.sendMessage(Text.literal("Exit requires a valid current shallow source location."), false); return false;
        }
        var support = SpiritTerrainService.support(p);
        if (support.isPresent() && !support.get().landmarkId().equals(state(p).landmarkId())) {
            enterDeep(p); p.sendMessage(Text.literal("The actual supporting region changed; cannot exit through the previous source binding."), false); return false;
        }
        if (!SpiritTerrainService.exit(p)) {
            p.sendMessage(Text.literal("Current shallow source location is unavailable or obstructed; no substitute exit."), false); return false;
        }
        deactivate(p); return true;
    }
    public static void enterDeep(ServerPlayerEntity p) {
        if (!spirit(p)) return;
        var permissions = state(p);
        if (!permissions.hasSavedAbilities()) permissions.rememberAbilities(p.getAbilities().allowFlying, p.getAbilities().flying, p.hasNoGravity());
        Session s = session(p); restoreAnchor(p, s);
        var nav = state(p); boolean changed = !nav.active() || !nav.deep();
        // Flight safety never waits for a model. Only semantic travel/landing/touch require a real anchor.
        nav.enterDeep(); SpiritTerrainService.setShallow(p, false); flight(p, true);
        s.unsupported = 0; s.jumping = false;
        if (changed) sync(p);
        if (!s.semanticReady && !s.warnedAnchor) {
            p.sendMessage(Text.literal("Free flight active; semantic travel awaits real source discovery."), false); s.warnedAnchor = true;
        }
    }

    /** Called once by the evolver. True permits ordinary deep movement integration. */
    public static boolean update(ServerPlayerEntity p, Vec3d delta) {
        if (!spirit(p)) { if (state(p).active() || state(p).hasSavedAbilities()) deactivate(p); return false; }
        var nav = state(p); Session s = session(p); restoreAnchor(p, s);
        if (nav.hasShallowTarget() && !s.prefetched) {
            SpiritTerrainService.prefetchTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetBlock(),
                    p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(), nav.targetBasis()); s.prefetched = true;
        }
        if (!nav.active()) enterDeep(p); // Safe freeflight even without an anchor; never invent a semantic vector.
        if (!nav.deep()) {
            if (p.getAbilities().flying) { enterDeep(p); return nav.deep() && s.semanticReady; }
            var mapping = SpiritTerrainService.sourcePosition(p);
            var support = SpiritTerrainService.support(p);
            if (mapping.isEmpty()) { enterDeep(p); return nav.deep() && s.semanticReady; }
            var source = mapping.get(); boolean anchoredNow = false;
            String id = source.landmarkId();
            // Permit the initial unowned frame to catch up with asynchronous ownership publication.
            // Once geometry actually confirms ownership, leaving it enters deep, never switches regions.
            if (!nav.landmarkId().isEmpty() && !id.equals(nav.landmarkId()) && (!id.isEmpty() || s.confirmedOwned)) {
                enterDeep(p); return nav.deep() && s.semanticReady;
            }
            if (!id.isEmpty()) s.confirmedOwned = true;
            if (id.isEmpty() && !s.confirmedOwned) id = nav.landmarkId();
            // A retained local window is NOT proof that the floor belongs to it. Blank matches blank only.
            if (support.isPresent() && !support.get().landmarkId().equals(id)) {
                enterDeep(p); return nav.deep() && s.semanticReady;
            }
            // Ownership checks precede initial anchoring; discovery cannot overwrite an established binding.
            if (!s.semanticReady && !source.landmarkId().isEmpty()) { anchorSource(p, s, source); anchoredNow = s.semanticReady; }
            nav.shallow(source.dimension(), id, source.position());
            if (support.isPresent()) { s.unsupported = 0; s.jumping = false; }
            else {
                if (s.unsupported == 0) s.jumping = delta.y > .01;
                if (++s.unsupported > 14 || !s.jumping) { enterDeep(p); return nav.deep() && s.semanticReady; }
            } // Ascending takeoff gets ordinary jump grace; walking over an edge gets immediate freeflight.
            flight(p, false);
            if (s.semanticReady && !anchoredNow) TraversalSteering.advance(p.getComponent(MysticismEntityComponents.LATENT_POS).get(),
                    p.getComponent(MysticismEntityComponents.LATENT_BASIS).get(), delta.x, delta.y, delta.z);
            if (p.getServer().getTicks() % 4 == 0) sync(p);
            return false;
        }
        flight(p, true);
        if (!s.semanticReady) return false;
        if (s.blendTo != null) {
            if (delta.lengthSquared() > 1e-5) { s.blendFrom = null; s.blendTo = null; }
            else {
                float fraction = ++s.blendTick / 20f;
                p.getComponent(MysticismEntityComponents.LATENT_BASIS).set(TraversalSteering.blend(s.blendFrom, s.blendTo, fraction));
                if (fraction >= 1) { s.blendFrom = null; s.blendTo = null; }
                return false;
            }
        }
        if (attemptLanding(p, s, delta)) return false; // Approach advanced q once, without ordinary basis steering.
        return nav.deep();
    }
    private static void restoreAnchor(ServerPlayerEntity p, Session s) {
        if (s.checkedRestore) return; s.checkedRestore = true;
        var nav = state(p); Vec384f q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
        // Physical pose survives model reset, but discarded q/IDs cannot authorize semantic travel.
        s.semanticReady = nav.active() && nav.semanticReady();
        if (nav.modelCompatible() && nav.active() && nav.deep() && q.length() > 1e-6) s.semanticReady = true;
        if (nav.modelCompatible() && nav.active() && !nav.landmarkId().isEmpty()
                && LandmarkStore.get(p.getServer()).metadata(nav.landmarkId()).isPresent()) s.semanticReady = true;
        if (nav.semanticReady() != s.semanticReady) { nav.setSemanticReady(s.semanticReady); sync(p); }
        // Terrain restores its source window and runs initial discovery; no duplicate entry extraction here.
    }
    /** Called only when a debug command supplies a ready real item location, not a fabricated fallback. */
    public static void anchorFromConcept(ServerPlayerEntity p) {
        if (p.getComponent(MysticismEntityComponents.LATENT_POS).get().length() > 1e-6) {
            Session s = session(p); s.semanticReady = true; s.checkedRestore = true;
            state(p).setSemanticReady(true); sync(p);
        }
    }
    private static void endApproach(ServerPlayerEntity p, Session s) {
        s.landingFrom = null; s.landingTick = 0;
        if (state(p).landingApproach()) { state(p).setLandingApproach(false); sync(p); }
    }
    /** True means this controller already integrated physical movement for this tick. Never snaps q/pose/basis. */
    private static boolean attemptLanding(ServerPlayerEntity p, Session s, Vec3d delta) {
        var nav = state(p);
        if (!nav.hasShallowTarget()) { endApproach(p, s); return false; }
        Vec384f target = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        if (s.targetSnapshot == null || s.targetSnapshot.squareDistance(target) > 0) {
            s.targetSnapshot = target; s.attemptedLanding = false; s.warnedLanding = false; endApproach(p, s);
        }
        var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
        float distance = q.squareDistance(target);
        if (distance > .15f * .15f) { s.attemptedLanding = false; s.warnedLanding = false; }
        if (distance > .035f * .035f || s.attemptedLanding) { endApproach(p, s); return false; }
        LandingSafety safety = landingSafety;
        if (safety == null) {
            endApproach(p, s);
            if (!s.warnedLanding) { p.sendMessage(Text.literal("Landing remains deep: continuous terrain-transition guard is not installed."), false); s.warnedLanding = true; }
            return false;
        }
        var component = p.getComponent(MysticismEntityComponents.LATENT_BASIS);
        Basis384f destination = nav.targetBasis();
        final float acquisitionDistanceSquared = .0005f * .0005f;
        // Keep ordinary movement-dependent pursuit until the FULL residual is inside the final band.
        // Then use bounded movement-driven convergence, not source-basis-only translation: arbitrary
        // continued movement must not recreate an unreachable perpendicular residual during alignment.
        if (distance > acquisitionDistanceSquared) { endApproach(p, s); return false; }
        if (s.landingFrom == null) { s.landingFrom = component.get().clone(); s.landingTick = 0; }
        if (!nav.landingApproach()) { nav.setLandingApproach(true); sync(p); }
        Basis384f proposed = s.landingTick < 40
                ? TraversalSteering.blend(s.landingFrom, destination, (s.landingTick + 1) / 40f) : component.get();
        // Reject a singular/antipodal interpolation jump rather than forcing the final basis.
        Basis384f before = component.get();
        if (before.i.squareDistance(proposed.i) > .01f || before.j.squareDistance(proposed.j) > .01f
                || before.k.squareDistance(proposed.k) > .01f) {
            s.attemptedLanding = true; endApproach(p, s);
            p.sendMessage(Text.literal("Captured source-grid alignment cannot transition continuously; remaining deep nearby."), false);
            return false;
        }
        Vec384f original = q.clone(), candidate = q.clone();
        TraversalSteering.approachStep(candidate, target, delta.x, delta.y, delta.z);
        // The real terrain guard reads q from the component. Preview the bounded candidate on this
        // server thread, restoring the ORIGINAL value/reference even if validation throws. No sync,
        // event or publication occurs here; the guard must remain a query. Never assign target to q.
        boolean clear;
        q.converge(candidate, 1);
        try { clear = safety.canAlign(p, proposed); }
        finally { q.converge(original, 1); }
        if (!clear) { endApproach(p, s); return false; } // normal deep pursuit remains available
        component.set(proposed); q.converge(candidate, 1);
        if (s.landingTick < 40) ++s.landingTick;
        distance = q.squareDistance(target);
        // 3.36 blocks is eligibility radius, not permission to replace q or jump onto another floor.
        // Acquisition requires <=4.8 cm semantic residual and an already-aligned, visible real target floor.
        Basis384f current = component.get();
        if (s.landingTick < 40 || distance > acquisitionDistanceSquared
                || current.i.squareDistance(destination.i) > 1e-8f || current.j.squareDistance(destination.j) > 1e-8f
                || current.k.squareDistance(destination.k) > 1e-8f) return true;
        var support = SpiritTerrainService.support(p);
        if (support.isEmpty() || !support.get().landmarkId().equals(nav.targetLandmarkId())
                || support.get().normal().y < .99 || !safety.ready(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetBlock())) return true;
        s.attemptedLanding = true;
        // Parent's terrain acquisition must preserve CURRENT carrier pose and commit only its ready source mapping.
        if (SpiritTerrainService.tryLandTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetBlock())) {
            var source = SpiritTerrainService.sourcePosition(p);
            if (source.isPresent()) {
                var at = source.get(); nav.shallow(at.dimension(), at.landmarkId(), at.position()); s.unsupported = 0;
                endApproach(p, s); SpiritBasisEvolver.resetMotion(p); flight(p, false);
                MysticismEntityComponents.LATENT_BASIS.sync(p); MysticismEntityComponents.LATENT_POS.sync(p); sync(p);
            }
        } else { endApproach(p, s); p.sendMessage(Text.literal("Captured destination changed or blocked. Remaining deep nearby."), false); }
        return true;
    }
    public static Optional<SpiritScenePayload.Binding> binding(ServerPlayerEntity p) {
        var nav = state(p); if (!spirit(p) || !nav.active() || nav.deep()) return Optional.empty();
        Session s = session(p);
        // An unowned source binding has no semantic anchor yet. ZERO is explicitly unavailable
        // binding metadata, never passed to the deep evolver/semantic selection as a fabricated embedding.
        Vec384f captured = s.semanticReady ? p.getComponent(MysticismEntityComponents.LATENT_POS).get().clone() : Vec384f.ZERO();
        return SpiritTerrainService.sourcePosition(p)
                .map(source -> new SpiritScenePayload.Binding(source.dimension(), source.landmarkId(), source.position(), captured));
    }

    public static void captureTarget(ServerPlayerEntity p, String dimension, String id, BlockPos block, Vec384f embedding) {
        captureTarget(p, dimension, id, block, embedding, new Basis384f());
    }
    public static void captureTarget(ServerPlayerEntity p, String dimension, String id, BlockPos block, Vec384f embedding, Basis384f basis) {
        EmbeddingSpace.requireCurrent(embedding);
        cancelCapture(p);
        state(p).target(dimension, id, block, basis);
        p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).set(embedding.clone());
        sync(p); MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p);
        Session s = session(p); s.attemptedLanding = false; s.prefetched = true; s.warnedLanding = false;
        endApproach(p, s);
        if (spirit(p)) SpiritTerrainService.prefetchTarget(p, dimension, id, block, embedding, basis);
        else s.prefetched = false;
    }
    public static boolean captureHere(ServerPlayerEntity p) {
        var nav = state(p);
        if (spirit(p) && (!nav.active() || nav.deep())) {
            p.sendMessage(Text.literal("Capture here requires the source world or a valid shallow location."), false); return false;
        }
        String dimension = spirit(p) ? nav.sourceDimension() : p.getWorld().getRegistryKey().getValue().toString();
        Vec3d position = spirit(p) ? nav.sourcePosition() : p.getPos();
        return captureSource(p, dimension, BlockPos.ofFloored(position), "");
    }
    /** A newer explicit target supersedes queued source discovery, including an already-queued callback. */
    public static void cancelCapture(ServerPlayerEntity p) {
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session s = sessions == null ? null : sessions.get(p.getUuid());
        if (s != null) { ++s.captureNonce; if (s.capture != null) s.capture.cancel(false); }
    }
    /** Debug source-coordinate capture, asynchronously verifies a real source landmark. */
    public static boolean captureSource(ServerPlayerEntity p, String dimension, BlockPos block, String requiredId) {
        Session s = session(p);
        if (s.capture != null && !s.capture.isDone()) { p.sendMessage(Text.literal("Source discovery already pending."), false); return false; }
        var server = p.getServer();
        // Snapshot the source-grid basis before async completion, not a later moving deep basis.
        Basis384f basis = state(p).active() && !state(p).deep() ? p.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone() : new Basis384f();
        s.captureDimension = dimension; long nonce = ++s.captureNonce;
        s.capture = SourceLandmarks.ensureSourceLocation(server, dimension, block).whenComplete((found, error) -> server.execute(() -> {
            if (!live(p, s) || s.captureNonce != nonce) return;
            if (error != null || found == null || found.isEmpty()) { p.sendMessage(Text.literal("No captured source landmark available."), false); return; }
            var metadata = found.get();
            if (!requiredId.isEmpty() && !LandmarkStore.get(server).resolve(requiredId).equals(metadata.id())) {
                p.sendMessage(Text.literal("Requested landmark does not own the captured source location."), false); return;
            }
            Vec384f captured = metadata.header().baseEmbedding().vector();
            TraversalSteering.advance(captured, basis, block.getX() + .5 - metadata.header().anchor().x(),
                    block.getY() - metadata.header().anchor().y(), block.getZ() + .5 - metadata.header().anchor().z());
            captureTarget(p, dimension, metadata.id(), block, captured, basis);
            p.sendMessage(Text.literal("Captured " + metadata.id() + " at " + dimension + " " + block.toShortString() + " (vector snapshot)."), false);
        }));
        p.sendMessage(Text.literal("Capturing generated source landmark asynchronously…"), false); return true;
    }
    /** Caller validates BOTH projected reach/alignment/collision rays before invoking. */
    public static void touch(ServerPlayerEntity actor, ServerPlayerEntity target) {
        if (actor == target || !deep(actor) || !deep(target) || !state(actor).semanticReady() || !state(target).semanticReady()
                || actor.getServer() != target.getServer()
                || actor.getWorld() != target.getWorld()) return;
        Session s = session(target); endApproach(target, s);
        s.blendFrom = target.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone();
        s.blendTo = actor.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone(); s.blendTick = 0;
        // Target vector and semantic q deliberately unchanged. Network authenticates/validates the touch.
    }
}
