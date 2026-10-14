package io.github.mysticism.navigation;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Offline bytecode/resource contracts. Does not bootstrap Minecraft or apply live mixins. */
public final class SpiritTransitionContractTest {
    private record Call(String owner, String name, String descriptor) {}
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static List<Call> calls(String className, String method) throws Exception {
        List<Call> calls = new ArrayList<>();
        try (var input = SpiritTransitionContractTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            check(input != null, "Missing class " + className);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals(method)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            calls.add(new Call(owner, name, descriptor));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(!calls.isEmpty(), "Missing target method " + method);
        return calls;
    }
    private static int index(List<Call> calls, String owner, String method) {
        int found = -1;
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).owner().equals(owner) && calls.get(i).name().equals(method)) {
            check(found < 0, "Target must be unique: " + method); found = i;
        }
        check(found >= 0, "Missing invocation " + owner + "." + method);
        return found;
    }
    public static void main(String[] args) throws Exception {
        String client = "net/minecraft/client/MinecraftClient";
        String handler = "net/minecraft/client/network/ClientPlayNetworkHandler";
        String payload = "io/github/mysticism/net/SpiritTransitionPayload";
        var respawn = calls(handler, "onPlayerRespawn");
        int guard = index(respawn, "net/minecraft/network/NetworkThreadUtils", "forceMainThread");
        int begin = index(respawn, "net/minecraft/network/packet/s2c/play/PlayerRespawnS2CPacket", "commonPlayerSpawnInfo");
        check(guard < begin && begin < index(respawn, client, "joinWorld"), "Handoff must begin after thread guard, before world reset");
        index(respawn, handler, "startWorldLoading");
        var join = calls(client, "joinWorld");
        check(join.get(index(join, client, "reset")).descriptor().equals("(Lnet/minecraft/client/gui/screen/Screen;)V"), "join reset screen descriptor");
        var loading = calls(handler, "startWorldLoading");
        index(loading, "net/minecraft/client/network/WorldLoadingState", "<init>");
        check(loading.get(index(loading, client, "setScreen")).descriptor().equals("(Lnet/minecraft/client/gui/screen/Screen;)V"), "loading screen descriptor");
        var reset = calls(client, "reset");
        check(index(reset, client, "setScreen") < index(reset, client, "render"), "reset forces an intermediate render (bridge regression)");
        var restore = calls("io/github/mysticism/client/spiritworld/SpiritTransitionClient", "endRespawn");
        String player = "net/minecraft/client/network/ClientPlayerEntity";
        check(index(restore, player, "refreshPositionAndAngles") < index(restore, player, "resetPosition"),
                "Seed authoritative pose before resetting interpolation history");
        index(restore, player, "setHeadYaw");
        index(restore, player, "setBodyYaw");
        index(restore, player, "setVelocity");
        var enter = calls("io/github/mysticism/navigation/SpiritNavigationService", "enter");
        check(index(enter, "io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService", "prepareEnter")
                < index(enter, payload, "beforeTeleport"), "Entry hint follows real mesh preparation");
        check(index(enter, payload, "beforeTeleport") < index(enter, "net/minecraft/server/network/ServerPlayerEntity", "teleport"), "Hint precedes entry respawn");
        var exit = calls("io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService", "exit");
        check(index(exit, "io/github/mysticism/dimension/spiritworld/terrain/SpiritTerrainService", "clearBody")
                < index(exit, payload, "beforeTeleport"), "Exit hint follows source obstruction validation");
        check(index(exit, payload, "beforeTeleport") < index(exit, "net/minecraft/server/network/ServerPlayerEntity", "teleport"), "Hint precedes exit respawn");
        try (var input = SpiritTransitionContractTest.class.getClassLoader().getResourceAsStream("mysticism.client.mixins.json")) {
            check(input != null, "Packaged client mixin descriptor");
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            check(json.get("required").getAsBoolean(), "Handoff mixins required");
            check(json.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1, "No silently missing screen hook");
            for (String mixin : List.of("SpiritRespawnHandoffMixin", "SpiritJoinWorldHandoffMixin")) {
                int count = 0;
                for (var name : json.getAsJsonArray("client")) if (name.getAsString().equals(mixin)) count++;
                check(count == 1, "Exactly one registered handoff mixin " + mixin);
                String prefix = json.get("package").getAsString().replace('.', '/') + "/";
                try (var bytecode = SpiritTransitionContractTest.class.getClassLoader().getResourceAsStream(prefix + mixin + ".class")) {
                    check(bytecode != null, "Registered mixin class packaged");
                }
            }
        }
        // Real production payload codec, preserving fractional source pose and angles exactly.
        var wire = new net.minecraft.network.RegistryByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.registry.DynamicRegistryManager.EMPTY);
        try {
            var expected = new SpiritTransitionGate.Handoff(java.util.UUID.randomUUID(), "mysticism:spirit",
                    "minecraft:the_nether", -128.03125, 65.5, 32768.875, -123.75f, 42.5f);
            io.github.mysticism.net.SpiritTransitionPayload.CODEC.encode(wire,
                    new io.github.mysticism.net.SpiritTransitionPayload(expected));
            check(io.github.mysticism.net.SpiritTransitionPayload.CODEC.decode(wire).handoff().equals(expected),
                    "Wire roundtrip retains exact source pose");
            check(wire.readableBytes() == 0, "No unread wire data");
        } finally { wire.release(); }
        System.out.println("SpiritTransitionContractTest: " + checks + " checks passed (offline mapped/wire contracts only)");
    }
}
