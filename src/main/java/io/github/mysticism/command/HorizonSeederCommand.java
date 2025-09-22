package io.github.mysticism.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mysticism.world.region.HorizonSeeder;
import io.github.mysticism.world.state.SpatialEmbeddingIndexState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class HorizonSeederCommand {
    private HorizonSeederCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("horizonseeder")
                        .requires(src -> src.hasPermissionLevel(2)) // op-only; tweak if you want
                        .then(CommandManager.literal("summary")
                                .executes(ctx -> {
                                    var server   = ctx.getSource().getServer();
                                    var index    = SpatialEmbeddingIndexState.get(server).getIndex();

                                    int processed   = index.size();                         // total regions indexed
                                    String state    = HorizonSeeder.isBusy() ? "BUSY" : "IDLE";
                                    int queued      = HorizonSeeder.getQueueSize();
                                    int rpt         = HorizonSeeder.getRegionsPerTick();
                                    boolean sat     = HorizonSeeder.isQueueSaturated();
                                    int maxQ        = HorizonSeeder.getMaxQueue();

                                    double mspt     = HorizonSeeder.getMspt(server);
                                    double tMs      = HorizonSeeder.getTargetMspt(server);
                                    double lagPct   = (HorizonSeeder.getLagRatio(server) - 1.0) * 100.0;

                                    // --- Chunk-fill telemetry (server-side chunk readiness near players)
                                    double fillHead = HorizonSeeder.getChunkFillHeadroom(server); // 0..1
                                    double worstFill = HorizonSeeder.getWorstChunkFill(server);    // 0..1
                                    double worstFillPct = worstFill * 100.0;
                                    double headPct = fillHead * 100.0;

                                    // Header
                                    send(ctx, Text.literal("HorizonSeeder summary:").formatted(Formatting.AQUA));

                                    // State
                                    send(ctx, Text.literal("State: ").formatted(Formatting.GRAY)
                                            .append(Text.literal(state)
                                                    .formatted("BUSY".equals(state) ? Formatting.GOLD : Formatting.GREEN)));

                                    // Totals
                                    send(ctx, Text.literal("Indexed ")
                                            .append(Text.literal(Integer.toString(processed)).formatted(Formatting.WHITE))
                                            .append(Text.literal(" regions so far, with "))
                                            .append(Text.literal(Integer.toString(queued)).formatted(Formatting.WHITE))
                                            .append(Text.literal(" vanilla regions queued."))
                                            .formatted(Formatting.GRAY));

                                    // Current throughput
                                    send(ctx, Text.literal("Processing ")
                                            .append(Text.literal(Integer.toString(rpt)).formatted(Formatting.WHITE))
                                            .append(Text.literal(" regions per tick."))
                                            .formatted(Formatting.GRAY));

                                    // MSPT
                                    send(ctx, Text.literal("Lag rate: ")
                                            .append(Text.literal(String.format("%.1f", lagPct))
                                                    .formatted(lagPct > 0 ? Formatting.RED : Formatting.GREEN))
                                            .append(Text.literal("% behind target "))
                                            .append(Text.literal("(").formatted(Formatting.GRAY))
                                            .append(Text.literal(String.format("%.1f", mspt)).formatted(Formatting.WHITE))
                                            .append(Text.literal(" ms / "))
                                            .append(Text.literal(String.format("%.1f", tMs)).formatted(Formatting.WHITE))
                                            .append(Text.literal(" ms)"))
                                            .formatted(Formatting.GRAY));

                                    // Chunk-fill line
                                    send(ctx, Text.literal("Chunk fill near players: ")
                                            .append(Text.literal(String.format("%.1f", worstFillPct)).formatted(Formatting.WHITE))
                                            .append(Text.literal("% loaded (headroom "))
                                            .append(Text.literal(String.format("%.0f", headPct)).formatted(Formatting.WHITE))
                                            .append(Text.literal("%)"))
                                            .formatted(Formatting.GRAY));

                                    // Queue saturation
                                    send(ctx, Text.literal(sat
                                                    ? "Queue is saturated (" + queued + " / " + maxQ + ")."
                                                    : "Queue is not saturated (" + queued + " / " + maxQ + ").")
                                            .formatted(sat ? Formatting.RED : Formatting.GREEN));

                                    return 1;
                                })
                        )
        );
    }

    private static void send(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, Text t) {
        ctx.getSource().sendFeedback(() -> t, false);
    }
}
