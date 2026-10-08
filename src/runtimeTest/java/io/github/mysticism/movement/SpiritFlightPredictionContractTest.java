package io.github.mysticism.movement;

import com.google.gson.JsonParser;
import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Deep spirit flight must receive vanilla creative-flight movement tolerance: the vanilla
 * "moved wrongly" snap-back in ServerPlayNetworkHandler.onPlayerMove is the rubber band that
 * teleports sustained/direction-changing free flight back to its pre-move position whenever the
 * authoritative mesh frame lags client prediction. These offline contracts pin the acceptance
 * policy, the mixin registration and the exact vanilla targets the hook redirects. */
public final class SpiritFlightPredictionContractTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    private static com.google.gson.JsonObject resource(String name) throws Exception {
        var stream = SpiritFlightPredictionContractTest.class.getClassLoader().getResourceAsStream(name);
        check(stream != null, "Missing packaged resource " + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void acceptancePolicy() {
        check(SpiritNavigationService.flightPredictionTolerance(true, true, true),
                "deep spirit flight accepts client prediction instead of rubber banding");
        check(!SpiritNavigationService.flightPredictionTolerance(true, true, false),
                "shallow walking keeps ordinary vanilla movement validation");
        check(!SpiritNavigationService.flightPredictionTolerance(true, false, true),
                "inactive navigation never receives movement tolerance");
        check(!SpiritNavigationService.flightPredictionTolerance(false, true, true),
                "ordinary source worlds keep vanilla movement validation");
    }

    private static void mixinContract() throws Exception {
        var manifest = resource("fabric.mod.json");
        int bindings = 0;
        for (var entry : manifest.getAsJsonArray("mixins"))
            if (entry.isJsonPrimitive() && entry.getAsString().equals("mysticism.mixins.json")) bindings++;
        check(bindings == 1, "common mixin config registered exactly once");
        var config = resource("mysticism.mixins.json");
        check(config.get("required").getAsBoolean(), "movement hooks must not silently disappear");
        check(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1,
                "a missing vanilla movement target must fail visibly");
        var registered = new java.util.HashSet<String>();
        for (var entry : config.getAsJsonArray("mixins")) registered.add(entry.getAsString());
        check(registered.contains("SpiritFlightPredictionMixin"),
                "flight prediction acceptance hook must be registered");
        Class.forName("io.github.mysticism.mixin.SpiritFlightPredictionMixin", false,
                SpiritFlightPredictionContractTest.class.getClassLoader());
    }

    private static void vanillaTargets() throws Exception {
        // The redirect targets; a signature change must break this test, not silently drop the fix.
        check(ServerPlayNetworkHandler.class.getDeclaredMethod("onPlayerMove", PlayerMoveC2SPacket.class)
                .getReturnType() == void.class, "onPlayerMove target descriptor changed");
        check(ServerPlayerInteractionManager.class.getDeclaredMethod("isCreative").getReturnType() == boolean.class,
                "isCreative exemption target descriptor changed");
        // The vanilla moved-wrongly snap-back still exists behind the exemption (this fix redirects
        // its creative gate rather than reimplementing movement handling).
        try (var in = ServerPlayNetworkHandler.class.getResourceAsStream("/net/minecraft/server/network/ServerPlayNetworkHandler.class")) {
            check(in != null, "named Minecraft classes available on the regression classpath");
            byte[] bytecode = in.readAllBytes();
            boolean referencesCreative = false;
            for (int i = 0; i + 10 < bytecode.length; i++) {
                if (bytecode[i] == 'i' && new String(bytecode, i, 10, StandardCharsets.US_ASCII).equals("isCreative")) {
                    referencesCreative = true; break;
                }
            }
            check(referencesCreative, "onPlayerMove still consults the creative exemption gate");
        }
    }

    public static void main(String[] args) throws Exception {
        acceptancePolicy();
        mixinContract();
        vanillaTargets();
        System.out.println("SpiritFlightPredictionContractTest: " + checks + " checks passed (offline contracts)");
    }
}
