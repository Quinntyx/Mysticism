package io.github.mysticism.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import static net.minecraft.server.command.CommandManager.literal;

/** Asynchronous terrain entry: suspend gravity only with a prepared return pose. */
public final class SpiritCommand {
    private SpiritCommand() {}
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("spirit")
                .then(literal("enter").executes(context -> enter(context.getSource().getPlayerOrThrow())))
                .then(literal("leave").executes(context -> leave(context.getSource().getPlayerOrThrow()))));
    }
    private static int enter(ServerPlayerEntity player) {
        ServerWorld world = player.getServer().getWorld(SpiritTerrainService.WORLD);
        if (world == null) { player.sendMessage(Text.literal("The spirit dimension is unavailable."), false); return 0; }
        if (!SpiritTerrainService.prepareEnter(player)) return 0;
        try {
            // Terrain's lifecycle callback owns suspended gravity, landing search and timeout.
            player.teleport(world, 0.5, 128, 0.5, player.getYaw(), player.getPitch());
            player.setVelocity(net.minecraft.util.math.Vec3d.ZERO); player.fallDistance = 0;
            player.sendMessage(Text.literal("Finding a stable spirit landmark; /spirit leave returns you."), false);
            return 1;
        } catch (RuntimeException failed) {
            SpiritTerrainService.cancelEnter(player);
            player.sendMessage(Text.literal("Spirit entry failed: " + failed.getMessage()), false);
            return 0;
        }
    }
    private static int leave(ServerPlayerEntity player) {
        if (!SpiritTerrainService.exit(player)) { player.sendMessage(Text.literal("You are not in the spirit world."), false); return 0; }
        return 1;
    }
}
