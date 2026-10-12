package io.github.mysticism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.navigation.SpiritNavigationService;
import io.github.mysticism.vector.*;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import static net.minecraft.server.command.CommandManager.literal;

/** Debug target snapshots and state inspection. Never synchronously calls or joins the model. */
public final class LatentCommands {
    private static final Set<UUID> LOG = new HashSet<>();
    private LatentCommands() {}
    public static void register(CommandDispatcher<ServerCommandSource> d, CommandRegistryAccess reg, CommandManager.RegistrationEnvironment env) {
        var root = literal("latent").executes(ctx -> show(ctx.getSource()));
        var show = literal("show").executes(ctx -> show(ctx.getSource()));
        for (String field : List.of("basis", "pos", "attune", "items"))
            show.then(literal(field).executes(ctx -> show(ctx.getSource())));
        root.then(show);
        root.then(literal("target")
                .then(literal("here").executes(ctx -> capture(ctx.getSource())))
                .then(literal("personal").executes(ctx -> personal(ctx.getSource())))
                .then(literal("item").then(CommandManager.argument("item", ItemStackArgumentType.itemStack(reg))
                        .executes(ctx -> setItem(ctx.getSource(), "attune", ItemStackArgumentType.getItemStackArgument(ctx, "item").createStack(1, false)))))
                .then(literal("at").then(CommandManager.argument("dimension", StringArgumentType.word())
                        .then(CommandManager.argument("landmark", StringArgumentType.word())
                                .then(CommandManager.argument("position", BlockPosArgumentType.blockPos())
                                        .executes(ctx -> captureAt(ctx.getSource(), StringArgumentType.getString(ctx, "dimension"),
                                                StringArgumentType.getString(ctx, "landmark"), BlockPosArgumentType.getBlockPos(ctx, "position"))))))));
        var set = literal("set");
        for (String field : List.of("basis", "pos", "attune"))
            set.then(literal(field).then(literal("item")
                    .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(reg))
                            .executes(ctx -> setItem(ctx.getSource(), field, ItemStackArgumentType.getItemStackArgument(ctx, "item").createStack(1, false))))));
        set.then(literal("attune").then(literal("region").executes(ctx -> capture(ctx.getSource()))));
        set.then(literal("basis").then(CommandManager.argument("itemId", StringArgumentType.word())
                .executes(ctx -> setById(ctx.getSource(), "basis", StringArgumentType.getString(ctx, "itemId")))));
        root.then(set);
        root.then(literal("log").then(literal("show").executes(ctx -> log(ctx.getSource(), true)))
                .then(literal("hide").executes(ctx -> log(ctx.getSource(), false))));
        d.register(root);
    }
    private static int capture(ServerCommandSource src) {
        var p = src.getPlayer(); return p != null && SpiritNavigationService.captureHere(p) ? 1 : 0;
    }
    private static int captureAt(ServerCommandSource src, String dimension, String id, BlockPos block) {
        var p = src.getPlayer(); if (p == null) return 0;
        if (net.minecraft.util.Identifier.tryParse(dimension) == null) { src.sendError(Text.literal("Invalid dimension ID.")); return 0; }
        return SpiritNavigationService.captureSource(p, dimension, block, id) ? 1 : 0;
    }
    private static int personal(ServerCommandSource src) {
        var p = src.getPlayer(); if (p == null) return 0;
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
        // Explicit snapshot, NOT followPersonal(), which would live-follow subsequent observations.
        SpiritNavigationService.cancelCapture(p);
        att.set(att.personal()); p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).clearTarget();
        // An explicit concept re-key is a destination request: it supersedes pending walk/landing intents.
        SpiritNavigationService.explicitDestinationChange(p);
        MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p); MysticismEntityComponents.SPIRIT_NAVIGATION.sync(p);
        src.sendFeedback(() -> Text.literal("Captured personal concept location; no shallow source destination."), false); return 1;
    }
    private static int setItem(ServerCommandSource src, String field, ItemStack stack) {
        return setById(src, field, Registries.ITEM.getId(stack.getItem()).toString());
    }
    private static int setById(ServerCommandSource src, String field, String id) {
        var p = src.getPlayer(); if (p == null) return 0;
        var vector = ItemEmbeddingIndexState.get(src.getServer()).getIndex().get(id);
        if (vector == null) { src.sendError(Text.literal("No ready current-model embedding for " + id + "; use /myst embed separately.")); return 0; }
        switch (field) {
            case "pos" -> {
                if (p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).active()
                        && !p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).deep()) {
                    src.sendError(Text.literal("Cannot re-key source-grid position while shallow; /spirit deep first.")); return 0;
                }
                p.getComponent(MysticismEntityComponents.LATENT_POS).set(vector); SpiritNavigationService.anchorFromConcept(p);
                MysticismEntityComponents.LATENT_POS.sync(p);
            }
            case "attune" -> {
                SpiritNavigationService.cancelCapture(p);
                p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).set(vector);
                p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).clearTarget();
                // An explicit concept re-key is a destination request: it supersedes pending walk/landing intents.
                SpiritNavigationService.explicitDestinationChange(p);
                MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p); MysticismEntityComponents.SPIRIT_NAVIGATION.sync(p);
            }
            case "basis" -> {
                if (p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).active()
                        && !p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION).deep()) {
                    src.sendError(Text.literal("Shallow preserves source-grid alignment; /spirit deep first.")); return 0;
                }
                p.getComponent(MysticismEntityComponents.LATENT_BASIS).set(basis(vector)); MysticismEntityComponents.LATENT_BASIS.sync(p);
            }
            default -> { return 0; }
        }
        src.sendFeedback(() -> Text.literal("Set " + field + " from captured item location " + id), false); return 1;
    }
    private static Basis384f basis(Vec384f vector) {
        Vec384f i = vector.clone(); if (i.length() < 1e-6) return new Basis384f(); i.mul(1 / i.length());
        Vec384f j = orthogonal(i, null); Vec384f k = orthogonal(i, j); return new Basis384f(i, j, k);
    }
    private static Vec384f orthogonal(Vec384f i, Vec384f j) {
        float[] a = i.data(), b = j == null ? new float[a.length] : j.data(); int seed = 0;
        for (int n = 1; n < a.length; n++) if (a[n]*a[n] + b[n]*b[n] < a[seed]*a[seed] + b[seed]*b[seed]) seed = n;
        float[] values = new float[a.length]; values[seed] = 1;
        Vec384f result = new Vec384f(values).sub(i.clone().mul(a[seed]));
        if (j != null) result.sub(j.clone().mul(b[seed]));
        return result.mul(1 / result.length());
    }
    private static int show(ServerCommandSource src) {
        var p = src.getPlayer(); if (p == null) return 0;
        var b = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT);
        var nav = p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
        src.sendFeedback(() -> Text.literal("q=" + fmt(p.getComponent(MysticismEntityComponents.LATENT_POS).get())
                + " target(snapshot)=" + fmt(att.target()) + " personal=" + fmt(att.personal())
                + " basis{i=" + fmt(b.i) + ", j=" + fmt(b.j) + ", k=" + fmt(b.k) + "}"
                + " mode=" + (!nav.active() ? "source" : nav.deep() ? "deep" : "shallow")
                + (nav.hasShallowTarget() ? " targetSource=" + nav.targetDimension() + " " + nav.targetLandmarkId()
                + " " + nav.targetBlock().toShortString() : " targetSource=none")), false); return 1;
    }
    private static String fmt(Vec384f v) {
        float[] values = v.data(); return String.format(Locale.ROOT, "[%.3f, %.3f, %.3f, …] |%.3f|", values[0], values[1], values[2], v.length());
    }
    private static int log(ServerCommandSource src, boolean enable) {
        var p = src.getPlayer(); if (p == null) return 0;
        if (enable) LOG.add(p.getUuid()); else LOG.remove(p.getUuid()); return 1;
    }
    public static void tickActionbar(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (var p : server.getPlayerManager().getPlayerList()) {
            online.add(p.getUuid()); if (!LOG.contains(p.getUuid())) continue;
            var nav = p.getComponent(MysticismEntityComponents.SPIRIT_NAVIGATION);
            p.sendMessage(Text.literal("Spirit " + (!nav.active() ? "source" : nav.deep() ? "deep" : "shallow")
                    + " q=" + fmt(p.getComponent(MysticismEntityComponents.LATENT_POS).get())), true);
        }
        LOG.retainAll(online);
    }
}
