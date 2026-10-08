package io.github.mysticism.navigation;

import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.vector.Basis384f;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Per-player entry/exit lifecycle independence regressions.
 *
 * <p>Uses the real production {@link SpiritNavigation} component (one instance per player,
 * exactly as registered) and the real production {@link TouchReception} policy wired into
 * {@code SpiritNavigationService.touch}. No Minecraft server boot, model or world IO.</p>
 *
 * <p>Contract under test: entry, exit, mode, readiness, captured targets and remembered
 * abilities are strictly per-player state — another player's lifecycle event can never
 * flip, clear or inherit them — and an incoming touch must not destroy the recipient's
 * own pending lifecycle intents.</p>
 */
public final class PerPlayerEntryLifecycleTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void fails(Runnable action, String why) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError(why);
    }

    public static void main(String[] args) {
        componentModeReadinessIsolation();
        componentTargetAndAbilityIsolation();
        deactivationIsolation();
        touchReceptionPolicy();
        System.out.println("PerPlayerEntryLifecycleTest: " + checks + " checks passed");
    }

    /** Two fresh per-player components; every lifecycle mutation on one leaves the other untouched. */
    private static void componentModeReadinessIsolation() {
        SpiritNavigation a = new SpiritNavigation();
        SpiritNavigation b = new SpiritNavigation();
        check(!a.active() && !a.deep() && !a.semanticReady(), "fresh component starts inert");
        check(!b.active() && !b.deep() && !b.semanticReady(), "second player starts inert");

        // Player A's entry (deep) must not move any of B's flags.
        a.enterDeep();
        check(a.active() && a.deep(), "A is active deep after its own entry");
        check(!b.active() && !b.deep() && !b.semanticReady(), "B untouched by A's entry");

        // Player B's own shallow entry must not disturb A's mode or readiness.
        Vec3d footB = new Vec3d(12.5, 64, -7.25);
        b.shallow("minecraft:overworld", "landmark-b", footB);
        check(b.active() && !b.deep(), "B is shallow after its own entry");
        check(a.active() && a.deep(), "A's mode unchanged by B's entry");

        // Readiness is strictly per player in both directions.
        a.setSemanticReady(true);
        check(a.semanticReady(), "A anchored");
        check(!b.semanticReady(), "B does not inherit A's readiness");
        b.setSemanticReady(true);
        check(a.semanticReady() && b.semanticReady(), "independent readiness both directions");

        // Approach flags are per player too.
        a.setSupportApproach(true);
        check(a.supportApproach() && !b.supportApproach(), "support approach flag is per player");
        a.setLandingApproach(true);
        check(a.landingApproach() && !b.landingApproach(), "landing approach flag is per player");
    }

    /** Captured semantic targets and remembered pre-entry abilities never cross players. */
    private static void componentTargetAndAbilityIsolation() {
        SpiritNavigation a = new SpiritNavigation();
        SpiritNavigation b = new SpiritNavigation();
        a.enterDeep();
        Vec3d foot = new Vec3d(3.5, 70, 9.5);
        a.target("minecraft:overworld", "landmark-a", BlockPos.ofFloored(foot), new Basis384f());
        check(a.hasShallowTarget() && a.targetLandmarkId().equals("landmark-a"), "A captured its target");
        check(!b.hasShallowTarget() && b.targetLandmarkId().isEmpty(), "B does not inherit A's target");

        // Remembered pre-entry flight permissions are per player.
        a.rememberAbilities(true, true, true);
        b.rememberAbilities(false, false, false);
        check(a.hasSavedAbilities() && a.savedAllowFlying() && a.savedFlying() && a.savedNoGravity(),
                "A remembered its own survival flight permissions");
        check(b.hasSavedAbilities() && !b.savedAllowFlying() && !b.savedFlying() && !b.savedNoGravity(),
                "B remembered different permissions without shadowing A's");

        // Clearing B's remembered abilities cannot clear A's.
        b.clearSavedAbilities();
        check(!b.hasSavedAbilities() && a.hasSavedAbilities() && a.savedAllowFlying(),
                "A's remembered abilities survive B's clear");

        // Re-entering B does not adopt A's captured target.
        b.enterDeep();
        check(a.hasShallowTarget() && a.deep(), "A's target and mode survive B's re-entry");
        check(!b.hasShallowTarget() && b.deep(), "B re-entered without A's target");
    }

    /** Exit/deactivation of one player leaves the other player's full lifecycle state intact. */
    private static void deactivationIsolation() {
        SpiritNavigation a = new SpiritNavigation();
        SpiritNavigation b = new SpiritNavigation();
        a.enterDeep();
        a.setSemanticReady(true);
        b.shallow("minecraft:overworld", "landmark-b", new Vec3d(1.5, 64, 1.5));
        b.setSemanticReady(true);
        b.setSupportApproach(true);

        // B exits (world change/exit path): setActive(false) clears only B's state.
        b.setActive(false);
        check(!b.active() && !b.deep() && !b.semanticReady() && !b.supportApproach(),
                "B fully cleared by its own exit");
        check(a.active() && a.deep() && a.semanticReady(), "A's mode and readiness survive B's exit");

        // A's own exit likewise leaves nothing dangling for a future re-entry to inherit.
        a.setActive(false);
        check(!a.active() && !a.deep() && !a.semanticReady(), "A fully cleared by its own exit");

        // After both exits, fresh re-entries start from a clean per-player state.
        a.enterDeep();
        check(a.deep() && !a.semanticReady(), "A re-entry does not resurrect old readiness");
    }

    /** The production touch policy: a recipient's own pending lifecycle intents reject a touch. */
    private static void touchReceptionPolicy() {
        // A free deep recipient may be touched (approved mechanic preserved).
        check(TouchReception.mayDisturb(new TouchReception.Intent(false, false, false)),
                "free deep recipient may be touched");

        // Each of the recipient's own pending intents rejects the touch.
        check(!TouchReception.mayDisturb(new TouchReception.Intent(true, false, false)),
                "pending walk request (support approach) blocks an incoming touch");
        check(!TouchReception.mayDisturb(new TouchReception.Intent(false, true, false)),
                "pending landing approach blocks an incoming touch");
        check(!TouchReception.mayDisturb(new TouchReception.Intent(false, false, true)),
                "in-progress blend blocks a repeated touch (bounded per-target disruption)");

        // Combined intents fail closed as well.
        check(!TouchReception.mayDisturb(new TouchReception.Intent(true, true, true)),
                "any pending recipient intent blocks the touch");

        // The policy fails closed on a missing snapshot rather than fabricating a decision.
        fails(() -> TouchReception.mayDisturb(null), "null recipient intent must be rejected");
    }
}
