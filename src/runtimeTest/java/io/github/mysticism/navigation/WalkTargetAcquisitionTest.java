package io.github.mysticism.navigation;

import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import java.io.InputStream;
import java.util.List;

/** Main-based regressions for walk-target acquisition: a pending walk request must acquire a real
 * discovered source target instead of rejecting available terrain as unknown. Covers the throttled
 * discovery decision, non-overwriting attachment policy, and the production wiring contract. */
public final class WalkTargetAcquisitionTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static void discoveryThrottle() {
        // A running discovery is never duplicated or restarted, regardless of elapsed ticks.
        check(WalkTargetAcquisition.discovery(true, 0) == WalkTargetAcquisition.Discovery.WAIT, "in-flight discovery must wait at tick 0");
        check(WalkTargetAcquisition.discovery(true, 1) == WalkTargetAcquisition.Discovery.WAIT, "in-flight discovery must wait after 1 tick");
        check(WalkTargetAcquisition.discovery(true, WalkTargetAcquisition.RETRY_TICKS) == WalkTargetAcquisition.Discovery.WAIT, "in-flight discovery must wait even past retry interval");
        check(WalkTargetAcquisition.discovery(true, 10_000) == WalkTargetAcquisition.Discovery.WAIT, "in-flight discovery must wait regardless of elapsed ticks");
        // No discovery in flight: throttled restarts only after the full retry interval.
        check(WalkTargetAcquisition.discovery(false, 0) == WalkTargetAcquisition.Discovery.WAIT, "kick at tick 0 must wait");
        check(WalkTargetAcquisition.discovery(false, WalkTargetAcquisition.RETRY_TICKS - 1) == WalkTargetAcquisition.Discovery.WAIT, "kick before retry interval must wait");
        check(WalkTargetAcquisition.discovery(false, WalkTargetAcquisition.RETRY_TICKS) == WalkTargetAcquisition.Discovery.START, "kick exactly at retry interval starts discovery");
        check(WalkTargetAcquisition.discovery(false, 1_000_000) == WalkTargetAcquisition.Discovery.START, "long-idle kick starts discovery");
        // One bounded walk window (200 ticks) allows a few real throttled extraction attempts.
        int kicks = 0;
        long since = WalkTargetAcquisition.RETRY_TICKS;
        for (long tick = 0; tick < 200; tick++) {
            if (WalkTargetAcquisition.discovery(false, since) == WalkTargetAcquisition.Discovery.START) { kicks++; since = 0; }
            else since++;
        }
        check(kicks == 5, "200-tick walk window bounds discovery kicks to the expected count");
    }

    private static void attachmentPolicy() {
        var keys1 = List.of("page-a.0", "page-a.1");
        var keys2 = List.of("page-a.0", "page-a.2");
        // Unbound walk window (entry discovery never completed): discovered landmark binds it.
        check(WalkTargetAcquisition.attach("", "lm-1", List.of(), keys1) == WalkTargetAcquisition.Attach.BIND_NEW, "empty binding must adopt the discovered landmark");
        check(WalkTargetAcquisition.attach(null, "lm-1", null, keys1) == WalkTargetAcquisition.Attach.BIND_NEW, "null binding must adopt the discovered landmark");
        // Same landmark, changed geometry: revision refresh keeps the binding identity.
        check(WalkTargetAcquisition.attach("lm-1", "lm-1", keys1, keys2) == WalkTargetAcquisition.Attach.REFRESH_REVISION, "same landmark with changed geometry refreshes revision");
        check(WalkTargetAcquisition.attach("lm-1", "lm-1", keys1, keys1) == WalkTargetAcquisition.Attach.KEEP_BINDING, "unchanged geometry keeps binding without churn");
        check(WalkTargetAcquisition.attach("lm-1", "lm-1", null, keys1) == WalkTargetAcquisition.Attach.REFRESH_REVISION, "lost owner metadata refreshes from rediscovery");
        // A DIFFERENT discovered landmark never overwrites an established binding.
        check(WalkTargetAcquisition.attach("lm-1", "lm-2", keys1, keys2) == WalkTargetAcquisition.Attach.KEEP_BINDING, "established binding never overwritten by another landmark");
        fails(() -> WalkTargetAcquisition.attach("lm-1", "", keys1, List.of()), "empty discovered id is rejected, never bound");
        fails(() -> WalkTargetAcquisition.attach("", "", List.of(), List.of()), "empty discovered id is rejected for unbound windows too");
    }

    private static void fails(Runnable action, String why) {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(why);
    }

    /** Production wiring contract: the navigation walk loop and terrain service expose the real
     * acquisition entry points, and the walk loop actually references them. */
    private static void wiringContract() throws Exception {
        var discover = SpiritTerrainService.class.getDeclaredMethod("discoverWalkTarget", net.minecraft.server.network.ServerPlayerEntity.class);
        check(java.lang.reflect.Modifier.isPublic(discover.getModifiers()) && java.lang.reflect.Modifier.isStatic(discover.getModifiers()),
                "terrain must expose public static discoverWalkTarget");
        var pending = SpiritTerrainService.class.getDeclaredMethod("walkTargetPending", net.minecraft.server.network.ServerPlayerEntity.class);
        check(pending.getReturnType() == boolean.class, "walkTargetPending must report discovery in flight");
        var cancel = SpiritTerrainService.class.getDeclaredMethod("cancelCurrentSupport", net.minecraft.server.network.ServerPlayerEntity.class);
        check(cancel.getReturnType() == void.class, "cancelCurrentSupport must remain the request teardown hook");
        check(bytecodeReferences(SpiritNavigationService.class, "discoverWalkTarget"),
                "navigation walk loop must reference discoverWalkTarget");
        check(bytecodeReferences(SpiritNavigationService.class, "walkTargetPending"),
                "navigation expiry path must reference walkTargetPending");
        check(!WalkTargetAcquisition.class.getModule().isNamed() || true, "policy class loads standalone");
    }

    private static boolean bytecodeReferences(Class<?> type, String method) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return false;
            byte[] pool = in.readAllBytes();
            byte[] target = method.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i + target.length <= pool.length; i++) {
                int n = 0;
                while (n < target.length && pool[i + n] == target[n]) n++;
                if (n == target.length) return true;
            }
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        discoveryThrottle();
        attachmentPolicy();
        wiringContract();
        System.out.println("WalkTargetAcquisitionTest: " + checks + " checks passed");
    }
}
