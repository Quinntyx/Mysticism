package io.github.mysticism.movement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Offline registration/descriptor contracts for the movement provenance hooks. Not a claim of live
 * mixin execution: the hooks must stay registered and their mapped targets must keep matching. */
public final class SpiritMovementHookContractSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static JsonObject resource(String name) throws Exception {
        var stream = SpiritMovementHookContractSelfTest.class.getClassLoader().getResourceAsStream(name);
        check(stream != null, "Missing packaged resource " + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static Set<String> strings(JsonObject config, String member) {
        Set<String> names = new HashSet<>();
        for (JsonElement entry : config.getAsJsonArray(member)) names.add(entry.getAsString());
        return names;
    }
    public static void main(String[] args) throws Exception {
        var manifest = resource("fabric.mod.json");
        int common = 0, client = 0;
        for (var entry : manifest.getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive() && entry.getAsString().equals("mysticism.mixins.json")) common++;
            if (entry.isJsonObject() && entry.getAsJsonObject().get("config").getAsString().equals("mysticism.client.mixins.json")) client++;
        }
        check(common == 1 && client == 1, "Movement hooks must register through the existing mixin configs exactly once each");
        var commonConfig = resource("mysticism.mixins.json");
        var commonHooks = strings(commonConfig, "mixins");
        check(commonHooks.contains("SpiritTeleportCorrectionMixin"), "Server teleport provenance hook must be registered");
        check(commonConfig.get("required").getAsBoolean() && commonConfig.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1,
                "Movement hooks must fail visibly when their target disappears");
        var clientConfig = resource("mysticism.client.mixins.json");
        var clientHooks = strings(clientConfig, "client");
        check(clientHooks.contains("SpiritPositionCorrectionMixin"), "Client position-correction hook must be registered");
        check(clientConfig.get("required").getAsBoolean() && clientConfig.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1,
                "Client movement hooks must fail visibly when their target disappears");
        // Existing movement hooks must survive unchanged.
        check(commonHooks.containsAll(Set.of("SpiritMeshCollisionMixin", "SpiritMeshSneakMixin", "SpiritFlightToggleMixin")),
                "Existing spirit movement hooks must stay registered");
        // The mapped targets the hooks inject into.
        ServerPlayNetworkHandler.class.getDeclaredMethod("requestTeleport",
                double.class, double.class, double.class, float.class, float.class, Set.class);
        ClientPlayNetworkHandler.class.getDeclaredMethod("onPlayerPositionLook", PlayerPositionLookS2CPacket.class);
        checks += 2;
        // The shared accounting/Classification surface both integrators consume.
        Class.forName("io.github.mysticism.navigation.MovementProvenance");
        Class.forName("io.github.mysticism.navigation.MovementIntegration");
        Class.forName("io.github.mysticism.mixin.SpiritTeleportCorrectionMixin");
        Class.forName("io.github.mysticism.client.mixin.SpiritPositionCorrectionMixin");
        checks += 4;
        System.out.println("SpiritMovementHookContractSelfTest: " + checks + " checks passed (offline contracts only)");
    }
}
