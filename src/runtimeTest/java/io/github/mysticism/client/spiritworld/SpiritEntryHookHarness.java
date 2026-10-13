package io.github.mysticism.client.spiritworld;

import java.lang.reflect.Method;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Executes the compiled redirect bodies with tiny remapped game stand-ins. Only the shadow reset
 * is replaced with a recorder. This checks real hook control flow, not Mixin application or a GPU.
 */
public final class SpiritEntryHookHarness {
    public static class Screen {}
    public static final class LoadingScreen extends Screen {}
    public static final class Reason {}
    public record Identifier(String value) { @Override public String toString() { return value; } }
    public record Key(Identifier value) { public Identifier getValue() { return value; } }
    public record World(Key key) {
        public World(String dimension) { this(new Key(new Identifier(dimension))); }
        public Key getRegistryKey() { return key; }
    }
    public record Player(World world, boolean alive) {
        public World getWorld() { return world; }
        public boolean isAlive() { return alive; }
    }
    public static final class Client {
        public World world;
        public Player player;
        public Screen screen;
        public int screenCalls;
        public void setScreen(Screen screen) { this.screen = screen; screenCalls++; }
    }
    private static int resets;
    private static Screen resetScreen;
    private static int checks;

    public static void recordReset(Screen screen) { resets++; resetScreen = screen; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static String name(Class<?> type) { return type.getName().replace('.', '/'); }

    private static Object hook(String simpleName) throws Exception {
        String oldName = "io/github/mysticism/client/mixin/" + simpleName;
        String newName = "io/github/mysticism/client/spiritworld/" + simpleName + "UnderTest";
        ClassNode source = new ClassNode();
        try (var stream = SpiritEntryHookHarness.class.getClassLoader().getResourceAsStream(oldName + ".class")) {
            check(stream != null, "Compiled hook must exist");
            new org.objectweb.asm.ClassReader(stream).accept(source, 0);
        }
        source.access &= ~Opcodes.ACC_ABSTRACT;
        source.visibleAnnotations = null;
        source.invisibleAnnotations = null;
        for (var method : source.methods) {
            method.visibleAnnotations = null;
            method.invisibleAnnotations = null;
            if (method.name.equals("reset")) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, name(SpiritEntryHookHarness.class),
                        "recordReset", "(L" + name(Screen.class) + ";)V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
        }
        Map<String, String> types = Map.ofEntries(
                Map.entry(oldName, newName),
                Map.entry("net/minecraft/client/MinecraftClient", name(Client.class)),
                Map.entry("net/minecraft/client/gui/screen/Screen", name(Screen.class)),
                Map.entry("net/minecraft/client/gui/screen/DownloadingTerrainScreen", name(LoadingScreen.class)),
                Map.entry("net/minecraft/client/gui/screen/DownloadingTerrainScreen$WorldEntryReason", name(Reason.class)),
                Map.entry("net/minecraft/client/network/ClientPlayerEntity", name(Player.class)),
                Map.entry("net/minecraft/client/world/ClientWorld", name(World.class)),
                Map.entry("net/minecraft/world/World", name(World.class)),
                Map.entry("net/minecraft/registry/RegistryKey", name(Key.class)),
                Map.entry("net/minecraft/util/Identifier", name(Identifier.class)));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        source.accept(new ClassRemapper(writer, new SimpleRemapper(types)));
        class HookLoader extends ClassLoader {
            HookLoader() { super(SpiritEntryHookHarness.class.getClassLoader()); }
            Class<?> loadHook() {
                byte[] bytes = writer.toByteArray();
                return defineClass(newName.replace('/', '.'), bytes, 0, bytes.length);
            }
        }
        return new HookLoader().loadHook().getConstructor().newInstance();
    }

    private static Method callback(Object hook, String name, Class<?>... arguments) throws Exception {
        var callback = hook.getClass().getDeclaredMethod(name, arguments);
        callback.setAccessible(true);
        return callback;
    }

    public static int run() throws Exception {
        Object worldHook = hook("SpiritEntryWorldMixin");
        Object loadingHook = hook("SpiritEntryLoadingMixin");
        Method worldCallback = callback(worldHook, "mysticism$keepSourceView",
                Client.class, Screen.class, World.class, Reason.class);
        Method loadingCallback = callback(loadingHook, "mysticism$keepSpiritView",
                Client.class, Screen.class, Player.class, World.class, Reason.class);
        var source = new World("minecraft:overworld");
        var destination = new World("mysticism:spirit");
        var livePlayer = new Player(source, true);
        var nextPlayer = new Player(destination, true);
        var loading = new LoadingScreen();
        var unrelated = new Screen();
        // world reset always runs exactly once; only a live source entry substitutes null for its GUI.
        for (int scenario = 0; scenario < 10; scenario++) {
            var client = new Client();
            client.world = source;
            client.player = livePlayer;
            Screen screen = loading;
            World nextWorld = destination;
            switch (scenario) {
                case 1 -> client.world = null; // initial connection
                case 2 -> client.player = null;
                case 3 -> client.player = new Player(source, false); // death
                case 4 -> client.player = nextPlayer; // source player/world disagreement
                case 5 -> nextWorld = source; // vanilla same-dimension respawn
                case 6 -> { client.world = destination; client.player = nextPlayer; nextWorld = source; } // exit
                case 7 -> { client.world = destination; client.player = nextPlayer; } // spirit respawn
                case 8 -> screen = unrelated;
                case 9 -> screen = null;
                default -> { }
            }
            World beforeWorld = client.world;
            Player beforePlayer = client.player;
            resets = 0;
            resetScreen = unrelated;
            worldCallback.invoke(worldHook, client, screen, nextWorld, null);
            check(resets == 1, "Never skip reset: " + scenario);
            check(resetScreen == (scenario == 0 ? null : screen), "Reset screen selection: " + scenario);
            check(client.world == beforeWorld && client.player == beforePlayer && client.screenCalls == 0,
                    "World hook leaves vanilla world/player replacement untouched: " + scenario);
        }
        // Loading state has already been constructed by vanilla. Suppress only setScreen, never
        // set a loading-ready flag. The source player is still installed until AFTER this callback.
        for (int scenario = 0; scenario < 11; scenario++) {
            var client = new Client();
            client.world = destination;
            client.player = livePlayer;
            Screen screen = loading;
            World nextWorld = destination;
            switch (scenario) {
                case 1 -> client.world = source; // wrong callback phase
                case 2 -> client.player = null;
                case 3 -> client.player = nextPlayer; // login already installed the new player
                case 4 -> client.player = new Player(source, false); // death
                case 5 -> { client.world = source; nextWorld = source; } // respawn in source world
                case 6 -> { client.world = source; client.player = nextPlayer; nextWorld = source; } // exit
                case 7 -> client.player = new Player(destination, true); // spirit respawn
                case 8 -> screen = unrelated;
                case 9 -> screen = null;
                case 10 -> client.world = null;
                default -> { }
            }
            World beforeWorld = client.world;
            Player beforePlayer = client.player;
            loadingCallback.invoke(loadingHook, client, screen, nextPlayer, nextWorld, null);
            check(client.screenCalls == (scenario == 0 ? 0 : 1), "Loading screen calls: " + scenario);
            check(client.screen == (scenario == 0 ? null : screen), "Preserve fallback screen: " + scenario);
            check(client.world == beforeWorld && client.player == beforePlayer,
                    "Loading hook leaves world/player identity untouched: " + scenario);
        }
        return checks;
    }
}
