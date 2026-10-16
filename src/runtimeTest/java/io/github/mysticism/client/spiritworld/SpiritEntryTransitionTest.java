package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Screen policy and actual mapped bytecode contracts; no game, mixin bootstrap or GPU needed. */
public final class SpiritEntryTransitionTest {
    private static final String CLIENT = "net/minecraft/client/MinecraftClient";
    private static final String HANDLER = "net/minecraft/client/network/ClientPlayNetworkHandler";
    private static final String PLAYER = "net/minecraft/client/network/ClientPlayerEntity";
    private static final String WORLD = "net/minecraft/client/world/ClientWorld";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screen/Screen;";
    private static final String REASON = "Lnet/minecraft/client/gui/screen/DownloadingTerrainScreen$WorldEntryReason;";
    private static final String MIXINS = "io/github/mysticism/client/mixin/";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var stream = SpiritEntryTransitionTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            check(stream != null, "Missing compiled class " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        var matches = type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        check(matches.size() == 1, "Missing/ambiguous target " + type.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static int call(MethodNode method, String owner, String name, String descriptor) {
        int index = -1;
        for (int i = 0; i < method.instructions.size(); i++) {
            var instruction = method.instructions.get(i);
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(owner)
                    && invoke.name.equals(name) && invoke.desc.equals(descriptor)) {
                check(index < 0, "More than one invocation of " + owner + "." + name);
                index = i;
            }
        }
        check(index >= 0, "Missing invocation " + owner + "." + name);
        return index;
    }

    private static int field(MethodNode method, String owner, String name, int opcode) {
        for (int i = 0; i < method.instructions.size(); i++) {
            var instruction = method.instructions.get(i);
            if (instruction instanceof FieldInsnNode access && access.owner.equals(owner)
                    && access.name.equals(name) && access.getOpcode() == opcode) return i;
        }
        throw new AssertionError("Missing field access " + owner + "." + name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation value " + key);
    }

    private static void redirect(String type, String name, String descriptor, String targetMethod,
                                 String invocation) throws Exception {
        var mixin = bytecode(MIXINS + type);
        var hook = method(mixin, name, descriptor);
        var annotations = new java.util.ArrayList<AnnotationNode>();
        if (hook.visibleAnnotations != null) annotations.addAll(hook.visibleAnnotations);
        if (hook.invisibleAnnotations != null) annotations.addAll(hook.invisibleAnnotations);
        check(annotations.size() == 1, "Expected one redirect annotation");
        var annotation = annotations.getFirst();
        check(annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;"),
                "Entry must redirect the GUI call, not cancel the transfer method");
        check(value(annotation, "method").equals(List.of(targetMethod)), "Wrong method selector");
        var at = (AnnotationNode) value(annotation, "at");
        check(value(at, "value").equals("INVOKE") && value(at, "target").equals(invocation),
                "Wrong redirected invocation");
        check(value(annotation, "index").equals(0), "Only GUI argument may be changed");
        call(hook, "io/github/mysticism/client/spiritworld/SpiritTransitionClient", "respawning", "()Z");
        for (var instruction : hook.instructions) {
            check(instruction.getOpcode() != Opcodes.PUTFIELD && instruction.getOpcode() != Opcodes.PUTSTATIC,
                    "GUI hook must not mutate player/world/loading fields");
            if (instruction instanceof MethodInsnNode invoke)
                check(!invoke.name.equals("cancel") && !invoke.name.equals("sendPacket")
                                && !invoke.name.equals("handleChunksComingPacket"),
                        "GUI hook must not cancel or fabricate protocol state");
        }
    }

    private static void policy() {
        String spirit = "mysticism:spirit";
        // All source dimensions, including modded worlds, may enter with a live source scene.
        for (String source : new String[]{"minecraft:overworld", "minecraft:the_nether",
                "minecraft:the_end", "example:moon"}) {
            check(SpiritEntryTransition.hideLoadingScreen(source, spirit, true), "Live entry " + source);
            check(!SpiritEntryTransition.hideLoadingScreen(source, spirit, false), "Death respawn " + source);
            for (String destination : new String[]{source, "minecraft:overworld", "example:moon", ""})
                check(!SpiritEntryTransition.hideLoadingScreen(source, destination, true),
                        "Non-entry transition " + source + " -> " + destination);
        }
        check(!SpiritEntryTransition.hideLoadingScreen(null, spirit, true), "Login has no source world");
        check(!SpiritEntryTransition.hideLoadingScreen("", spirit, true), "Missing source is not entry");
        check(!SpiritEntryTransition.hideLoadingScreen(spirit, spirit, true), "Same-world respawn/login");
        check(!SpiritEntryTransition.hideLoadingScreen(spirit, "minecraft:overworld", true), "Exit");
        check(!SpiritEntryTransition.hideLoadingScreen("minecraft:overworld", null, true), "Missing destination");
        check(!SpiritEntryTransition.hideLoadingScreen("minecraft:overworld", "other:spirit", true),
                "Dimension path alone must not match");
    }

    private static void registration() throws Exception {
        var loader = SpiritEntryTransitionTest.class.getClassLoader();
        try (var stream = loader.getResourceAsStream("mysticism.client.mixins.json")) {
            check(stream != null, "Missing client mixin config");
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            check(config.get("required").getAsBoolean(), "Required client hooks");
            check(config.get("package").getAsString().equals("io.github.mysticism.client.mixin"), "Mixin package");
            for (String hook : List.of("SpiritJoinWorldHandoffMixin", "SpiritRespawnHandoffMixin")) {
                int count = 0;
                for (var entry : config.getAsJsonArray("client")) if (entry.getAsString().equals(hook)) count++;
                check(count == 1, "Register " + hook + " exactly once");
            }
        }
        try (var stream = loader.getResourceAsStream("fabric.mod.json")) {
            check(stream != null, "Missing mod manifest");
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            int count = 0;
            for (var entry : config.getAsJsonArray("mixins"))
                if (entry.isJsonObject() && entry.getAsJsonObject().get("config").getAsString()
                        .equals("mysticism.client.mixins.json")) {
                    check(entry.getAsJsonObject().get("environment").getAsString().equals("client"),
                            "Entry GUI hooks must not load on a dedicated server");
                    count++;
                }
            check(count == 1, "Register client mixin config once");
        }
    }

    private static void vanillaContracts() throws Exception {
        var client = bytecode(CLIENT);
        var handler = bytecode(HANDLER);
        var join = method(client, "joinWorld", "(L" + WORLD + ";" + REASON + ")V");
        int reset = call(join, CLIENT, "reset", "(" + SCREEN + ")V");
        check(reset < field(join, CLIENT, "world", Opcodes.PUTFIELD),
                "joinWorld hook must still see the old source world");
        var resetBody = method(client, "reset", "(" + SCREEN + ")V");
        int screen = call(resetBody, CLIENT, "setScreen", "(" + SCREEN + ")V");
        check(field(resetBody, CLIENT, "cameraEntity", Opcodes.PUTFIELD) < screen,
                "Keep vanilla camera reset even when GUI is hidden");
        check(screen < call(resetBody, CLIENT, "render", "(Z)V"), "Keep forced source render after GUI decision");

        var respawn = method(handler, "onPlayerRespawn",
                "(Lnet/minecraft/network/packet/s2c/play/PlayerRespawnS2CPacket;)V");
        int joined = call(respawn, CLIENT, "joinWorld", "(L" + WORLD + ";" + REASON + ")V");
        int loading = call(respawn, HANDLER, "startWorldLoading", "(L" + PLAYER + ";L" + WORLD + ";" + REASON + ")V");
        check(joined < loading && loading < field(respawn, CLIENT, "player", Opcodes.PUTFIELD),
                "Second hook must see new client.world but OLD client.player");
        var start = method(handler, "startWorldLoading", "(L" + PLAYER + ";L" + WORLD + ";" + REASON + ")V");
        int setScreen = call(start, CLIENT, "setScreen", "(" + SCREEN + ")V");
        check(call(start, "net/minecraft/client/network/WorldLoadingState", "<init>",
                "(L" + PLAYER + ";L" + WORLD + ";Lnet/minecraft/client/render/WorldRenderer;)V")
                < field(start, HANDLER, "worldLoadingState", Opcodes.PUTFIELD), "Keep vanilla loading-state construction");
        check(field(start, HANDLER, "worldLoadingState", Opcodes.PUTFIELD) < setScreen,
                "Screen suppression must happen AFTER installing loading state");
        // Loading advances outside Screen.tick; hiding a screen must not stall the chunk handshake.
        call(method(handler, "tick", "()V"), "net/minecraft/client/network/WorldLoadingState", "tick", "()V");
        call(method(handler, "onGameStateChange", "(Lnet/minecraft/network/packet/s2c/play/GameStateChangeS2CPacket;)V"),
                "net/minecraft/client/network/WorldLoadingState", "handleChunksComingPacket", "()V");
        var position = method(handler, "onPlayerPositionLook",
                "(Lnet/minecraft/network/packet/s2c/play/PlayerPositionLookS2CPacket;)V");
        check(java.util.stream.StreamSupport.stream(position.instructions.spliterator(), false).anyMatch(i ->
                i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && type.desc.equals("net/minecraft/network/packet/c2s/play/TeleportConfirmC2SPacket")),
                "Vanilla teleport confirmation remains in the unmodified position handler");

        redirect("SpiritJoinWorldHandoffMixin", "mysticism$omitResetScreen",
                "(" + SCREEN + ")" + SCREEN, "joinWorld",
                "L" + CLIENT + ";reset(" + SCREEN + ")V");
        redirect("SpiritRespawnHandoffMixin", "mysticism$omitTerrainScreen",
                "(" + SCREEN + ")" + SCREEN, "startWorldLoading",
                "L" + CLIENT + ";setScreen(" + SCREEN + ")V");
    }

    public static void main(String[] args) throws Exception {
        policy();
        registration();
        vanillaContracts();
        checks += SpiritEntryHookHarness.run();
        System.out.println("SpiritEntryTransitionTest: " + checks
                + " checks passed (policy + mapped bytecode + remapped hook execution; no live game)");
    }
}
