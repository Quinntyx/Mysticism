package io.github.mysticism.client.spiritworld;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Local session controls; never sends a server command or changes navigation/collision. */
final class SpiritVisualCommands {
    private static boolean initialized;
    private SpiritVisualCommands() {}

    static void init() {
        if (initialized) return;
        initialized = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(commandTree()));
    }

    record Actions(Consumer<Boolean> enabled, BiConsumer<SpiritVisualControls.Effect, Boolean> effect,
                   Consumer<SpiritRenderSettings.Quality> quality, Runnable retry, Supplier<String> status) {}

    static LiteralArgumentBuilder<FabricClientCommandSource> commandTree() {
        return commandTree(new Actions(ShaderManager::setEnabled, ShaderManager::setEffect,
                ShaderManager::setQuality, ShaderManager::retry, ShaderManager::status));
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(Actions actions) {
        var root = ClientCommandManager.literal("spiritvisual")
                .executes(context -> report(context.getSource(), actions))
                .then(ClientCommandManager.literal("status").executes(context -> report(context.getSource(), actions)))
                .then(ClientCommandManager.literal("on").executes(context -> {
                    actions.enabled.accept(true);
                    return report(context.getSource(), actions);
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    actions.enabled.accept(false);
                    return report(context.getSource(), actions);
                }))
                .then(ClientCommandManager.literal("retry").executes(context -> {
                    actions.retry.run();
                    return report(context.getSource(), actions);
                }));
        for (var effect : SpiritVisualControls.Effect.values()) {
            root.then(toggle(effect.name().toLowerCase(Locale.ROOT), value -> actions.effect.accept(effect, value), actions));
        }
        var quality = ClientCommandManager.literal("quality");
        for (var value : SpiritRenderSettings.Quality.values()) {
            quality.then(ClientCommandManager.literal(value.name().toLowerCase(Locale.ROOT)).executes(context -> {
                actions.quality.accept(value);
                return report(context.getSource(), actions);
            }));
        }
        root.then(quality);
        root.then(ClientCommandManager.literal("help").executes(context -> {
            context.getSource().sendFeedback(Text.literal("Client session only: /spiritvisual status|on|off|retry; "
                    + "/spiritvisual fog|kuwahara|saturation on|off; /spiritvisual quality low|medium|high. "
                    + "Off bypasses postprocessing, not spirit terrain or collision. Fog off leaves vanilla fog unchanged. "
                + "Saturation controls the 2.5s entry fade; it does not restart when toggled."));
            return 1;
        }));
        return root;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Consumer<Boolean> setter, Actions actions) {
        var node = ClientCommandManager.literal(name);
        for (boolean value : new boolean[]{true, false}) {
            node.then(ClientCommandManager.literal(value ? "on" : "off").executes(context -> {
                setter.accept(value);
                return report(context.getSource(), actions);
            }));
        }
        return node;
    }
    private static int report(FabricClientCommandSource source, Actions actions) {
        source.sendFeedback(Text.literal("Spirit visuals (client session): " + actions.status.get()));
        return 1;
    }
}
