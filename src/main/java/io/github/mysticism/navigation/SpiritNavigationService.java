package io.github.mysticism.navigation;

import io.github.mysticism.activity.TraversalSteering;
import io.github.mysticism.activity.SpiritActivityService;
import io.github.mysticism.component.*;
import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import io.github.mysticism.dimension.spiritworld.SpiritBasisEvolver;
import io.github.mysticism.landmark.*;
import io.github.mysticism.net.SpiritScenePayload;
import io.github.mysticism.net.SpiritFlightCorrectionPayload;
import io.github.mysticism.vector.*;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual per-player mode/flight/target controller. Terrain owns meshes, source mapping and collision. */
public final class SpiritNavigationService {
    private static final int WALK_BUDGET_TICKS = 200;
    private static final int SUPPORT_ALIGN_TICKS = 40;
    /** After a failed walk request, repeated flight toggles are refused instead of restarting terrain validation. */
    private static final long WALK_RETRY_COOLDOWN_TICKS = 60;
    /** Identical walk feedback inside this window is suppressed; new/changed information always shows. */
    private static final long FEEDBACK_WINDOW_TICKS = 40;
    private static final Map<MinecraftServer, Map<UUID, Session>> SERVERS = new IdentityHashMap<>();
    private static boolean initialized;
    /** Terrain-owned validation; install only with no-snap acquisition and coherent visible/collision frames. */
    public interface LandingSafety {
        boolean canAlign(ServerPlayerEntity player, Basis384f proposedBasis);
        boolean ready(ServerPlayerEntity player, String dimension, String landmarkId, BlockPos block);
        default boolean ready(ServerPlayerEntity player, String dimension, String landmarkId, Vec3d foot) {
            BlockPos block = BlockPos.ofFloored(foot);
            // Legacy adapters can validate only their exact integer-Y / centered-XZ contract.
            return foot.equals(new Vec3d(block.getX() + .5, block.getY(), block.getZ() + .5))
                    && ready(player, dimension, landmarkId, block);
        }
    }
    private static LandingSafety landingSafety;
    public static void installLandingSafety(LandingSafety safety) { landingSafety = Objects.requireNonNull(safety); }
    /** How far below a descending actor an approach may discover the support it will land on, so
     * guarded source-grid alignment and descent proof prewarming run BEFORE actual contact. */
    private static final double WALK_APPROACH_DEPTH = 24;
    private SpiritNavigationService() {}
    static final class Session {
        int unsupported, blendTick, landingTick, supportAlignTick, supportStall, acquireFailures;
        long acquireRetry, supportStartedTick;
        boolean attemptedLanding, semanticReady, checkedRestore, warnedAnchor, prefetched, confirmedOwned, warnedLanding, supportPending;
        /** Feet height of the last mesh-supported shallow tick; NaN until support is first measured. */
        double supportY = Double.NaN;
        /** Bounded self-heal state for a lost carrier session (entry-failure recovery). */
        long restoreNextTick; int restoreFailures;
        Boolean handedDeep; // null = navigation never handed this session off; else last handed mode.
        final AcceptedPlayerMovement movement = new AcceptedPlayerMovement();
        int supportProgressTicks, restingTicks, entrySupportTicks;
        WalkIntent walkIntent;
        boolean airborne, naturalSupport;
        final WalkRequestClock walk = new WalkRequestClock();
        final WalkingModePolicy.Alignment alignment = new WalkingModePolicy.Alignment();
        final WalkIntentTracker gestures = new WalkIntentTracker();
        Vec384f targetSnapshot, supportTargetSnapshot;
        Basis384f supportFrom, supportGrid;
        long supportWindow;
        String supportId = "", supportDimension = "";
        String lastTransition = "";
        Basis384f blendFrom, blendTo, landingFrom;
        CompletableFuture<?> capture;
        String captureDimension = "";
        long captureNonce;
        final FeedbackGate feedback = new FeedbackGate(FEEDBACK_WINDOW_TICKS);
        final WalkRetryGate walkRetry = new WalkRetryGate(WALK_RETRY_COOLDOWN_TICKS);
    }
    private static Session session(ServerPlayerEntity p) {
        return SERVERS.computeIfAbsent(p.getServer(), s -> new HashMap<>()).computeIfAbsent(p.getUuid(), id -> new Session());
    }
    private static SpiritNavigation state(ServerPlayerEntity p) { return p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION); }
    private static boolean spirit(ServerPlayerEntity p) { return p.getWorld().getRegistryKey().equals(SpiritTerrainService.WORLD); }
    public static boolean deep(ServerPlayerEntity p) { return spirit(p) && state(p).active() && state(p).deep(); }
    /** Vanilla exempts creative flight from the moved-wrongly snap-back because the client owns its
     *  flight prediction. Deep spirit flight is creative-like flight through per-player ROTATING
     *  observer-local geometry whose authoritative mesh frame necessarily lags client prediction
     *  (publication cadence plus network latency), so divergent collision outcomes are expected and
     *  a divergent move must be accepted like vanilla creative movement, never rubber-banded back.
     *  Shallow walking on static source-identical terrain keeps ordinary vanilla validation. */
    public static boolean flightPredictionTolerance(boolean spiritWorld, boolean active, boolean deep) {
        return spiritWorld && active && deep;
    }
    public static boolean flightPredictionTolerance(ServerPlayerEntity p) {
        return spirit(p) && state(p).active() && state(p).deep();
    }

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
            for (var p : server.getPlayerManager().getPlayerList()) {
                Session s = sessions == null ? null : sessions.get(p.getUuid());
                if (s != null && (s.supportPending || s.walk.armed()) && (world.getRegistryKey().equals(SpiritTerrainService.WORLD)
                        || s.supportDimension.isEmpty() || s.supportDimension.equals(dimension))) {
                    endSupportApproach(p, s);
                    feedback(p, s, true, "Walk request cancelled by source/dimension unload; remaining deep.");
                }
                if (spirit(p) && state(p).active() && !state(p).deep() && state(p).sourceDimension().equals(dimension)) enterDeep(p, "Source dimension unloaded");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Map<UUID, Session> sessions = SERVERS.remove(server);
            if (sessions != null) for (Session s : sessions.values()) if (s.capture != null) s.capture.cancel(false);
            for (var p : server.getPlayerManager().getPlayerList()) {
                SpiritTerrainService.cancelCurrentSupport(p); state(p).setSupportApproach(false);
            }
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
        SpiritTerrainService.cancelCurrentSupport(p); // also release proof retained after expiry
        // Pending support ownership/alignment is session-local, never resumed from a saved/wire flag.
        state(p).setSupportApproach(false);
        state(p).setLandingApproach(false);
    }
    /** Repeated entry starts a brand-new movement/semantic lifecycle; no previous visit state survives. */
    private static Session freshVisit(ServerPlayerEntity p) {
        Map<UUID, Session> sessions = SERVERS.computeIfAbsent(p.getServer(), s -> new HashMap<>());
        Session previous = sessions.put(p.getUuid(), new Session());
        if (previous != null) {
            if (previous.capture != null) previous.capture.cancel(false);
            releaseSupportResources(previous, () -> SpiritTerrainService.cancelCurrentSupport(p));
        }
        return sessions.get(p.getUuid());
    }
    private static void sync(ServerPlayerEntity p) { MysticismEntityComponents.SPIRIT_NAVIGATION.sync(p); }

    /** Deduplicated player feedback: an identical message inside the window is suppressed instead of repeated. */
    private static void feedback(ServerPlayerEntity p, Session s, boolean actionbar, String message) {
        if (!s.feedback.allow(p.getServer().getTicks(), message)) return;
        p.sendMessage(Text.literal(message), actionbar);
    }

    private static void notice(ServerPlayerEntity p,Session s,String message,boolean overlay) {
        s.lastTransition=message;feedback(p,s,overlay,message);
    }
    public static boolean retrySourceDiscovery(ServerPlayerEntity p) {
        if(!spirit(p)){notice(p,session(p),"Source retry requires spirit mode; use /spirit enter here.",false);return false;}
        return SpiritTerrainService.retryDiscovery(p);
    }
    /** A failed walk request arms the retry cooldown and reports once; /spirit describes the cooldown. */
    private static void walkFailed(ServerPlayerEntity p, Session s, String message) {
        s.walkRetry.failed(p.getServer().getTicks());
        feedback(p, s, true, message);
    }

    /** Merged walk-acceptance contract: an explicit walk request is refused while an approach is
     * pending or the failed-request cooldown is armed. Natural landings NEVER consult this gate —
     * they are physical resting events decided by {@link NaturalLandingPolicy} alone, so an expired
     * or failed walk intent can never leave a settling player stuck in flight above support. */
    public static boolean explicitWalkRequestAccepted(boolean supportPending, WalkRetryGate gate, long ticks) {
        return !supportPending && gate.canRequest(ticks);
    }

    /** Real current navigation state for command responses; nothing inferred or fabricated. */
    public static String status(ServerPlayerEntity p) {
        var nav = state(p); Session s = session(p);
        boolean pending = s.supportPending || nav.supportApproach();
        var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
        var target = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        var source = nav.sourcePosition();
        return NavigationReport.snapshot()
                .mode(nav.active(), nav.deep(), nav.semanticReady())
                .source(nav.sourceDimension(), nav.landmarkId(),
                        String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", source.x, source.y, source.z))
                .walk(pending, s.supportProgressTicks, WALK_BUDGET_TICKS, s.supportAlignTick, SUPPORT_ALIGN_TICKS,
                        s.supportId, s.supportDimension)
                .walkClock(s.walk.alignmentTicks(), s.walk.sourceWaitTicks(), s.walk.armed(), s.walk.recoveryWaitTicks())
                .walkDiscovery(SpiritTerrainService.walkTargetPending(p))
                .walkCooldown(s.walkRetry.remaining(p.getServer().getTicks()))
                .landing(nav.landingApproach())
                .target(nav.hasShallowTarget(), nav.targetDimension(), nav.targetLandmarkId(),
                        nav.targetBlock().toShortString(), nav.hasShallowTarget() ? Math.sqrt(q.squareDistance(target)) : Double.NaN)
                .capture(s.capture != null && !s.capture.isDone())
                .text();
    }
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
        Session s = session(p);
        // Handoff is driven by the NAVIGATION-mode transition, not by an abilities diff: normal double-jump
        // entry reaches navigation only after vanilla's abilities handler already preset flying=true
        // server-side, so an abilities-only check would silently skip the handoff.
        boolean handoff = WalkFlightHandoff.handoffDue(s == null ? null : s.handedDeep, deep);
        boolean abilitiesChanged = !p.getAbilities().allowFlying || p.getAbilities().flying != deep;
        p.getAbilities().allowFlying = true; p.getAbilities().flying = deep;
        p.setNoGravity(false); p.fallDistance = 0;
        if (handoff) {
            if (s != null) s.handedDeep = deep;
            // Every mode switch hands off a stable usable velocity: bounded momentum into flight, and a
            // zeroed standing velocity when walk acquisition validated ground contact (a residual flight
            // descent would clip the fresh mesh and bounce out via depenetration — visible rubber banding).
            // Ordinary player-move packets update the accepted pose, NOT server horizontal velocity.
            // Use recent accepted displacement (including the tick before a double-jump packet), so
            // sending the handoff to the owner cannot cancel a moving client's walk/sprint momentum.
            p.setVelocity(s.movement.handoff(p.getServer().getTicks(), p.getVelocity(), deep,
                    !deep && MeshCollision.ground(p).isPresent()));
            s.movement.clear();
            deliverVelocity(p, p.getVelocity());
        }
        if (abilitiesChanged || handoff || s.gestures.correctionConflicts(deep)) {
            p.sendAbilitiesUpdate();
            ServerPlayNetworking.send(p, new SpiritFlightCorrectionPayload(s.gestures.serverCorrected(deep)));
        }
    }

    /** Only a live session may acknowledge an ordered abilities correction. */
    public static void acknowledgeFlightCorrection(ServerPlayerEntity p, UUID token) {
        if (!spirit(p)) return;
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session s = sessions == null ? null : sessions.get(p.getUuid());
        if (s != null) s.gestures.acknowledgeCorrection(token);
    }

    /** Invoked at the successful tail of vanilla's onPlayerMove, after validation/collision acceptance. */
    public static void recordAcceptedMovement(ServerPlayerEntity p, Vec3d before) {
        if (spirit(p)) session(p).movement.record(p.getServer().getTicks(), before, p.getPos());
    }

    /** Teleports/corrections are not momentum; do not carry a previous sprint into the new carrier pose. */
    public static void resetHandoffMotion(ServerPlayerEntity p) {
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session s = sessions == null ? null : sessions.get(p.getUuid());
        if (s != null) s.movement.clear();
    }

    /** setVelocity alone mutates only server state and the owning client never sees it. Deliver the
    * handed-off velocity as a self EntityVelocityUpdate packet so the CONTROLLING client starts the new
    * mode from the same bounded pose instead of continuing its stale client-side motion. */
    private static void deliverVelocity(ServerPlayerEntity p, Vec3d velocity) {
        p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p.getId(), velocity));
    }

    /** A walk request that ends without acquisition leaves the carrier deep in flight. Deliver a
     * stable usable hover to the CONTROLLING client: an unbounded residual velocity would keep
     * drifting the body after the request expires, and setVelocity alone would never reach the client. */
    private static void deliverStableHover(ServerPlayerEntity p, Session s) {
        p.setVelocity(Vec3d.ZERO); p.fallDistance = 0;
        s.movement.clear();
        deliverVelocity(p, Vec3d.ZERO);
    }

    public static boolean enter(ServerPlayerEntity p) {
        init();
        if (spirit(p)) {
            // A silent rejection feeds the enter/leave command loop after a partial failure; state the coherent exit.
            p.sendMessage(Text.literal("Already in the spirit world; /spirit leave exits the current shallow location."), false);
            return false;
        }
        var server = p.getServer(); var world = server.getWorld(SpiritTerrainService.WORLD);
        if (world == null) { p.sendMessage(Text.literal("Spirit dimension unavailable."), false); return false; }
        Vec3d source = p.getPos(); String dimension = p.getWorld().getRegistryKey().getValue().toString();
        var nav = state(p);
        nav.rememberAbilities(p.getAbilities().allowFlying, p.getAbilities().flying, p.hasNoGravity());
        if (!SpiritTerrainService.prepareEnter(p)) { deactivate(p); return false; }
        var mapped = SpiritTerrainService.sourcePosition(p);
        nav.shallow(dimension, mapped.map(SpiritTerrainService.SourcePosition::landmarkId).orElse(""), source);
        Session s = freshVisit(p); // stale blend/landing/support/jump state from any prior visit can never leak in
        s.semanticReady = false; s.checkedRestore = true;
        nav.setSemanticReady(false); nav.setLandingApproach(false); nav.setSupportApproach(false);
        s.confirmedOwned = false; s.warnedAnchor = false; s.prefetched = false;
        try {
            // Source-identical carrier pose: no shared origin, entry search or replacement floor.
            io.github.mysticism.net.SpiritTransitionPayload.beforeTeleport(p, world, source, p.getYaw(), p.getPitch());
            p.teleport(world, source.x, source.y, source.z, p.getYaw(), p.getPitch());
            SpiritTerrainService.setShallow(p, true); flight(p, false);
            s.supportY = source.y; // entry pose is the supported carrier pose; envelope measured from here
            p.setVelocity(Vec3d.ZERO); sync(p);
            if (nav.hasShallowTarget()) {
                SpiritTerrainService.prefetchTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetPosition(),
                        p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(), nav.targetBasis()); s.prefetched = true;
            }
            p.sendMessage(Text.literal("Shallow spirit: walk/jump normally; double-jump flies deep. /spirit leave exits current shallow location."), false);
            return true;
        } catch (RuntimeException failure) {
            recoverFailedEnter(p, failure, source, dimension);
            return false;
        }
    }
    /** Recovery classification probes the player's ACTUAL current world: Fabric world-change callbacks
     * execute INSIDE teleport, so a callback throwing after the transfer leaves the player already in the
     * spirit carrier while teleport itself reports failure. A post-return flag can be stale and must never
     * downgrade an in-carrier player to ABORT (which would delete their prepared terrain and force deep
     * flight); the current world is the only authoritative signal. */
    static EntryRecovery.Action classifyEntryFailure(boolean currentlyInCarrier, Vec3d source, String dimension) {
        return EntryRecovery.failedEnter(currentlyInCarrier, EntryRecovery.recoverableSource(dimension, source));
    }
    /** A partial entry must land in a coherent usable state: back at the remembered source pose, or a retained
     * shallow carrier with live geometry — never inactive flight over missing terrain or an exit-less void fall. */
    private static void recoverFailedEnter(ServerPlayerEntity p, RuntimeException failure, Vec3d source, String dimension) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        switch (classifyEntryFailure(spirit(p), source, dimension)) {
            case RETURN_TO_SOURCE -> {
                EntryRecovery.reconcileSourceReturn(
                        () -> SpiritTerrainService.returnToSource(p, dimension, source),
                        () -> spirit(p),
                        () -> {
                            // A return callback may already have deactivated us before throwing. Cleanup is
                            // idempotent; never regrant carrier flight after saved abilities were cleared.
                            completeSourceRecovery(p);
                            p.sendMessage(Text.literal("Spirit entry failed mid-transition (" + detail + "); left the spirit carrier."), false);
                        },
                        () -> retainCarrier(p, detail));
            }
            case RETAIN_CARRIER -> retainCarrier(p, detail);
            case ABORT -> {
                // The player's current world is the source world: no spirit state exists to recover.
                SpiritTerrainService.cancelEnter(p); deactivate(p);
                p.sendMessage(Text.literal("Spirit entry failed: " + detail), false);
            }
        }
    }
    private static void completeSourceRecovery(ServerPlayerEntity p) {
        // teleport can throw before returnToSource resets motion, even though transfer already succeeded.
        p.setVelocity(Vec3d.ZERO); p.fallDistance = 0;
        SpiritTerrainService.cancelEnter(p); deactivate(p);
    }
    /** The source pose is unusable; retain the already-built carrier mesh and shallow binding so the
     * player keeps real geometry and a working /spirit leave instead of falling through the void. */
    private static void retainCarrier(ServerPlayerEntity p, String detail) {
        flight(p, false); sync(p);
        p.sendMessage(Text.literal("Spirit entry failed mid-transition (" + detail
                + "); shallow carrier retained — /spirit leave exits."), false);
    }
    private static void anchorSource(ServerPlayerEntity p, Session s, SpiritTerrainService.SourcePosition source) {
        anchorSource(p, source, p.getComponent(MysticismEntityComponents.LATENT_BASIS).get());
    }
    /** Terrain calls this after actual async ownership publication, including while already flying deep.
     * source is the retained physical source pose; sourceBasis is its captured grid, NOT a later camera basis. */
    public static void anchorSource(ServerPlayerEntity p, SpiritTerrainService.SourcePosition source, Basis384f sourceBasis) {
        var nav = state(p); Session s = session(p); restoreAnchor(p, s);
        if (!spirit(p) || !nav.active() || nav.semanticReady() || source.landmarkId().isEmpty()
                || !source.dimension().equals(nav.sourceDimension())
                || (!nav.landmarkId().isEmpty() && !nav.landmarkId().equals(source.landmarkId()))) return;
        var found = LandmarkStore.get(p.getServer()).metadata(source.landmarkId(),source.dimension());
        if (found.isEmpty()) return;
        var metadata = found.get();
        if (!metadata.header().dimension().equals(source.dimension()) || !metadata.header().bounds().contains(
                MathHelper.floor(source.position().x), MathHelper.floor(source.position().y), MathHelper.floor(source.position().z))) return;
        Vec384f q = SpiritActivityService.effectiveEmbedding(p.getServer(), metadata).vector();
        Vec3d offset = source.position().subtract(metadata.header().anchor().x(), metadata.header().anchor().y(), metadata.header().anchor().z());
        TraversalSteering.advance(q, sourceBasis, offset.x, offset.y, offset.z);
        nav.bindSource(source.dimension(), source.landmarkId(), source.position());
        p.getComponent(MysticismEntityComponents.LATENT_POS).set(q);
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
        if (!nav.hasShallowTarget() && !att.hasTarget()) {
            att.set(q); // initialize only an absent default, never overwrite a captured coordinate (even ZERO)
            if (s.supportPending || s.walk.armed()) s.supportTargetSnapshot = att.target();
        }
        nav.anchored(SpiritNavigation.AnchorOrigin.SOURCE);
        s.lastTransition = "Exact source ownership discovered; semantic travel is now ready. Movement mode unchanged.";
        // Correction ordering: nav (and its motionEpoch) must sync BEFORE the q it re-anchors, so the
        // client pose-sync guard sees the changed epoch when the delayed/reordered q sync arrives.
        sync(p);
        MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p); MysticismEntityComponents.LATENT_POS.sync(p);
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
            enterDeep(p, "Actual supporting owner changed");
            p.sendMessage(Text.literal("Cannot exit through the previous source binding."), false); return false;
        }
        if (!SpiritTerrainService.exit(p)) {
            p.sendMessage(Text.literal("Current shallow source location is unavailable or obstructed; no substitute exit."), false); return false;
        }
        deactivate(p); return true;
    }

    /** Bounded entry-support grace: a fresh standing stance keeps walking while octree ownership publishes. */
    public static final int ENTRY_SUPPORT_GRACE_TICKS = WalkingModePolicy.GROUND_PENDING_LIMIT;

    /** Shallow stance outcome for one update tick. */
    public enum Stance { WALK, FREE_FALL, HOLD_ENTRY_SUPPORT }
    public record StanceStep(Stance stance, int unsupportedTicks, int entrySupportTicks) {}

    /** Real-ground mode hold, not ownership authority. Both initial discovery and later local
     * ownership revalidation may lag accepted collision. The pending signal MUST be the exact
     * producing local floor's groundPending check, not a stale onGround flag, retained ID or
     * unrelated window future. A confirmed support resets the per-episode wait in update(). */
    public static StanceStep entryStance(int unsupported, int entrySupportTicks,
            boolean standing, boolean jumping, boolean groundPending) {
        var decision = WalkingModePolicy.shallowSupport(false, standing && !jumping && groundPending,
                jumping, unsupported, jumping, entrySupportTicks);
        return switch (decision) {
            case WALK -> new StanceStep(Stance.HOLD_ENTRY_SUPPORT, 0, entrySupportTicks + 1);
            case JUMP_GRACE -> new StanceStep(Stance.WALK, unsupported + 1, entrySupportTicks);
            case DEEP -> new StanceStep(Stance.FREE_FALL, unsupported + 1, entrySupportTicks);
        };
    }

    public static void enterDeep(ServerPlayerEntity p) { enterDeep(p, "Flight requested"); }
    private static void enterDeep(ServerPlayerEntity p, String reason) {
        if (!spirit(p)) return;
        var permissions = state(p);
        if (!permissions.hasSavedAbilities()) permissions.rememberAbilities(p.getAbilities().allowFlying, p.getAbilities().flying, p.hasNoGravity());
        Session s = session(p); restoreAnchor(p, s); endSupportApproach(p, s);
        var nav = state(p); boolean changed = !nav.active() || !nav.deep();
        // Flight safety never waits for a model. Only semantic travel/landing/touch require a real anchor.
        nav.enterDeep(); SpiritTerrainService.setShallow(p, false); flight(p, true);
        s.unsupported = 0;
        if (changed) sync(p);
        if (!s.semanticReady && !s.warnedAnchor) {
            p.sendMessage(Text.literal("Free flight active; semantic travel awaits real source discovery."), false); s.warnedAnchor = true;
        }
    }

    /** Vanilla false-flight requests current support, not a remembered entry or captured destination.
     * Only a fresh client gesture (state edge) may start a walk: a retransmitted or stale flight-off
     * packet must never restart a request or override the latest requested destination. */
    public static void onFlightToggle(ServerPlayerEntity p, boolean flying) {
        if (!spirit(p)) return;
        Session s = session(p);
        var request = s.gestures.flightRequest(flying);
        // Flight-on is an explicit cancellation even when a server correction recorded the same
        // state. Conversely stale off packets cannot resurrect a superseded/expired request.
        if (request == WalkIntentTracker.FlightRequest.DEEP) { enterDeep(p); return; }
        if (request == WalkIntentTracker.FlightRequest.IGNORE) return;
        if (!state(p).active()) enterDeep(p);
        if (!state(p).deep()) { flight(p, false); return; }
        restoreAnchor(p, s);
        flight(p, true); // remains deep/freeflight until the real terrain acquisition commits
        if (s.supportPending || s.walk.armed()) return; // repeated packets cannot reset budgets or duplicate requests
        if (!explicitWalkRequestAccepted(false, s.walkRetry, p.getServer().getTicks())) return; // failed-request cooldown: repeated toggles must not restart terrain validation
        beginSupportApproach(p, s, false);
        feedback(p, s, true, "Walk request: validating current source-owned support; flight remains active until ready.");
    }
    /** Shared trigger for explicit walk requests and natural resting landings; terrain validates everything.
     * Natural landings are physical events, not repeated requests: they bypass the retry cooldown but
     * keep their own airborne/resting policy so a failed walk can never leave flight permanently stuck. */
    private static void beginSupportApproach(ServerPlayerEntity p, Session s, boolean natural) {
        endApproach(p, s); s.blendFrom = null; s.blendTo = null;
        s.gestures.startWalk(); s.walkIntent = new WalkIntent();
        s.supportPending = true; s.supportProgressTicks = 0; s.supportFrom = null;
        s.supportGrid = null; s.supportAlignTick = 0; s.walk.reissue(); s.alignment.begin();
        s.supportStartedTick = p.getServer().getTicks();
        s.supportStall = 0; s.acquireFailures = 0; s.acquireRetry = 0;
        s.supportId = ""; s.supportDimension = "";
        s.supportTargetSnapshot = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target();
        s.naturalSupport = natural; s.restingTicks = 0;
        state(p).setSupportApproach(true); sync(p);
    }
    /** Resume the SAME intent, preserving its natural/explicit origin, target and recovery allowance.
     * Suspension allows freeflight to change the basis: interpolation resumes from the current frame. */
    private static void resumeSupportApproach(ServerPlayerEntity p, Session s) {
        endApproach(p, s); s.blendFrom = null; s.blendTo = null;
        s.supportPending = true; s.supportProgressTicks = 0; s.supportAlignTick = 0;
        // Resumption belongs to the original request: never renew its absolute deadline.
        s.walk.restart(); s.alignment.reset(); s.alignment.begin();
        if (s.supportFrom != null) s.supportFrom = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone();
        state(p).setSupportApproach(true); sync(p);
    }
    /** A deep-flying player who settles onto available owned custom terrain lands instead of hovering above support. */
    private static void naturalLanding(ServerPlayerEntity p, Session s, Vec3d delta) {
        var nav = state(p);
        if (naturalLandingAction(s, nav.deep(), nav.landingApproach(), MeshCollision.ground(p), delta)
                != NaturalLandingPolicy.Action.LAND) return;
        // Contact is real visible/collidable terrain, but its source ownership may still need
        // discovery. Start the same bounded acquisition as an explicit walk; attemptSupport
        // discovers the inverse-mapped source body and never commits unknown geometry.
        beginSupportApproach(p, s, true);
        feedback(p, s, true, "Natural landing: validating the terrain below; flight remains active until ready.");
    }
    /** Measured-contact/cadence seam shared with runtime regressions; this grants only an intent,
     * never shallow mode. Source discovery, guarded alignment and acquisition still follow. */
    static NaturalLandingPolicy.Action naturalLandingAction(Session s, boolean deep, boolean landingApproach,
            Optional<MeshCollision.Hit> ground, Vec3d delta) {
        if (ground.isEmpty()) { s.airborne = true; s.restingTicks = 0; return NaturalLandingPolicy.Action.NONE; }
        if (!s.airborne) { s.restingTicks = 0; return NaturalLandingPolicy.Action.NONE; } // standing takeoff needs real airtime
        // Admission uses ordinary mesh ground, not final walking's .99 normal: a projected
        // source-flat floor may need this very approach to align it back to horizontal.
        boolean resting = ground.get().normal().y > NaturalLandingPolicy.MIN_GROUND_NORMAL_Y
                && delta.y <= NaturalLandingPolicy.MAX_REST_VERTICAL;
        if (!resting) { s.restingTicks = 0; return NaturalLandingPolicy.Action.NONE; }
        var action = NaturalLandingPolicy.evaluate(deep, s.airborne, s.supportPending || s.walk.armed(), s.blendTo != null,
                landingApproach, resting, s.restingTicks);
        if (action != NaturalLandingPolicy.Action.LAND) ++s.restingTicks;
        else s.restingTicks = 0;
        return action;
    }
    private static void endSupportApproach(ServerPlayerEntity p, Session s) {
        endSupportApproach(p, s, false);
    }
    /** Pure policy seam: when a support approach ends, does this deep stretch keep its airborne
     * history? A failed/expired EXPLICIT walk request preserves it — a player who settled onto
     * terrain during pending validation must still be able to land naturally afterwards, without
     * renewed airtime. Everything else resets it: success (a fresh stretch re-arms honestly),
     * lifecycle resets, and failed NATURAL approaches, whose retry requires lifting off again
     * (anti-loop, matching the natural expiry message). */
    public static boolean keepsAirborneAfterApproachEnd(boolean naturalApproach, boolean failedOrExpired) {
        return failedOrExpired && !naturalApproach;
    }
    /** recover() disarms on timeout before teardown runs. The frozen target is still an
     * outstanding intent and owns its async proof/discovery even after that flag was cleared. */
    static void releaseSupportResources(Session s, Runnable cancel) {
        if (s.supportPending || s.walk.armed() || s.supportTargetSnapshot != null) cancel.run();
    }
    private static void endSupportApproach(ServerPlayerEntity p, Session s, boolean failedOrExpired) {
        boolean natural = s.naturalSupport;
        SpiritTerrainService.cancelCurrentSupport(p); // explicit cancellation also releases retained proof
        s.walk.disarm(); s.walkIntent = null; s.alignment.reset(); s.gestures.endWalk();
        s.supportPending = false; s.supportTargetSnapshot = null; s.supportFrom = null; s.supportGrid = null;
        s.supportId = ""; s.supportDimension = ""; s.supportProgressTicks = 0; s.supportAlignTick = 0;
        s.naturalSupport = false;
        if (!keepsAirborneAfterApproachEnd(natural, failedOrExpired)) s.airborne = false;
        s.restingTicks = 0;
        if (state(p).supportApproach()) { state(p).setSupportApproach(false); sync(p); }
    }

    /** A server-decided arrival invalidates pre-arrival movement/approach state, not the
     * player's independent source anchor, mode, captured attunement or capture request. */
    public static void onTeleportArrival(ServerPlayerEntity p) {
        if (!spirit(p)) return;
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session s = sessions == null ? null : sessions.get(p.getUuid());
        if (s == null) return;
        endSupportApproach(p, s); endApproach(p, s);
        resetArrivalMovement(s);
    }
    /** Shared state reset used by the arrival hook; package-visible for deterministic regressions. */
    static void resetArrivalMovement(Session s) {
        s.unsupported = 0; s.entrySupportTicks = 0;
        s.supportY = Double.NaN; s.movement.clear();
        s.airborne = false; s.restingTicks = 0;
        s.blendFrom = null; s.blendTo = null; s.blendTick = 0;
        s.attemptedLanding = false; s.warnedLanding = false;
    }

    /** Single-delta compatibility: identical delta for both roles (pre-split caller behavior). */
    public static boolean update(ServerPlayerEntity p, Vec3d delta) { return update(p, delta, delta); }

    /** Landing envelope: how far below the last supported feet height a jump/step-down may fall and
     * still be expected to land back on its supporting projected terrain (one ordinary source-block descent).
     * Beyond it the body has genuinely left its support and shallow walking must yield to freeflight. */
    static final double LANDING_ENVELOPE = WalkPolicy.LANDING_ENVELOPE;
    /** Bounded air budget for any continuous unsupported shallow interval, however it arose. */
    static final int AIR_GRACE_TICKS = WalkPolicy.AIR_GRACE_TICKS;
    /** Pure per-tick shallow support decision: keep walking shallow only while the carrier is still
     * inside its last support's landing envelope or the collision mesh still physically holds it.
     * A momentary ground-contact detection miss while standing/clamped, an ordinary jump ascent and
     * its landing, and small step-downs all HOLD instead of alternating shallow/deep flight states. */
    static boolean holdShallow(boolean onGround, double feetY, double supportY, int unsupportedTicks) {
        return WalkPolicy.landingEnvelopeHold(onGround, feetY, supportY, unsupportedTicks);
    }
    /** Called once by the evolver. True permits ordinary deep movement integration. Never snaps q/pose/basis.
     * physical drives real-movement navigation decisions (blend cancel); semantic drives q/basis
     * advancement and must match ClientLatentPredictor epoch filtering. The airborne shallow decision
     * is solely the physical landing envelope below — no rising-latch heuristic competes with it. */
    public static boolean update(ServerPlayerEntity p, Vec3d physical, Vec3d semantic) {
        if (!spirit(p)) { if (state(p).active() || state(p).hasSavedAbilities()) deactivate(p); return false; }
        var nav = state(p); Session s = session(p); restoreAnchor(p, s);
        if (!ensureTerrainSession(p, s)) return false; // Recovery returned the player to the source world this tick.
        if (nav.hasShallowTarget() && !s.prefetched) {
            SpiritTerrainService.prefetchTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetPosition(),
                    p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).target(), nav.targetBasis()); s.prefetched = true;
        }
        if (!nav.active()) enterDeep(p, "Spirit arrival without an active source binding"); // Safety, not semantic readiness.
        if (!nav.deep()) {
            if (p.getAbilities().flying) { enterDeep(p); return nav.deep() && state(p).semanticReady(); }
            // Transient identity retention is mode-keeping only, never capture/exit authority.
            var mapping = SpiritTerrainService.walkingPosition(p);
            var support = SpiritTerrainService.support(p);
            boolean grounded = MeshCollision.ground(p).isPresent();
            boolean pending = SpiritTerrainService.groundPending(p);
            boolean entryHold = boundedEntrySupport(s, grounded, pending);
            if (mapping.isEmpty()) {
                // Physical ground with ownership still publishing is shallow walking, not takeoff.
                // Entry and ownership refreshes must keep the player attached to their source locality
                // instead of flapping into free flight and back (jitter/rubber banding).
                if (!entryHold) { enterDeep(p); return nav.deep() && s.semanticReady; }
                flight(p, false);
                if (s.semanticReady) TraversalSteering.advance(p.getComponent(MysticismEntityComponents.LATENT_POS).get(),
                        p.getComponent(MysticismEntityComponents.LATENT_BASIS).get(), semantic.x, semantic.y, semantic.z);
                s.unsupported = 0;
                if (p.getServer().getTicks() % 4 == 0) sync(p);
                return false;
            }
            var source = mapping.get(); boolean anchoredNow = false;
            String id = source.landmarkId();
            // Permit the initial unowned frame to catch up with asynchronous ownership publication.
            // Once geometry actually confirms ownership, leaving it enters deep, never switches regions.
            if (!nav.landmarkId().isEmpty() && !id.equals(nav.landmarkId()) && (!id.isEmpty() || s.confirmedOwned)) {
                enterDeep(p, "Current source owner no longer matches the shallow binding"); return nav.deep() && nav.semanticReady();
            }
            if (!id.isEmpty()) s.confirmedOwned = true;
            if (id.isEmpty() && !s.confirmedOwned) id = nav.landmarkId();
            // A retained local window is NOT proof that the floor belongs to it. Blank matches blank only.
            if (support.isPresent() && !support.get().landmarkId().equals(id)) {
                enterDeep(p, "Supporting terrain belongs to a different source owner"); return nav.deep() && nav.semanticReady();
            }
            // Ownership checks precede initial anchoring; discovery cannot overwrite an established binding.
            if (!nav.semanticReady() && !source.landmarkId().isEmpty()) {
                SpiritTerrainService.deliverSourceAnchor(p); anchoredNow = nav.semanticReady();
            }
            nav.shallow(source.dimension(), id, source.position());
            var walkPhase = WalkPolicy.phase(support.isPresent(), grounded, pending, id.isEmpty());
            if (walkPhase == WalkPolicy.SupportPhase.PENDING && !entryHold) {
                enterDeep(p); return nav.deep() && s.semanticReady;
            }
            if (walkPhase != WalkPolicy.SupportPhase.AIRBORNE) {
                if (walkPhase == WalkPolicy.SupportPhase.SUPPORTED) s.entrySupportTicks = 0;
                // Fully owned floors AND ownership-pending mesh ground keep the carrier attached to
                // its source locality instead of flapping into free flight and back (jitter).
                s.unsupported = 0;
                if (grounded || support.isPresent()) s.supportY = p.getPos().y;
            } else if (!holdShallow(p.isOnGround(), p.getPos().y, s.supportY, ++s.unsupported)) {
                // The physical landing envelope is the ONLY airborne decision: ordinary jumps, landings
                // and small step-downs stay shallow while the carrier remains over supporting projected
                // terrain; an edge walk-off converts at the envelope breach, and the bounded air budget
                // caps any continuous unsupported interval. The superseded rising-latch heuristic must
                // not compete with this decision (see EntryFailureRecoveryTest).
                enterDeep(p); return nav.deep() && s.semanticReady;
            }
            flight(p, false);
            if (s.semanticReady && !anchoredNow) TraversalSteering.advance(p.getComponent(MysticismEntityComponents.LATENT_POS).get(),
                    p.getComponent(MysticismEntityComponents.LATENT_BASIS).get(), semantic.x, semantic.y, semantic.z);
            if (p.getServer().getTicks() % 4 == 0) sync(p);
            return false;
        }
        flight(p, true);
        naturalLanding(p, s, physical);
        if (s.supportPending) { attemptSupport(p, s, semantic); return false; }
        if (s.walk.armed()) {
            if (WalkPolicy.requestExpired(s.supportStartedTick, p.getServer().getTicks())) {
                expireSupport(p, s, "total request lifetime exceeded while waiting for source geometry");
                return nav.deep() && s.semanticReady;
            }
            if (!supportTargetUnchanged(p, s)) return nav.deep() && s.semanticReady;
            // Poll the real current contact; this also starts/reuses its bounded terrain proof.
            var support = s.semanticReady ? SpiritTerrainService.currentSupport(p) : Optional.<SpiritTerrainService.WalkSupport>empty();
            if (support.isEmpty()) SpiritTerrainService.discoverWalkTarget(p);
            if (s.walk.recover(s.semanticReady && support.isPresent()
                    && SpiritTerrainService.currentSupportReadiness(p, support.get()) == SpiritTerrainService.WalkSourceStatus.READY)) {
                resumeSupportApproach(p, s);
                attemptSupport(p, s, semantic); return false;
            }
            if (!s.walk.armed()) {
                // recover() timed out. Ensure retained proof and snapshots are released as well.
                endSupportApproach(p, s, true);
                walkFailed(p, s, "Walk recovery expired; remaining deep. No substitute landing.");
            }
            // While suspended, ordinary freeflight/prediction is allowed, not captured-target landing.
            return nav.deep() && s.semanticReady;
        }
        if (!s.semanticReady) return false;
        if (s.blendTo != null) {
            if (physical.lengthSquared() > 1e-5) { s.blendFrom = null; s.blendTo = null; }
            else {
                float fraction = ++s.blendTick / 20f;
                p.getComponent(MysticismEntityComponents.LATENT_BASIS).set(TraversalSteering.blend(s.blendFrom, s.blendTo, fraction));
                if (fraction >= 1) { s.blendFrom = null; s.blendTo = null; }
                return false;
            }
        }
        if (attemptLanding(p, s, semantic)) return false; // Approach advanced q once, without ordinary basis steering.
        return nav.deep();
    }
    /** A cancelled/unbuilt carrier session must self-heal from the per-player binding; a session that cannot be
     * rebuilt returns the player to the remembered source pose instead of stranding flight over missing terrain
     * with permanently failing walk requests and an exit-less command loop. */
    private static boolean ensureTerrainSession(ServerPlayerEntity p, Session s) {
        var nav = state(p);
        if (!nav.active() || !SpiritTerrainService.needsRestore(p)) { s.restoreFailures = 0; return true; }
        long now = p.getServer().getTicks();
        if (now < s.restoreNextTick) return true;
        s.restoreNextTick = now + EntryRecovery.RETRY_INTERVAL_TICKS;
        if (SpiritTerrainService.restore(p, false)) { s.restoreFailures = 0; return true; }
        ++s.restoreFailures;
        if (s.restoreFailures < EntryRecovery.REBUILD_FAILURE_LIMIT
                || !EntryRecovery.recoverableSource(nav.sourceDimension(), nav.sourcePosition())) return true;
        boolean leftCarrier = EntryRecovery.reconcileSourceReturn(
                () -> SpiritTerrainService.returnToSource(p, nav.sourceDimension(), nav.sourcePosition()),
                () -> spirit(p),
                () -> {
                    completeSourceRecovery(p);
                    s.restoreFailures = 0; s.restoreNextTick = 0;
                    p.sendMessage(Text.literal("Spirit terrain could not be rebuilt; left the spirit carrier."), false);
                },
                () -> s.restoreFailures = EntryRecovery.REBUILD_BACKOFF_FAILURES);
        // A callback throwing after transfer must not resume carrier integration in the source world.
        return !leftCarrier;
    }
    private static void restoreAnchor(ServerPlayerEntity p, Session s) {
        if (s.checkedRestore) return; s.checkedRestore = true;
        if (Double.isNaN(s.supportY)) s.supportY = p.getPos().y;
        endSupportApproach(p, s);
        endApproach(p, s); // a persisted landingApproach flag never resumes as an approach mid-flight
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
        if (p.getComponent(MysticismEntityComponents.LATENT_POS).hasCoordinate()) {
            Session s = session(p); endSupportApproach(p, s); endApproach(p, s);
            s.blendFrom = null; s.blendTo = null; s.targetSnapshot = null;
            s.attemptedLanding = false; s.warnedLanding = false; s.checkedRestore = true;
            state(p).anchored(SpiritNavigation.AnchorOrigin.CONCEPT);
            s.lastTransition = "Semantic position explicitly set to a ready concept coordinate; no source landing was acquired.";
            sync(p);
        }
    }
    private static void endApproach(ServerPlayerEntity p, Session s) {
        s.landingFrom = null; s.landingTick = 0;
        if (state(p).landingApproach()) { state(p).setLandingApproach(false); sync(p); }
    }
    /** Current support, independent of captured destinations. Physical movement drives q; the only
     * non-movement q change is the bounded validated slide below that closes alignment residual. */
    private static void expireSupport(ServerPlayerEntity p, Session s, String reason) {
        // Retain terrain-side proof: retries must reuse, not repeatedly cancel real source work.
        deliverStableHover(p, s);
        boolean natural = s.naturalSupport;
        s.walk.disarm(); s.walkIntent = null; s.alignment.reset(); s.gestures.endWalk();
        s.supportPending = false; s.supportTargetSnapshot = null; s.supportFrom = null; s.supportGrid = null;
        s.supportId = ""; s.supportDimension = ""; s.supportAlignTick = 0;
        s.naturalSupport = false; s.restingTicks = 0;
        if (!keepsAirborneAfterApproachEnd(natural, true)) s.airborne = false;
        state(p).setSupportApproach(false); sync(p); flight(p, true);
        walkFailed(p, s, "Walk request expired: " + reason + "; flight active. Move onto terrain or retry after discovery.");
    }

    /** Pending ownership may hold a REAL mesh floor, not an unbounded fabricated entry stance. */
    static boolean boundedEntrySupport(Session s, boolean grounded, boolean pending) {
        return grounded && pending && s.entrySupportTicks++ < ENTRY_SUPPORT_GRACE_TICKS;
    }
    private static void attemptSupport(ServerPlayerEntity p, Session s, Vec3d delta) {
        // Elapsed time includes suspended recovery and skipped processing ticks. Check before
        // source readiness/IO gates so intermittent geometry cannot renew an old walk intent.
        if (WalkPolicy.requestExpired(s.supportStartedTick, p.getServer().getTicks())) {
            expireSupport(p, s, "total request lifetime exceeded"); return;
        }
        var nav = state(p); var component = p.getComponent(MysticismEntityComponents.LATENT_BASIS);
        var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
        if (s.semanticReady && Double.isFinite(delta.x) && Double.isFinite(delta.y) && Double.isFinite(delta.z)
                && delta.lengthSquared() <= 16)
            TraversalSteering.advance(q, WalkPolicy.integrationBasis(component.get(), s.supportGrid), delta.x, delta.y, delta.z);
        ++s.supportProgressTicks;
        if (s.supportProgressTicks % 40 == 0) feedback(p, s, true, status(p));
        if (!supportTargetUnchanged(p, s)) return;
        var found = s.semanticReady ? SpiritTerrainService.currentSupport(p) : Optional.<SpiritTerrainService.WalkSupport>empty();
        if (found.isEmpty()) SpiritTerrainService.discoverWalkTarget(p);
        var source = s.semanticReady ? SpiritTerrainService.currentSupportReadiness(p, found.orElse(null))
                : SpiritTerrainService.WalkSourceStatus.PENDING;
        boolean proofPending = SpiritTerrainService.currentSupportProofPending(p, WALK_APPROACH_DEPTH);
        var lifetime = s.walkIntent.tick(s.semanticReady, found.isPresent(), proofPending
                || source == SpiritTerrainService.WalkSourceStatus.PENDING);
        if (lifetime.outcome() == WalkIntent.Outcome.EXPIRED) {
            expireSupport(p, s, lifetime.reason().message()); return;
        }
        WalkRequestClock.Phase phase = s.walk.tick(source == SpiritTerrainService.WalkSourceStatus.READY,
                source == SpiritTerrainService.WalkSourceStatus.PENDING);
        if (phase == WalkRequestClock.Phase.EXPIRE_RECOVERABLE) {
            // Keep the in-flight terrain proof alive: cancelling it here would defeat late recovery.
            // Stop prediction/alignment now, retaining only this issued intent and its frozen target.
            s.supportPending = false; nav.setSupportApproach(false); sync(p);
            feedback(p, s, true, "Walk waiting for late source discovery/geometry; flight active, automatic recovery retained briefly.");
            return;
        }
        // Source wait uses the recovery clock; alignment uses actual progress, not a raw 200 ticks.
        if (phase == WalkRequestClock.Phase.EXPIRE && source == SpiritTerrainService.WalkSourceStatus.PENDING) {
            expireSupport(p, s, "source discovery exceeded its bounded recovery allowance"); return;
        }
        if (phase == WalkRequestClock.Phase.WAIT_SOURCE) return; // Never invent q or align against unfinished source inputs.
        if (found.isEmpty()) return; // Terrain starts/reuses bounded exact source-cell proof, never a join.
        SpiritTerrainService.anchorSupportPivot(p);
        // Pivot changes semantic placement without changing visible geometry; refresh its coordinate.
        found = SpiritTerrainService.currentSupport(p);
        if (found.isEmpty()) return;
        var support = found.get(); Basis384f destination = support.sourceBasis();
        boolean sameLocality = s.supportFrom != null && s.supportWindow == support.windowIdentity()
                && s.supportId.equals(support.landmarkId()) && s.supportDimension.equals(support.sourceDimension())
                && basisError(s.supportGrid, destination) <= 1e-12f;
        // Classify the SOURCE floor, not its current projected tilt: guarded alignment must run
        // before the projected normal can reach acquisition's .99 gate. Side/edge grazes wait;
        // an alignable source floor in a changed locality is FOLLOWED while moving, not cancelled.
        switch (DeepWalkAttachment.evaluate(s.supportFrom != null, sameLocality,
                DeepWalkAttachment.moving(delta.x, delta.y, delta.z), support.normal().y, support.sourceNormal().y)) {
            case WAIT -> { return; } // Unsupported grazes never lock or cancel the pending request.
            case KEEP -> { }
            case ATTACH, FOLLOW -> { // (Re)attach to the locality actually contacted while moving through the projection.
                s.supportFrom = component.get().clone(); s.supportGrid = destination.clone(); s.supportAlignTick = 0;
                s.supportWindow = support.windowIdentity(); s.supportId = support.landmarkId(); s.supportDimension = support.sourceDimension();
            }
            case CANCEL -> {
                endSupportApproach(p, s, true);
                walkFailed(p, s, "Actual support window/ownership changed; remaining deep. Request walking on the new support again.");
                return;
            }
        }
        // Changed support re-bases the SAME request from the live basis, retaining its budgets.
        Basis384f before = component.get();
        if (s.alignment.stalled(basisError(before, destination), proofPending)) {
            expireSupport(p, s, "source alignment made no progress"); return;
        }
        s.alignment.bind(before, destination, support.windowIdentity(), support.landmarkId(), support.sourceDimension());
        boolean aligned = basisError(before, destination) < 1e-8f;
        Basis384f proposed = aligned ? before : s.alignment.proposed(before, destination);
        if (before.i.squareDistance(proposed.i) > .01f || before.j.squareDistance(proposed.j) > .01f
                || before.k.squareDistance(proposed.k) > .01f) {
            endSupportApproach(p, s, true);
            walkFailed(p, s, "Source-grid alignment would be discontinuous; remaining deep on current geometry.");
            return;
        }
        // q already represents ACTUAL movement in the previous frame. Validate the joint current-q /
        // proposed-basis scene before application. No attraction to sourceCoordinate or user target.
        if (!SpiritTerrainService.canAlign(p, proposed)) { s.alignment.held(before); return; }
        if (!aligned) { component.set(proposed); s.alignment.applied(); s.supportAlignTick = s.alignment.alignTick; }
        if (basisError(component.get(), destination) >= 1e-8f) return;
        // Basis is aligned. Movement during the request can leave a bounded q/coordinate residual;
        // close it with a small validated slide each tick so acquisition stays reachable while the
        // player stands still. Nothing moves once the residual is inside the acquisition gate.
        double worldError = WalkPolicy.worldErrorSquared(support.sourceCoordinate().sub(q), component.get());
        if (WalkPolicy.displacementFatal(worldError)) {
            endSupportApproach(p, s);
            p.sendMessage(Text.literal("Current support is semantically displaced from your coordinate; remaining deep."), true);
            return;
        }
        if (WalkPolicy.slideNeeded(worldError)) {
            Vec384f original = q.clone(), candidate = WalkPolicy.slidToward(q, support.sourceCoordinate());
            q.converge(candidate, 1);
            boolean clear;
            try { clear = SpiritTerrainService.canAlign(p, component.get()); }
            finally { q.converge(original, 1); } // restore the ORIGINAL value/reference even on throw
            if (!clear) return; // held this tick; the slide resumes when the body is clear
            q.converge(candidate, 1);
        }
        if (p.getServer().getTicks() < s.acquireRetry) return; // bounded retry pressure, never a busy loop
        if (!SpiritTerrainService.acquireCurrentSupport(p, support)) {
            s.acquireRetry = p.getServer().getTicks() + WalkPolicy.ACQUIRE_RETRY_TICKS;
            if (++s.acquireFailures > WalkPolicy.MAX_ACQUIRE_FAILURES) {
                expireSupport(p, s, "current support repeatedly failed safe acquisition");
            }
            return;
        }
        if (!commitSupportMapping(nav, support, SpiritTerrainService.sourcePosition(p))) {
            SpiritTerrainService.setShallow(p, false); endSupportApproach(p, s, true);
            walkFailed(p, s, "Terrain acquisition did not retain its validated source mapping; remaining deep.");
            return;
        }
        s.confirmedOwned = true; recordAcquiredSupport(s, p.getY()); endSupportApproach(p, s);
        SpiritBasisEvolver.resetMotion(p); flight(p, false);
        // Navigation epoch must reach the client before the pose committed by acquisition.
        sync(p); MysticismEntityComponents.LATENT_BASIS.sync(p); MysticismEntityComponents.LATENT_POS.sync(p);
        feedback(p, s, true, "Shallow: walking on the current source-owned landmark. Captured attunement retained.");
    }
    /** Final mapping/state commit only after terrain's source/mesh acquisition succeeds. */
    public static boolean commitSupportMapping(SpiritNavigation nav, SpiritTerrainService.WalkSupport support,
            Optional<SpiritTerrainService.SourcePosition> mapped) {
        if (mapped.isEmpty() || !mapped.get().landmarkId().equals(support.landmarkId())
                || !mapped.get().dimension().equals(support.sourceDimension())) return false;
        var at = mapped.get(); nav.shallow(at.dimension(), at.landmarkId(), at.position());
        return true;
    }
    private static boolean supportTargetUnchanged(ServerPlayerEntity p, Session s) {
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
        if (!s.gestures.superseded() && (s.supportTargetSnapshot == null
                || !WalkIntentTracker.attunementSupersedes(s.supportTargetSnapshot, att.target(), att.explicitTarget()))) return true;
        endSupportApproach(p, s, true);
        walkFailed(p, s, "Walk request cancelled after destination changed; latest target retained.");
        return false;
    }
    /** Acquisition already validated contact at CURRENT carrier feet. Seed the new floor's
     * landing envelope before flight/ability handoff: a jump packet can precede our next tick. */
    private static void recordAcquiredSupport(Session s, double feetY) {
        s.unsupported = 0; s.entrySupportTicks = 0; s.supportY = feetY;
    }
    private static float basisError(Basis384f a, Basis384f b) {
        return a.i.squareDistance(b.i) + a.j.squareDistance(b.j) + a.k.squareDistance(b.k);
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
            if (!s.warnedLanding) { notice(p, s, "Landing remains deep: continuous terrain-transition guard is not installed.", false); s.warnedLanding = true; }
            return false;
        }
        var component = p.getComponent(MysticismEntityComponents.LATENT_BASIS);
        Basis384f destination = nav.targetBasis();
        final float approachDistanceSquared = .0005f * .0005f;
        final float acquisitionDistanceSquared = 2e-6f * 2e-6f;
        // Keep ordinary movement-dependent pursuit until the FULL residual is inside the final band.
        // Then use bounded movement-driven convergence, not source-basis-only translation: arbitrary
        // continued movement must not recreate an unreachable perpendicular residual during alignment.
        if (distance > approachDistanceSquared) { endApproach(p, s); return false; }
        if (s.landingFrom == null) { s.landingFrom = component.get().clone(); s.landingTick = 0; }
        if (!nav.landingApproach()) { nav.setLandingApproach(true); sync(p); }
        Basis384f proposed = s.landingTick < 40
                ? TraversalSteering.blend(s.landingFrom, destination, (s.landingTick + 1) / 40f) : component.get();
        // Reject a singular/antipodal interpolation jump rather than forcing the final basis.
        Basis384f before = component.get();
        if (before.i.squareDistance(proposed.i) > .01f || before.j.squareDistance(proposed.j) > .01f
                || before.k.squareDistance(proposed.k) > .01f) {
            s.attemptedLanding = true; endApproach(p, s);
            notice(p, s, "Captured source-grid alignment cannot transition continuously; remaining deep nearby.", false);
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
        // .0005 is ONLY the alignment-entry band. Keep movement convergence running after step 40
        // until the strict 2e-6 residual; approachStep stops only below 1e-8 (well inside this gate).
        Basis384f current = component.get();
        if (s.landingTick < 40 || distance > acquisitionDistanceSquared
                || current.i.squareDistance(destination.i) > 1e-8f || current.j.squareDistance(destination.j) > 1e-8f
                || current.k.squareDistance(destination.k) > 1e-8f) return true;
        var support = SpiritTerrainService.support(p);
        if (support.isEmpty() || !support.get().landmarkId().equals(nav.targetLandmarkId())
                || support.get().normal().y < .99 || !safety.ready(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetPosition())) return true;
        s.attemptedLanding = true;
        // Parent's terrain acquisition must preserve CURRENT carrier pose and commit only its ready source mapping.
        if (SpiritTerrainService.tryLandTarget(p, nav.targetDimension(), nav.targetLandmarkId(), nav.targetPosition())) {
            var source = SpiritTerrainService.sourcePosition(p);
            if (source.isPresent()) {
                var at = source.get(); nav.shallow(at.dimension(), at.landmarkId(), at.position());
                recordAcquiredSupport(s, p.getY());
                endApproach(p, s); SpiritBasisEvolver.resetMotion(p); flight(p, false);
                // Correction ordering: nav (and its motionEpoch) must sync BEFORE the landing pose it commits.
                sync(p);
                MysticismEntityComponents.LATENT_BASIS.sync(p); MysticismEntityComponents.LATENT_POS.sync(p);
            }
        } else { endApproach(p, s); notice(p, s, "Captured destination changed or blocked. Remaining deep nearby.", false); }
        return true;
    }
    public static Optional<SpiritScenePayload.Binding> binding(ServerPlayerEntity p) {
        var nav = state(p); if (!spirit(p) || !nav.active() || nav.deep()) return Optional.empty();
        // An unowned source binding has no semantic anchor yet. ZERO is explicitly unavailable
        // binding metadata, never passed to the deep evolver/semantic selection as a fabricated embedding.
        Vec384f captured = state(p).semanticReady() ? p.getComponent(MysticismEntityComponents.LATENT_POS).get().clone() : Vec384f.ZERO();
        return SpiritTerrainService.sourcePosition(p)
                .map(source -> new SpiritScenePayload.Binding(source.dimension(), source.landmarkId(), source.position(), captured));
    }

    public static void captureTarget(ServerPlayerEntity p, String dimension, String id, BlockPos block, Vec384f embedding) {
        captureTarget(p, dimension, id, block, embedding, new Basis384f());
    }
    public static void captureTarget(ServerPlayerEntity p, String dimension, String id, BlockPos block, Vec384f embedding, Basis384f basis) {
        captureTarget(p, dimension, id, new Vec3d(block.getX() + .5, block.getY(), block.getZ() + .5), embedding, basis);
    }
    public static void captureTarget(ServerPlayerEntity p, String dimension, String id, Vec3d foot, Vec384f embedding, Basis384f basis) {
        EmbeddingSpace.requireCurrent(embedding);
        cancelCapture(p);
        state(p).target(dimension, id, foot, basis);
        p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).set(embedding.clone());
        sync(p); MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p);
        Session s = session(p);
        s.walk.disarm(); s.walkIntent = null; s.alignment.reset(); s.gestures.endWalk(); // A newer explicit destination supersedes any pending walk intent.
        s.attemptedLanding = false; s.prefetched = true; s.warnedLanding = false;
        endSupportApproach(p, s); endApproach(p, s);
        if (spirit(p)) SpiritTerrainService.prefetchTarget(p, dimension, id, foot, embedding, basis);
        else s.prefetched = false;
    }
    public static boolean captureHere(ServerPlayerEntity p) {
        var nav = state(p);
        if (spirit(p) && (!nav.active() || nav.deep())) {
            p.sendMessage(Text.literal("Capture here requires the source world or a valid shallow location."), false); return false;
        }
        if (spirit(p)) {
            var mapped = SpiritTerrainService.sourcePosition(p);
            if (mapped.isEmpty()) { p.sendMessage(Text.literal("No validated current source foot to capture."), false); return false; }
            return captureSource(p, mapped.get().dimension(), mapped.get().position(), "");
        }
        return captureSource(p, p.getWorld().getRegistryKey().getValue().toString(), p.getPos(), "");
    }
    /** A newer explicit target supersedes queued source discovery, including an already-queued callback. */
    public static void cancelCapture(ServerPlayerEntity p) {
        Map<UUID, Session> sessions = SERVERS.get(p.getServer());
        Session s = sessions == null ? null : sessions.get(p.getUuid());
        if (s != null) { ++s.captureNonce; if (s.capture != null) s.capture.cancel(false); }
    }
    /** Debug source-coordinate capture, asynchronously verifies a real source landmark. */
    public static boolean captureSource(ServerPlayerEntity p, String dimension, BlockPos block, String requiredId) {
        return captureSource(p, dimension, new Vec3d(block.getX() + .5, block.getY(), block.getZ() + .5), requiredId);
    }
    private static boolean captureSource(ServerPlayerEntity p, String dimension, Vec3d foot, String requiredId) {
        BlockPos block = BlockPos.ofFloored(foot); // ownership seed only; never the captured feet
        Session s = session(p);
        if (s.capture != null && !s.capture.isDone()) {
            // The latest requested destination wins: an older pending discovery never rejects or
            // outlives a newer capture. Its stale completion is dead via the capture nonce.
            s.capture.cancel(false);
            p.sendMessage(Text.literal("Superseding the older pending source discovery with this capture."), false);
        }
        // Accepting a capture is a destination request: supersede older navigation intents NOW,
        // not only when delayed discovery completes, so an older pending walk can never commit
        // shallow mode against the pre-capture destination in the meantime.
        state(p).clearTarget();
        explicitDestinationChange(p);
        var server = p.getServer();
        // Snapshot the source-grid basis before async completion, not a later moving deep basis.
        Basis384f basis = state(p).active() && !state(p).deep() ? p.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone() : new Basis384f();
        s.captureDimension = dimension; long nonce = ++s.captureNonce;
        s.capture = SourceLandmarks.ensureSourceLocation(server, dimension, block).whenComplete((found, error) -> server.execute(() -> {
            if (!live(p, s) || s.captureNonce != nonce) return;
            if (error != null || found == null || found.isEmpty()) { p.sendMessage(Text.literal("No captured source landmark available."), false); return; }
            var metadata = found.get();
            if (!metadata.header().dimension().equals(dimension)) {
                p.sendMessage(Text.literal("Discovered landmark belongs to another source dimension."), false); return;
            }
            if (!requiredId.isEmpty() && !LandmarkStore.get(server).resolve(requiredId).equals(metadata.id())) {
                p.sendMessage(Text.literal("Requested landmark does not own the captured source location."), false); return;
            }
            Vec384f captured = SpiritActivityService.effectiveEmbedding(server, metadata).vector();
            TraversalSteering.advance(captured, basis, foot.x - metadata.header().anchor().x(),
                    foot.y - metadata.header().anchor().y(), foot.z - metadata.header().anchor().z());
            captureTarget(p, dimension, metadata.id(), foot, captured, basis);
            p.sendMessage(Text.literal("Captured " + metadata.id() + " at " + dimension + " " + block.toShortString() + " (vector snapshot)."), false);
        }));
        p.sendMessage(Text.literal("Capturing generated source landmark asynchronously…"), false); return true;
    }
    /** Caller validates BOTH projected reach/alignment/collision rays before invoking.
     * The touch effect is the RECIPIENT's basis blend. The recipient's own lifecycle intents
     * (pending walk request, landing approach, in-progress blend) are per-player state: an
     * incoming touch is rejected while they are active instead of silently cancelling them.
     * Movement, expiry and the recipient's own actions still cancel those intents normally. */
    /** An explicit concept/attunement re-key without a new shallow capture (e.g. /myst set attune
     * or the personal-concept capture) is still a destination request: it supersedes any pending
     * walk intent or landing pursuit, which must never complete against the older destination. */
    public static void explicitDestinationChange(ServerPlayerEntity p) {
        // Keep delayed capture invalidation in the destination API, not only in command callers.
        // A queued old capture callback must not replace this newer explicit destination.
        cancelCapture(p);
        state(p).invalidateMotionPrediction(); sync(p);
        Session s = session(p);
        s.walk.disarm(); s.walkIntent = null; s.alignment.reset(); s.gestures.endWalk();
        endSupportApproach(p, s); endApproach(p, s);
        s.targetSnapshot = null; s.attemptedLanding = false; s.warnedLanding = false;
    }
    /** Caller validates BOTH projected reach/alignment/collision rays before invoking. */
    public static void touch(ServerPlayerEntity actor, ServerPlayerEntity target) {
        if (actor == target || !deep(actor) || !deep(target) || !state(actor).semanticReady() || !state(target).semanticReady()
                || actor.getServer() != target.getServer()
                || actor.getWorld() != target.getWorld()) return;
        Session s = session(target);
        var targetState = state(target);
        if (!TouchReception.mayDisturb(new TouchReception.Intent(
                s.supportPending || targetState.supportApproach(),
                s.landingFrom != null || targetState.landingApproach(),
                s.blendTo != null))) return;
        endSupportApproach(target, s); endApproach(target, s);
        s.blendFrom = target.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone();
        s.blendTo = actor.getComponent(MysticismEntityComponents.LATENT_BASIS).get().clone(); s.blendTick = 0;
        // Target vector and semantic q deliberately unchanged. Network authenticates/validates the touch.
    }
}
