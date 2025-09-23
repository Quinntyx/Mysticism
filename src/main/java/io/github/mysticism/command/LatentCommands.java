package io.github.mysticism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import io.github.mysticism.component.MysticismEntityComponents;
import io.github.mysticism.dimension.spiritworld.SpiritVisibilityService;
import io.github.mysticism.vector.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /latent commands.
 * NOTE: Latent vectors (pos, attunement) are treated as RAW here (no normalization).
 *       Only basis construction normalizes/orthonormalizes its axes.
 */
public final class LatentCommands {
    // simple per-player “actionbar logging” toggle
    private static final Set<UUID> LOG = new HashSet<>();

    private LatentCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> d, CommandRegistryAccess reg, CommandManager.RegistrationEnvironment env) {
        d.register(literal("latent")
                // /latent show
                .then(literal("show").executes(ctx -> showAll(ctx.getSource())))
                // /latent show <basis|pos|attune|items>
                .then(literal("show")
                        .then(literal("basis").executes(ctx -> showOne(ctx.getSource(), "basis")))
                        .then(literal("pos").executes(ctx -> showOne(ctx.getSource(), "pos")))
                        .then(literal("attune").executes(ctx -> showOne(ctx.getSource(), "attune")))
                        .then(literal("items").executes(ctx -> showOne(ctx.getSource(), "items")))
                )
                // /latent set <basis|pos|attune> item <item>
                .then(literal("set")
                        .then(literal("basis")
                                .then(literal("item")
                                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(reg))
                                                .executes(ctx -> setFromItem(ctx.getSource(), "basis", ItemStackArgumentType.getItemStackArgument(ctx, "item").createStack(1, false)))
                                        )
                                )
                        )
                        .then(literal("pos")
                                .then(literal("item")
                                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(reg))
                                                .executes(ctx -> setFromItem(ctx.getSource(), "pos", ItemStackArgumentType.getItemStackArgument(ctx, "item").createStack(1, false)))
                                        )
                                )
                        )
                        .then(literal("attune")
                                .then(literal("item")
                                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(reg))
                                                .executes(ctx -> setFromItem(ctx.getSource(), "attune", ItemStackArgumentType.getItemStackArgument(ctx, "item").createStack(1, false)))
                                        )
                                )
                        )
                        // /latent set <basis|pos|attune> region  (stub)
                        .then(literal("basis").then(literal("region").executes(ctx -> stubRegion(ctx.getSource(), "basis"))))
                        .then(literal("pos").then(literal("region").executes(ctx -> stubRegion(ctx.getSource(), "pos"))))
                        .then(literal("attune").then(literal("region").executes(ctx -> stubRegion(ctx.getSource(), "attune"))))
                )
                // /latent set basis <item> (shorthand)
                .then(literal("set")
                        .then(literal("basis")
                                .then(CommandManager.argument("itemId", StringArgumentType.string())
                                        .executes(ctx -> setBasisByItemId(ctx.getSource(), StringArgumentType.getString(ctx, "itemId")))
                                )
                        )
                )
                // /latent log <show|hide>
                .then(literal("log")
                        .then(literal("show").executes(ctx -> toggleLog(ctx.getSource(), true)))
                        .then(literal("hide").executes(ctx -> toggleLog(ctx.getSource(), false)))
                )
        );
    }

    // ---- handlers ----

    private static int showAll(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return 0;

        var pos = p.getComponent(MysticismEntityComponents.LATENT_POS).get();                 // RAW
        var basis = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();             // Orthonormal
        var att = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get();          // RAW

        List<String> items = visibleItems(src.getServer(), p, 15);

        src.sendFeedback(() -> Text.literal(
                "Latent State\n" +
                        "  pos   = " + fmt(pos) + "\n" +
                        "  basis = { i=" + fmt(basis.i) + ", j=" + fmt(basis.j) + ", k=" + fmt(basis.k) + " }\n" +
                        "  att   = " + fmt(att) + "\n" +
                        "  items = " + (items.isEmpty() ? "[]" : items.toString())
        ), false);
        return 1;
    }

    private static int showOne(ServerCommandSource src, String which) {
        ServerPlayerEntity p = src.getPlayer(); if (p == null) return 0;
        switch (which) {
            case "pos" -> {
                var v = p.getComponent(MysticismEntityComponents.LATENT_POS).get(); // RAW
                src.sendFeedback(() -> Text.literal("pos = " + fmt(v)), false);
            }
            case "attune" -> {
                var v = p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get(); // RAW
                src.sendFeedback(() -> Text.literal("attunement = " + fmt(v)), false);
            }
            case "basis" -> {
                var b = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
                src.sendFeedback(() -> Text.literal("basis = { i=" + fmt(b.i) + ", j=" + fmt(b.j) + ", k=" + fmt(b.k) + " }"), false);
            }
            case "items" -> {
                List<String> items = visibleItems(src.getServer(), p, 30);
                src.sendFeedback(() -> Text.literal("visible items = " + (items.isEmpty() ? "[]" : items.toString())), false);
            }
        }
        return 1;
    }

    private static int setFromItem(ServerCommandSource src, String target, ItemStack stack) {
        ServerPlayerEntity p = src.getPlayer(); if (p == null) return 0;

        var id = Objects.requireNonNull(stack.getItem().getRegistryEntry().registryKey().getValue()).toString();
        var v = embeddingForItem(src.getServer(), id); // RAW embedding from your index
        if (v == null) {
            src.sendError(Text.literal("No embedding for item: " + id));
            return 0;
        }

        switch (target) {
            case "pos" -> {
                // RAW set (no normalization)
                p.getComponent(MysticismEntityComponents.LATENT_POS).set(v.clone());
                MysticismEntityComponents.LATENT_POS.sync(p);
                src.sendFeedback(() -> Text.literal("Set pos to RAW item embedding of " + id), false);
            }
            case "attune" -> {
                // RAW set (no normalization)
                p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).set(v.clone());
                MysticismEntityComponents.LATENT_ATTUNEMENT.sync(p);
                src.sendFeedback(() -> Text.literal("Set attunement to RAW item embedding of " + id), false);
            }
            case "basis" -> {
                // Basis MUST be orthonormal for projection; use direction of v + Gram–Schmidt.
                Basis384f B = orthonormalBasisFrom(v, p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get());
                p.getComponent(MysticismEntityComponents.LATENT_BASIS).set(B);
                MysticismEntityComponents.LATENT_BASIS.sync(p);
                src.sendFeedback(() -> Text.literal("Aligned basis to item " + id + " (i forward; orthonormal)"), false);
            }
        }
        return 1;
    }

    // /latent set basis <itemId> (string shortcut)
    private static int setBasisByItemId(ServerCommandSource src, String itemId) {
        ServerPlayerEntity p = src.getPlayer(); if (p == null) return 0;
        var v = embeddingForItem(src.getServer(), itemId); // RAW
        if (v == null) { src.sendError(Text.literal("No embedding for item: " + itemId)); return 0; }
        Basis384f B = orthonormalBasisFrom(v, p.getComponent(MysticismEntityComponents.LATENT_ATTUNEMENT).get());
        p.getComponent(MysticismEntityComponents.LATENT_BASIS).set(B);
        MysticismEntityComponents.LATENT_BASIS.sync(p);
        src.sendFeedback(() -> Text.literal("Aligned basis to item " + itemId + " (i forward; orthonormal)"), false);
        return 1;
    }

    private static int stubRegion(ServerCommandSource src, String which) {
        src.sendFeedback(() -> Text.literal(
                        "[todo] `/latent set " + which + " region` not implemented yet (current region-embedding pipeline is disabled)"),
                false);
        return 1;
    }

    private static int toggleLog(ServerCommandSource src, boolean show) {
        var p = src.getPlayer(); if (p == null) return 0;
        if (show) { LOG.add(p.getUuid()); src.sendFeedback(() -> Text.literal("latent debug actionbar: ON"), false); }
        else      { LOG.remove(p.getUuid()); src.sendFeedback(() -> Text.literal("latent debug actionbar: OFF"), false); }
        return 1;
    }

    // ---- helpers (embedding lookup, basis construction, formatting) ----

    private static Vec384f embeddingForItem(MinecraftServer server, String itemId) {
        var state = io.github.mysticism.world.state.ItemEmbeddingIndexState.get(server);
        // your index should be able to return the vector by id (RAW)
        return state.getIndex().get(itemId);
    }

    /**
     * Build an orthonormal basis with i = direction of vItem (normalized).
     * j is obtained by Gram–Schmidt using a seed (prefer attunement; else fallback).
     * k orthogonalized similarly. Only the basis is normalized; pos/attune stay RAW.
     */
    private static Basis384f orthonormalBasisFrom(Vec384f vItem, Vec384f attunementRaw) {
        Vec384f i = vItem.clone(); normalizeInPlace(i);

        // seed not collinear with i
        Vec384f seed1 = (attunementRaw != null && attunementRaw.length() > 1e-6f) ? attunementRaw.clone() : canonicalSeed();

        // j = normalize(seed1 - <seed1,i> i)
        Vec384f j = seed1.sub(i.clone().mul(seed1.dot(i)));
        if (j.length() < 1e-6f) j = canonicalSeed().sub(i.clone().mul(canonicalSeed().dot(i)));
        normalizeInPlace(j);

        // k from another seed, orthogonalize against i & j
        Vec384f seed2 = altSeed();
        Vec384f k = seed2.sub(i.clone().mul(seed2.dot(i))).sub(j.clone().mul(seed2.dot(j)));
        if (k.length() < 1e-6f) {
            Vec384f h = hashedSeed(vItem);
            k = h.sub(i.clone().mul(h.dot(i))).sub(j.clone().mul(h.dot(j)));
        }
        normalizeInPlace(k);

        return new Basis384f(i, j, k);
    }

    private static void normalizeInPlace(Vec384f v) {
        float len = v.length();
        if (len > 1e-6f) v.mul(1f / len).updateNorm();
    }

    private static Vec384f canonicalSeed() {
        float[] a = new float[384];
        a[0] = 1f;
        return new Vec384f(a);
    }

    private static Vec384f altSeed() {
        float[] a = new float[384];
        a[1] = 1f;
        return new Vec384f(a);
    }

    private static Vec384f hashedSeed(Vec384f v) {
        int h = Arrays.hashCode(v.data()); // uses a clone; fine for a seed
        Random r = new Random(h);
        float[] a = new float[384];
        for (int i = 0; i < 384; i++) a[i] = (r.nextFloat() - 0.5f);
        return new Vec384f(a);
    }

    // compact formatting for logs/chat; does not normalize
    private static String fmt(Vec384f v) {
        float n = v.length();
        float[] d = v.data(); // RAW snapshot
        int show = Math.min(6, d.length);
        String head = IntStream.range(0, show)
                .mapToObj(i -> String.format("%.3f", d[i]))
                .collect(Collectors.joining(", "));
        return "[" + head + (d.length > show ? ", …" : "") + "] |‖v‖=" + String.format("%.3f", n);
    }

    private static List<String> visibleItems(MinecraftServer server, ServerPlayerEntity p, int k) {
        if (!SpiritVisibilityService.isSpiritWorld(p)) return List.of();
        var state = io.github.mysticism.world.state.ItemEmbeddingIndexState.get(server);
        var q = p.getComponent(MysticismEntityComponents.LATENT_POS).get(); // RAW
        // Adapt to your API; this assumes state exposes a nearestIds(k, q) over RAW vectors.
        return state.nearestIds(k, q);
    }

    // ---- actionbar logger loop ----

    public static void tickActionbar(MinecraftServer server) {
        for (var p : server.getPlayerManager().getPlayerList()) {
            if (!LOG.contains(p.getUuid())) continue;
            var pos = p.getComponent(MysticismEntityComponents.LATENT_POS).get();
            var b = p.getComponent(MysticismEntityComponents.LATENT_BASIS).get();
            p.sendMessage(Text.literal("latent pos‖" + String.format("%.2f", pos.length())
                    + "  i‖" + String.format("%.2f", b.i.length())
                    + " j‖" + String.format("%.2f", b.j.length())
                    + " k‖" + String.format("%.2f", b.k.length())), true);
        }
    }
}
