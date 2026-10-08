package io.github.mysticism.movement;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/** Applies the real production mixin to real named Minecraft bytecode, without booting a game.
 * Each application runs in a fresh JVM: Mixin's global state must not leak into other tests.
 * The missing-receiver mutation must fail injection, not merely a static signature assertion. */
public final class SpiritFlightMixinApplicationTest {
    private static final String TARGET = "net.minecraft.server.network.ServerPlayNetworkHandler";
    private static final String MIXIN = "io.github.mysticism.mixin.SpiritFlightPredictionMixin";
    private static final String RECEIVER = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;";
    private static final String CONFIG = "mysticism-flight-application-test.json";

    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        for (String mode : List.of("production", "missing-receiver")) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-cp", System.getProperty("java.class.path"), Harness.class.getName(), mode)
                    .inheritIO().start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Mixin application timed out: " + mode);
            }
            check(child.exitValue() == 0, "Mixin application regression failed: " + mode);
        }
        System.out.println("SpiritFlightMixinApplicationTest: production injection and missing-receiver rejection passed");
    }

    public static final class Harness {
        public static void main(String[] args) throws Exception {
            boolean mutant = args[0].equals("missing-receiver");
            System.setProperty("mixin.service", FlightMixinTestService.class.getName());
            System.setProperty("mysticism.test.missingReceiver", Boolean.toString(mutant));
            // Fabric's existing property service normally receives this map from its launcher.
            // No launcher, registries, game initialization or embedding service is needed here.
            var properties = net.fabricmc.loader.impl.launch.FabricLauncherBase.class
                    .getDeclaredMethod("setProperties", java.util.Map.class);
            properties.setAccessible(true);
            properties.invoke(null, new java.util.HashMap<String, Object>());
            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
            Mixins.addConfiguration(CONFIG);
            var service = (FlightMixinTestService) MixinService.getService();
            IMixinTransformer transformer = service.transformer();
            byte[] original = bytes(TARGET);
            var before = node(original);
            check(calls(move(before), before.name, "isPlayerNotCollidingWithBlocks") == 1,
                    "Expected one real vanilla collision gate in onPlayerMove");
            for (var instruction : move(before).instructions)
                if (instruction instanceof MethodInsnNode call && call.name.equals("isPlayerNotCollidingWithBlocks"))
                    check(call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(before.name),
                            "Collision gate must be an invokevirtual requiring a receiver");
            check(calls(move(before), "net/minecraft/server/network/ServerPlayerInteractionManager", "isCreative") == 1,
                    "Expected one real vanilla creative gate in onPlayerMove");
            try {
                byte[] transformed = transformer.transformClass(MixinEnvironment.getDefaultEnvironment(), TARGET, original);
                check(!mutant, "Mixin accepted a collision redirect without its invokevirtual receiver");
                var after = node(transformed);
                var movement = move(after);
                check(calls(movement, after.name, "isPlayerNotCollidingWithBlocks") == 0,
                        "Vanilla collision call was not redirected");
                check(calls(movement, "net/minecraft/server/network/ServerPlayerInteractionManager", "isCreative") == 0,
                        "Vanilla creative call was not redirected");
                MethodNode collision = hook(after, "$meshMoveValidation");
                MethodNode creative = hook(after, "$flightPredictionTolerance");
                check(collision.desc.startsWith("(" + RECEIVER + "Lnet/minecraft/world/WorldView;"),
                        "Applied non-static redirect must consume the invocation receiver before WorldView");
                check(calls(movement, after.name, collision.name) == 1 && calls(movement, after.name, creative.name) == 1,
                        "Both applied hooks must be called by real onPlayerMove");
                check(calls(collision, "io/github/mysticism/dimension/spiritworld/terrain/MeshMovementValidation", "allowsMeshMove") == 1,
                        "Applied collision hook must still validate actual mesh motion");
                check(calls(collision, after.name, "isPlayerNotCollidingWithBlocks") == 1,
                        "Applied hook must preserve the vanilla fallback");
                for (MethodNode method : List.of(movement, collision, creative))
                    new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
                System.out.println("Applied production SpiritFlightPredictionMixin to actual ServerPlayNetworkHandler; both redirects and stack flow verified");
            } catch (org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError failure) {
                if (!mutant) throw failure;
                Throwable cause = failure;
                while (cause != null && !(cause instanceof org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException))
                    cause = cause.getCause();
                check(cause != null && cause.getMessage().contains("invalid signature")
                                && cause.getMessage().contains("meshMoveValidation"),
                        "Negative control must reproduce the receiver-signature injection error: " + failure);
                System.out.println("Missing-receiver negative control correctly rejected by Mixin: " + cause.getMessage());
            }
        }
    }

    static byte[] bytes(String name) throws IOException, ClassNotFoundException {
        try (InputStream in = SpiritFlightMixinApplicationTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new ClassNotFoundException(name);
            return in.readAllBytes();
        }
    }

    static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode move(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals("onPlayerMove")
                && m.desc.equals("(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V"))
                .findFirst().orElseThrow();
    }

    private static MethodNode hook(ClassNode node, String suffix) {
        return node.methods.stream().filter(m -> m.name.endsWith(suffix)).findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        return count;
    }

    static InputStream config() throws IOException {
        // Keep production required/defaultRequire/compatibility settings; isolate only this hook,
        // since other mixins require Fabric access wideners or unrelated registry lifecycles.
        try (var in = SpiritFlightMixinApplicationTest.class.getClassLoader().getResourceAsStream("mysticism.mixins.json")) {
            check(in != null, "Missing production mixin configuration");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            check(json.get("required").getAsBoolean(), "Production mixin must be required");
            check(json.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1, "Production hooks must require injection");
            boolean registered = false;
            for (var entry : json.getAsJsonArray("mixins"))
                registered |= entry.getAsString().equals("SpiritFlightPredictionMixin");
            check(registered, "Production config must register the flight mixin");
            var mixins = new com.google.gson.JsonArray();
            mixins.add("SpiritFlightPredictionMixin");
            json.add("mixins", mixins);
            return new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static ClassNode suppliedNode(String name) throws IOException, ClassNotFoundException {
        ClassNode node = node(bytes(name));
        if (name.replace('/', '.').equals(MIXIN) && Boolean.getBoolean("mysticism.test.missingReceiver")) {
            MethodNode redirect = hook(node, "$meshMoveValidation");
            check(redirect.desc.startsWith("(" + RECEIVER), "Negative control requires the corrected production signature");
            redirect.desc = "(" + redirect.desc.substring(1 + RECEIVER.length());
            // Only the signature is mutated: Mixin must reject it before trying to inject code.
        }
        return node;
    }
}
