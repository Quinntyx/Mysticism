package io.github.mysticism.navigation;

/** Tests the production per-session clock, not a replacement navigation/terrain implementation. */
public final class WalkRequestRecoveryTest {
    public static void main(String[] args) {
        lateDiscoveryDoesNotConsumeAlignment();
        proofWaitDoesNotResetSpentAlignment();
        interleavedSourceWaitsRemainBounded();
        suspendedRequestResumesOnce();
        recoveryIsBounded();
        failedAlignmentNeverArmsRecovery();
        currentContactBeatsBackgroundStreaming();
        cancellationAndFreshRequest();
        independentPlayers();
        System.out.println("WalkRequestRecoveryTest: 9 timing/recovery regressions passed (no game bootstrap)");
    }

    private static void lateDiscoveryDoesNotConsumeAlignment() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        // The original 200-tick controller expired before this genuine async arrival.
        for (int tick = 0; tick < 400; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "slow discovery expired");
        for (int tick = 0; tick < WalkRequestClock.ALIGNMENT_TICKS; tick++) check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "wait consumed alignment");
        check(clock.tick(true, false) == WalkRequestClock.Phase.EXPIRE, "alignment deadline not enforced");
        check(!clock.armed(), "ordinary alignment failure retained intent");
    }

    private static void proofWaitDoesNotResetSpentAlignment() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        for (int tick = 0; tick < 150; tick++) check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "early alignment expiry");
        for (int tick = 0; tick < 500; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "material proof wait expired");
        for (int tick = 0; tick < 50; tick++) check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "alignment not preserved across proof wait");
        check(clock.tick(true, false) == WalkRequestClock.Phase.EXPIRE, "async wait reset alignment budget");
    }

    private static void interleavedSourceWaitsRemainBounded() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        for (int tick = 0; tick < 500; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "initial source wait expired");
        for (int tick = 0; tick < 10; tick++) check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "intermittent readiness failed");
        for (int tick = 0; tick < 100; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "cumulative wait too short");
        check(clock.tick(false, true) == WalkRequestClock.Phase.EXPIRE_RECOVERABLE && clock.armed(), "ready gap reset source wait budget");
    }

    private static void suspendedRequestResumesOnce() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue(); suspend(clock);
        for (int tick = 0; tick < 300; tick++) check(!clock.recover(false) && clock.armed(), "resumed without actual ready support");
        check(clock.recover(true), "late geometry did not resume issued intent");
        check(!clock.armed() && clock.recoveries() == 1, "recovery did not consume allowance");
        check(!clock.recover(true), "same arrival resumed twice");
        check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "resumed alignment failed");
        clock.disarm();
        check(!clock.recover(true), "successful/cancelled intent revived");
    }

    private static void recoveryIsBounded() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        for (int recovery = 1; recovery <= WalkRequestClock.MAX_RECOVERIES; recovery++) {
            suspend(clock);
            check(clock.recover(true) && clock.recoveries() == recovery, "wrong recovery allowance");
        }
        for (int tick = 0; tick < WalkRequestClock.SOURCE_WAIT_TICKS; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "final wait too short");
        check(clock.tick(false, true) == WalkRequestClock.Phase.EXPIRE && !clock.armed(), "unbounded automatic retries");

        clock.reissue(); suspend(clock);
        for (int tick = 0; tick < WalkRequestClock.RECOVERY_WAIT_TICKS; tick++) check(!clock.recover(false) && clock.armed(), "dormant deadline too short");
        check(!clock.recover(true) && !clock.armed(), "expired dormant intent revived on deadline race");
        check(!clock.recover(true), "intent revived after recovery timeout");
    }

    private static void failedAlignmentNeverArmsRecovery() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        // Alternating async waits cannot give alignment an unlimited budget.
        for (int tick = 0; tick < WalkRequestClock.ALIGNMENT_TICKS; tick++) {
            check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "short wait failed");
            check(clock.tick(true, false) == WalkRequestClock.Phase.ALIGN, "early interleaved expiry");
        }
        check(clock.tick(true, false) == WalkRequestClock.Phase.EXPIRE, "interleaved waits evade deadline");
        check(!clock.recover(true), "geometric alignment failure auto-retried");
    }

    private static void currentContactBeatsBackgroundStreaming() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue();
        for (int tick = 0; tick < WalkRequestClock.ALIGNMENT_TICKS; tick++)
            check(clock.tick(true, true) == WalkRequestClock.Phase.ALIGN, "background source streaming blocked ready current support");
        check(clock.tick(true, true) == WalkRequestClock.Phase.EXPIRE && !clock.armed(), "background IO bypassed alignment limit");
        clock.reissue();
        for (int tick = 0; tick < WalkRequestClock.ALIGNMENT_TICKS; tick++)
            check(clock.tick(false, false) == WalkRequestClock.Phase.ALIGN, "absent contact with no pending source retained wait");
        check(clock.tick(false, false) == WalkRequestClock.Phase.EXPIRE && !clock.armed(), "final unowned/unsupported contact incorrectly armed recovery");
    }

    private static void cancellationAndFreshRequest() {
        WalkRequestClock clock = new WalkRequestClock(); clock.reissue(); suspend(clock);
        clock.disarm(); check(!clock.recover(true), "superseded intent revived");
        clock.reissue(); suspend(clock); check(clock.recover(true), "new issued intent failed");
        clock.restart(); check(clock.recoveries() == 1, "continuation reset allowance");
        clock.reissue(); check(clock.recoveries() == 0 && !clock.armed(), "fresh request not reset");
    }

    private static void independentPlayers() {
        WalkRequestClock first = new WalkRequestClock(), second = new WalkRequestClock();
        first.reissue(); second.reissue(); suspend(first);
        check(second.tick(true, false) == WalkRequestClock.Phase.ALIGN && !second.armed(), "player wait shared");
        check(first.recover(true), "first player recovery failed");
        check(second.recoveries() == 0, "player recovery allowance shared");
        second.disarm(); check(first.tick(true, false) == WalkRequestClock.Phase.ALIGN, "cancellation crossed player session");
    }

    private static void suspend(WalkRequestClock clock) {
        for (int tick = 0; tick < WalkRequestClock.SOURCE_WAIT_TICKS; tick++) check(clock.tick(false, true) == WalkRequestClock.Phase.WAIT_SOURCE, "source budget too short");
        check(clock.tick(false, true) == WalkRequestClock.Phase.EXPIRE_RECOVERABLE && clock.armed(), "late arrival recovery not armed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
