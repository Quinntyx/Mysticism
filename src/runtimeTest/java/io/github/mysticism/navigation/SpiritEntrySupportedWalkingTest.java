package io.github.mysticism.navigation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Regression: entering spirit mode standing on supported source terrain begins supported walking,
 * never unconditional flight. Covers the pure shallow-stance decision and the production wire-up.
 * No Minecraft server boot: decision logic is exercised directly, wire-up via production source. */
public final class SpiritEntrySupportedWalkingTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** Simulates update()'s shallow support bookkeeping: support confirmation resets the counter. */
    private static final class Stance {
        int unsupported;
        boolean everSupported, confirmedOwned;

        SpiritNavigationService.StanceStep tick(boolean standing, boolean jumping, boolean ownershipPending) {
            var step = SpiritNavigationService.entryStance(unsupported, everSupported, confirmedOwned,
                    standing, jumping, ownershipPending);
            unsupported = step.unsupportedTicks();
            return step;
        }
        void confirmSupport() { everSupported = true; confirmedOwned = true; unsupported = 0; }
    }

    private static void freshStandingEntryHoldsWalkingWhileOwnershipPublishes() {
        var stance = new Stance();
        // First 100 ticks (ENTRY_SUPPORT_GRACE_TICKS): standing, not jumping, ownership region still
        // in flight. Every tick must hold shallow walking; flight must never engage.
        for (int t = 0; t < SpiritNavigationService.ENTRY_SUPPORT_GRACE_TICKS; t++) {
            var step = stance.tick(true, false, true);
            check(step.stance() == SpiritNavigationService.Stance.HOLD_ENTRY_SUPPORT,
                    "Tick " + t + " of a fresh standing entry must hold supported walking, got " + step.stance());
            check(step.unsupportedTicks() == t + 1, "Hold must advance its bounded counter");
        }
        // Grace is bounded: expiry enters freeflight instead of holding forever.
        var expired = stance.tick(true, false, true);
        check(expired.stance() == SpiritNavigationService.Stance.FREE_FALL,
                "Entry-support hold must expire into freeflight after the grace budget");
        check(expired.unsupportedTicks() == SpiritNavigationService.ENTRY_SUPPORT_GRACE_TICKS + 1,
                "Expiry tick must still account its counter");
    }

    private static void ownershipResolutionEndsTheHoldAndRestoresOrdinaryRules() {
        var stance = new Stance();
        for (int t = 0; t < 30; t++)
            check(stance.tick(true, false, true).stance() == SpiritNavigationService.Stance.HOLD_ENTRY_SUPPORT,
                    "Pre-resolution ticks must hold walking");
        // Terrain published ownership; support confirms (update() resets the stance and records it).
        stance.confirmSupport();
        // Walking off the edge afterwards must still enter freeflight immediately: the hold is for
        // the initial catch-up only, never a license to walk on air.
        check(stance.tick(false, false, false).stance() == SpiritNavigationService.Stance.FREE_FALL,
                "A confirmed stance that loses support must freefall immediately");
    }

    private static void confirmedOwnershipIsNeverReheld() {
        // Ownership already confirmed for this session; support vanished while standing (e.g. an
        // ownership revision flip). Re-holding would mask a real region change.
        check(SpiritNavigationService.entryStance(0, false, true, true, false, true).stance()
                == SpiritNavigationService.Stance.FREE_FALL,
                "Confirmed-then-lost stance must not re-enter the walking hold");
    }

    private static void airborneEntryKeepsTheJumpGraceAndEdgeRules() {
        // Ascending takeoff at entry: ordinary 14-tick jump grace, then freeflight.
        var jump = new Stance();
        for (int t = 0; t < 14; t++)
            check(jump.tick(false, true, true).stance() == SpiritNavigationService.Stance.WALK,
                    "Jump tick " + t + " must keep ordinary walking grace");
        check(jump.tick(false, true, true).stance() == SpiritNavigationService.Stance.FREE_FALL,
                "Jump grace must expire into freeflight after 14 ticks");
        // Walking over an edge at entry: immediate freeflight, no hold.
        check(SpiritNavigationService.entryStance(0, false, false, false, false, true).stance()
                == SpiritNavigationService.Stance.FREE_FALL,
                "An unsupported edge walk-off must freefall immediately");
        // Standing on genuinely unowned ground (ownership resolved, nothing pending): no hold.
        check(SpiritNavigationService.entryStance(0, false, false, true, false, false).stance()
                == SpiritNavigationService.Stance.FREE_FALL,
                "Resolved-unowned ground must not hold walking");
        // Established bindings do not matter here: the hold keys on session-confirmed state only.
        check(SpiritNavigationService.entryStance(0, true, false, true, false, true).stance()
                == SpiritNavigationService.Stance.FREE_FALL,
                "A session that already had support must not re-enter the hold");
    }

    /** The decision is only real if update()/enter() actually wire it that way in production source. */
    private static void productionWireUp() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java")))
            root = root.getParent();
        check(root != null, "Could not locate production source root from " + Paths.get("").toAbsolutePath());
        String source = Files.readString(root.resolve("src/main/java/io/github/mysticism/navigation/SpiritNavigationService.java"));

        int update = source.indexOf("public static boolean update(");
        int afterUpdate = source.indexOf("private static void restoreAnchor(ServerPlayerEntity p, Session s) {");
        check(update >= 0 && afterUpdate > update, "update() body must precede restoreAnchor");
        String updateBody = source.substring(update, afterUpdate);
        check(updateBody.contains("SpiritTerrainService.ownershipPending(p)"),
                "Shallow stance decision must consult live octree ownership publication state");
        check(updateBody.contains("p.isOnGround()"),
                "Standing detection must use the physical ground state");
        check(Pattern.compile("entryStance\\(").matcher(updateBody).find(),
                "update() must route shallow support through the shared stance decision");
        check(updateBody.contains("s.everSupported = true"),
                "Confirmed support must permanently end the entry hold for the session");
        check(updateBody.contains("Stance.HOLD_ENTRY_SUPPORT") && updateBody.contains("flight(p, false)"),
                "The walking hold must keep flight off");
        check(updateBody.contains("Stance.WALK"), "Ordinary jump-grace walking must remain a stance outcome");
        check(updateBody.contains("Stance.FREE_FALL") && updateBody.contains("enterDeep(p)"),
                "Freefall stances must still enter deep flight");

        int enter = source.indexOf("public static boolean enter(ServerPlayerEntity p)");
        int endEnter = source.indexOf("private static void anchorSource(ServerPlayerEntity p, Session s");
        check(enter >= 0 && endEnter > enter, "enter() must exist before its anchor helper");
        String enterBody = source.substring(enter, endEnter);
        check(enterBody.contains("SpiritTerrainService.setShallow(p, true)"),
                "Entry must begin in shallow walking mode");
        check(enterBody.contains("flight(p, false)"), "Entry must start with flight off");
        check(!enterBody.contains("flight(p, true)"), "Entry must never engage flight unconditionally");
    }

    public static void main(String[] args) {
        freshStandingEntryHoldsWalkingWhileOwnershipPublishes();
        ownershipResolutionEndsTheHoldAndRestoresOrdinaryRules();
        confirmedOwnershipIsNeverReheld();
        airborneEntryKeepsTheJumpGraceAndEdgeRules();
        try {
            productionWireUp();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println("SpiritEntrySupportedWalkingTest: " + checks + " checks passed (stance decision + production wire-up)");
    }
}
