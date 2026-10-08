package io.github.mysticism.navigation;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mysticism.command.SpiritCommand;
import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.embedding.EmbeddingNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Offline regressions for partial-entry failure recovery: decision policy, binding coherence,
 * carrier-session self-heal wiring and validated return contracts. No live server, GPU or model
 * assets are claimed or required; this exercises deterministic logic and loadable contracts only. */
public final class EntryFailureRecoveryTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method found = type.getDeclaredMethod(name, parameters);
        int modifiers = found.getModifiers();
        check(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers), name + " must stay a public static contract");
        return found;
    }

    private static void decisionMatrix() throws Exception {
        // Failure classification uses the player's ACTUAL current world. Fabric world-change callbacks
        // execute INSIDE teleport: a callback throwing after the transfer leaves the player already in
        // the spirit carrier while teleport itself reports failure (P1). Such a player must never be
        // classified ABORT, which would delete their prepared terrain and force deep flight.
        check(EntryRecovery.failedEnter(true, true) == EntryRecovery.Action.RETURN_TO_SOURCE,
                "In-carrier player with a recoverable remembered pose must attempt the validated return");
        check(EntryRecovery.failedEnter(true, false) == EntryRecovery.Action.RETAIN_CARRIER,
                "In-carrier player with an unusable remembered pose must retain the usable carrier");
        for (boolean recoverable : new boolean[]{true, false})
            check(EntryRecovery.failedEnter(false, recoverable) == EntryRecovery.Action.ABORT,
                    "A player whose current world is the source world has no spirit state to recover");
        // The production classification seam must delegate to the same policy (no divergent rule).
        Vec3d pose = new Vec3d(1.5, 64, -2.5);
        check(SpiritNavigationService.classifyEntryFailure(true, pose, "minecraft:overworld")
                == EntryRecovery.Action.RETURN_TO_SOURCE,
                "Production classification keeps the return attempt for an in-carrier player");
        check(SpiritNavigationService.classifyEntryFailure(false, pose, "minecraft:overworld")
                == EntryRecovery.Action.ABORT,
                "Production classification aborts only when the current world is the source world");
        check(SpiritNavigationService.classifyEntryFailure(true, pose, "not a dimension")
                == EntryRecovery.Action.RETAIN_CARRIER,
                "Production classification retains the carrier when the remembered pose is unusable");

        check(EntryRecovery.recoverableSource("minecraft:overworld", pose), "Real source pose is recoverable");
        check(!EntryRecovery.recoverableSource("", new Vec3d(0, 64, 0)), "Empty dimension is not a recovery target");
        check(!EntryRecovery.recoverableSource(null, new Vec3d(0, 64, 0)), "Null dimension is not a recovery target");
        check(!EntryRecovery.recoverableSource("not a dimension", new Vec3d(0, 64, 0)), "Unparseable dimension is not a recovery target");
        check(!EntryRecovery.recoverableSource("minecraft:overworld", null), "Null pose is not a recovery target");
        check(!EntryRecovery.recoverableSource("minecraft:overworld", new Vec3d(Double.NaN, 64, 0)), "NaN pose is not a recovery target");
        check(!EntryRecovery.recoverableSource("minecraft:overworld", new Vec3d(0, Double.POSITIVE_INFINITY, 0)), "Infinite pose is not a recovery target");
        check(EntryRecovery.RETRY_INTERVAL_TICKS > 0 && EntryRecovery.REBUILD_FAILURE_LIMIT > 1
                && EntryRecovery.REBUILD_BACKOFF_FAILURES > 0 && EntryRecovery.REBUILD_BACKOFF_FAILURES < EntryRecovery.REBUILD_FAILURE_LIMIT,
                "Self-heal cadence constants must form a bounded backoff");

        // P1 wiring contract: recovery must not accept a carried/post-return flag parameter at all —
        // a boolean flag sampled before teleport returns is exactly the stale-signal bug. The only
        // allowed signature probes the player's current world inside the recovery path itself.
        ClassLoader loader = EntryFailureRecoveryTest.class.getClassLoader();
        Class<?> navigation = Class.forName("io.github.mysticism.navigation.SpiritNavigationService", false, loader);
        var recover = navigation.getDeclaredMethod("recoverFailedEnter",
                ServerPlayerEntity.class, RuntimeException.class, Vec3d.class, String.class);
        check(Modifier.isStatic(recover.getModifiers()) && !Modifier.isPublic(recover.getModifiers()),
                "recoverFailedEnter stays a package-private current-world probe");
        for (var parameter : recover.getParameterTypes())
            check(parameter != boolean.class && parameter != Boolean.class,
                "recoverFailedEnter must never take a stale carried flag");
        var classify = navigation.getDeclaredMethod("classifyEntryFailure", boolean.class, Vec3d.class, String.class);
        int classifyModifiers = classify.getModifiers();
        check(Modifier.isStatic(classifyModifiers) && !Modifier.isPublic(classifyModifiers) && classify.getReturnType() == EntryRecovery.Action.class,
                "classifyEntryFailure stays the package-private current-world classification seam");
    }

    private static void bindingCoherence() {
        var nav = new SpiritNavigation();
        // Saved pre-entry abilities must be captured once: a re-remember during deep flight must never
        // replace them, or a failed-entry recovery would grant permanent survival flight.
        nav.rememberAbilities(false, false, false);
        nav.enterDeep();
        nav.rememberAbilities(true, true, true);
        check(nav.hasSavedAbilities() && !nav.savedAllowFlying() && !nav.savedFlying() && !nav.savedNoGravity(),
                "Second ability capture must not overwrite the remembered pre-entry state");
        nav.shallow("minecraft:overworld", "", new Vec3d(1.5, 64, 2.5));
        check(nav.active() && !nav.deep() && nav.sourceDimension().equals("minecraft:overworld")
                && nav.sourcePosition().equals(new Vec3d(1.5, 64, 2.5)), "Shallow binding records the physical source pose");
        nav.clearSavedAbilities();
        check(!nav.hasSavedAbilities(), "Cleared abilities stay cleared");

        // The remembered source pose survives a model-incompatible restart: recovery stays possible
        // even when every semantic coordinate is discarded.
        var tagged = new NbtCompound();
        nav.shallow("minecraft:overworld", "", new Vec3d(4.5, 65, -7.25));
        nav.writeToNbt(tagged, null);
        check(EmbeddingNbt.compatible(tagged), "Written navigation NBT carries the current semantic stamp");
        tagged.putString("embeddingModel", "incompatible-model");
        var restored = new SpiritNavigation();
        restored.readFromNbt(tagged, null);
        check(restored.active() && !restored.deep(), "Physical mode survives an incompatible semantic stamp");
        check(restored.sourceDimension().equals("minecraft:overworld")
                && restored.sourcePosition().equals(new Vec3d(4.5, 65, -7.25)),
                "The recovery target pose survives an incompatible semantic stamp");
        check(restored.landmarkId().isEmpty() && !restored.semanticReady(),
                "Semantic binding is discarded on an incompatible semantic stamp");

        // Untagged NBT must not fabricate a recovery target or a phantom active state.
        var untagged = new SpiritNavigation();
        untagged.readFromNbt(new NbtCompound(), null);
        check(!untagged.active() && untagged.sourceDimension().isEmpty() && untagged.sourcePosition().equals(Vec3d.ZERO),
                "Untagged NBT stays inactive with no invented source pose");
    }

    private static void recoveryContracts() throws Exception {
        ClassLoader loader = EntryFailureRecoveryTest.class.getClassLoader();
        Class<?> terrain = Class.forName("io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService", false, loader);
        check(method(terrain, "needsRestore", ServerPlayerEntity.class).getReturnType() == boolean.class,
                "Terrain must expose the missing-session probe navigation self-heals from");
        check(method(terrain, "returnToSource", ServerPlayerEntity.class, String.class, Vec3d.class).getReturnType() == boolean.class,
                "Terrain must expose the validated remembered-pose return");
        check(method(terrain, "restore", ServerPlayerEntity.class, boolean.class).getReturnType() == boolean.class,
                "Terrain restore must support quiet bounded retries");
        Class<?> navigation = Class.forName("io.github.mysticism.navigation.SpiritNavigationService", false, loader);
        Class<?> session = Class.forName("io.github.mysticism.navigation.SpiritNavigationService$Session", false, loader);
        session.getDeclaredField("restoreNextTick");
        session.getDeclaredField("restoreFailures");
        // Introspection only: the cadence fields back the bounded self-heal loop and must not disappear.
        check(true, "Navigation session carries self-heal cadence state");

        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        SpiritCommand.register(dispatcher);
        var spirit = dispatcher.getRoot().getChild("spirit");
        check(spirit != null, "/spirit must stay registered");
        check(spirit.getChild("enter") != null && spirit.getChild("leave") != null,
                "Enter/leave must stay registered so recovered players keep an exit path");
    }

    public static void main(String[] args) throws Exception {
        decisionMatrix();
        bindingCoherence();
        recoveryContracts();
        System.out.println("EntryFailureRecoveryTest: " + checks + " checks passed (offline policy/contract only)");
    }
}
