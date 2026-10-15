package io.github.mysticism.movement;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;

/** Applies the retained travel/accessor and entry mixins to actual Minecraft bytecode. This is
 * injection/stack verification, not a client launch or a stand-in for live gameplay testing. */
public final class SpiritIntegratedMixinApplicationTest {
    static final String CONFIG = "mysticism-integrated-application-test.json";
    private static final String PREFIX = "io/github/mysticism/";

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        for (String side : List.of("common", "client")) {
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-cp", System.getProperty("java.class.path"), Harness.class.getName(), side)
                    .inheritIO().start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Integrated mixin application timed out: " + side);
            }
            check(process.exitValue() == 0, "Integrated mixin application failed: " + side);
        }
        System.out.println("SpiritIntegratedMixinApplicationTest: combined collision/walk/flight/travel/accessor and both entry hooks applied");
    }

    static InputStream config() {
        boolean client = Boolean.getBoolean("mysticism.test.integratedClient");
        String resource = client ? "mysticism.client.mixins.json" : "mysticism.mixins.json";
        List<String> retained = client ? List.of("SpiritEntryWorldMixin", "SpiritEntryLoadingMixin")
                : List.of("SpiritFlightPredictionMixin", "SpiritMeshMovementMixin", "SpiritFlightToggleMixin",
                        "SpiritMeshCollisionMixin", "SpiritMeshSneakMixin", "SpiritTravelMixin", "EntityFlagAccess");
        try (var stream = SpiritIntegratedMixinApplicationTest.class.getClassLoader().getResourceAsStream(resource)) {
            check(stream != null, "Missing production config " + resource);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            check(json.get("required").getAsBoolean(), "Integrated hooks must be required");
            var entries = json.getAsJsonArray(client ? "client" : "mixins");
            var selected = new JsonArray();
            for (String hook : retained) {
                int count = 0;
                for (var entry : entries) if (entry.getAsString().equals(hook)) count++;
                check(count == 1, "Retain exactly one registration for " + hook);
                selected.add(hook);
            }
            // Unrelated production hooks require Fabric access wideners and game lifecycles.
            json.remove("client");
            json.add("mixins", selected);
            return new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static long calls(MethodNode method, String owner, String name) {
        long count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        return count;
    }

    public static final class Harness {
        public static void main(String[] args) throws Exception {
            boolean client = args[0].equals("client");
            System.setProperty("mysticism.test.integratedClient", Boolean.toString(client));
            System.setProperty("mixin.service", FlightMixinTestService.class.getName());
            var properties = net.fabricmc.loader.impl.launch.FabricLauncherBase.class
                    .getDeclaredMethod("setProperties", java.util.Map.class);
            properties.setAccessible(true);
            properties.invoke(null, new java.util.HashMap<String, Object>());
            MixinBootstrap.init();
            var environment = MixinEnvironment.getDefaultEnvironment();
            environment.setSide(client ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);
            Mixins.addConfiguration(CONFIG);
            var transformer = ((FlightMixinTestService) MixinService.getService()).transformer();
            List<String> targets = client ? List.of("net.minecraft.client.MinecraftClient",
                    "net.minecraft.client.network.ClientPlayNetworkHandler")
                    : List.of("net.minecraft.entity.Entity", "net.minecraft.entity.player.PlayerEntity",
                    "net.minecraft.server.network.ServerPlayNetworkHandler");
            for (String target : targets) {
                byte[] original = SpiritFlightMixinApplicationTest.bytes(target);
                var before = SpiritFlightMixinApplicationTest.node(original);
                var after = SpiritFlightMixinApplicationTest.node(transformer.transformClass(environment, target, original));
                if (target.endsWith("PlayerEntity")) {
                    var living = SpiritFlightMixinApplicationTest.node(
                            SpiritFlightMixinApplicationTest.bytes("net.minecraft.entity.LivingEntity"));
                    int horizontalScales = 0;
                    for (var instruction : method(living, "tickMovement").instructions)
                        if (instruction instanceof LdcInsnNode constant
                                && Float.valueOf(SpiritMovement.VANILLA_HORIZONTAL_INPUT).equals(constant.cst)) horizontalScales++;
                    check(horizontalScales == 2, "Vanilla horizontal input compensation must match both actual axis scales");
                    var movement = SpiritFlightMixinApplicationTest.node(
                            SpiritFlightMixinApplicationTest.bytes("io.github.mysticism.movement.SpiritMovement"));
                    check(calls(method(movement, "travel"), movement.name, "flightDirection") == 1,
                            "Production travel must use live-input compensation, not only the test model");
                    MethodNode travel = method(after, "travel");
                    var hook = after.methods.stream().filter(m -> m.name.endsWith("$consistentFlight")).findFirst().orElseThrow();
                    check(calls(travel, after.name, hook.name) == 1, "Travel must call the applied movement hook");
                    check(calls(hook, PREFIX + "movement/SpiritMovement", "travel") == 1,
                            "Applied hook must delegate to the real movement model");
                    check(calls(hook, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel") == 1,
                            "Handled flight must cancel vanilla travel, avoiding double integration");
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, travel);
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, hook);
                } else if (target.equals("net.minecraft.entity.Entity")) {
                    check(after.interfaces.contains(PREFIX + "mixin/EntityFlagAccess"), "Entity must implement the flag invoker");
                    check(calls(method(after, "mysticism$setFlag"), after.name, "setFlag") == 1,
                            "Flag invoker must delegate to real vanilla setFlag");
                } else if (target.endsWith("ServerPlayNetworkHandler")) {
                    check(calls(method(before, "onPlayerMove"), before.name, "isPlayerNotCollidingWithBlocks") == 1,
                            "Vanilla packet collision call must be present before application");
                    check(calls(method(after, "onPlayerMove"), after.name, "isPlayerNotCollidingWithBlocks") == 0,
                            "Flight reconciliation must survive the combined common config");
                    var packet = method(after, "onPlayerMove");
                    for (String suffix : List.of("$flightPredictionTolerance", "$meshMoveValidation",
                            "$spiritShortCircuit", "$beforeMove", "$acceptedMove")) {
                        var hook = after.methods.stream().filter(m -> m.name.endsWith(suffix)).findFirst().orElseThrow();
                        check(calls(packet, after.name, hook.name) > 0, "Combined movement path lost " + suffix);
                        new Analyzer<>(new BasicVerifier()).analyze(after.name, hook);
                    }
                    var meshVerdict = after.methods.stream().filter(m -> m.name.endsWith("$meshVerdict")).findFirst().orElseThrow();
                    check(calls(method(after,"isPlayerNotCollidingWithBlocks"), after.name, meshVerdict.name) == 1,
                            "Walking fallback must call the installed shared mesh verdict");
                    check(calls(meshVerdict, PREFIX + "dimension/spiritworld/terrain/MeshCollision", "movementRejected") == 1,
                            "Walking verdict must use actual production collision validation");
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, packet);
                } else {
                    String entry = target.endsWith("MinecraftClient") ? "joinWorld" : "startWorldLoading";
                    String vanilla = target.endsWith("MinecraftClient") ? "reset" : "setScreen";
                    String owner = "net/minecraft/client/MinecraftClient";
                    check(calls(method(before, entry), owner, vanilla) == 1, "Vanilla entry GUI call present");
                    check(calls(method(after, entry), owner, vanilla) == 0, "Entry GUI call actually redirected");
                    var hook = after.methods.stream().filter(m -> m.name.endsWith("$keepSourceView")
                            || m.name.endsWith("$keepSpiritView")).findFirst().orElseThrow();
                    check(calls(method(after, entry), after.name, hook.name) == 1, "Entry must invoke its applied GUI hook");
                    check(calls(hook, PREFIX + "client/spiritworld/SpiritEntryTransition", "hideLoadingScreen") == 1,
                            "Applied entry hook must retain the live-source policy");
                    check(calls(hook, owner, vanilla) == 1, "Applied entry hook must retain vanilla fallback/reset");
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, method(after, entry));
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, hook);
                }
                System.out.println("Applied integrated hooks to actual " + target);
            }
        }
    }
}
