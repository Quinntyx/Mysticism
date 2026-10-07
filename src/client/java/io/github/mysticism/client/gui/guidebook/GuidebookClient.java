package io.github.mysticism.client.gui.guidebook;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceType;
import org.lwjgl.glfw.GLFW;

/** Client-only glue. No server initializer/item registry dependency is necessary. */
public final class GuidebookClient {
    private static KeyBinding key;
    private static boolean pendingOpen;
    static boolean reducedMotion;
    private GuidebookClient() {}
    public static void init() {
        if (key != null) return;
        GuidebookShimmerShader.init();
        GuidebookLoader.loadBundled();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new GuidebookLoader());
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mysticism.guidebook", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J, "key.categories.mysticism"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("spiritguide").executes(context -> { pendingOpen = true; return 1; })));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean requested = pendingOpen; pendingOpen = false;
            while (key.wasPressed()) requested = true;
            if (requested && client.player != null && client.currentScreen == null) open(client);
        });
    }
    /** Call on the client thread from optional client item-use/network glue, only after init(). */
    public static void open(MinecraftClient client) {
        if (client.player != null && client.currentScreen == null) client.setScreen(new SpiritGuidebookScreen(null));
    }
}
