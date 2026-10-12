package io.github.mysticism.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mysticism.navigation.SpiritNavigationService;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import static net.minecraft.server.command.CommandManager.literal;

/** V1 debug controls; no survival UI or return-to-entry substitute. */
public final class SpiritCommand {
    private SpiritCommand() {}
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("spirit")
                .executes(context -> status(context.getSource().getPlayerOrThrow()))
                .then(literal("enter").executes(context -> SpiritNavigationService.enter(context.getSource().getPlayerOrThrow()) ? 1 : 0))
                .then(literal("leave").executes(context -> SpiritNavigationService.exit(context.getSource().getPlayerOrThrow()) ? 1 : 0))
                .then(literal("deep").executes(context -> {
                    var p = context.getSource().getPlayerOrThrow();
                    SpiritNavigationService.enterDeep(p); return status(p);
                }))
                .then(literal("capture").executes(context -> SpiritNavigationService.captureHere(context.getSource().getPlayerOrThrow()) ? 1 : 0)));
    }
    private static int status(ServerPlayerEntity p) {
        // Real current navigation progress: mode, semantic readiness, live walk-request state,
        // retry cooldown, landing approach, captured target and pending discovery.
        p.sendMessage(Text.literal(SpiritNavigationService.status(p)), false);
        return 1;
    }
}
