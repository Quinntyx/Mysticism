package io.github.mysticism.command;

import ai.djl.util.Pair;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.mysticism.Mysticism;
import io.github.mysticism.embedding.EmbeddingHelper;
import io.github.mysticism.vector.KnnIndex;
import io.github.mysticism.vector.Metric;
import io.github.mysticism.vector.Vec384f;
import io.github.mysticism.world.region.ISpiritualRegion;
import io.github.mysticism.world.state.ItemEmbeddingIndexState;
import io.github.mysticism.world.state.SpatialEmbeddingIndexState;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.stream.Collectors;

public class EmbeddingCommand {

    // Suggestion provider for slot names
    private static final SuggestionProvider<ServerCommandSource> SLOT_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(
                    Arrays.asList("mainhand", "offhand", "head", "chest", "legs", "feet"),
                    builder
            );

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("embedding")
                        // existing get_init (kept as-is)
                        .then(CommandManager.literal("get_init")
                                .then(CommandManager.argument("slot", StringArgumentType.string())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .executes(EmbeddingCommand::executeGetInit)
                                )
                        )
                        // /embedding get <slot>
                        .then(CommandManager.literal("get")
                                .then(CommandManager.argument("slot", StringArgumentType.string())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .executes(EmbeddingCommand::executeGetFromState)
                                )
                        )
                        // /embedding knn <k> <slot>  (items->items)
                        .then(CommandManager.literal("knn")
                                .then(CommandManager.argument("k", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("slot", StringArgumentType.string())
                                                .suggests(SLOT_SUGGESTIONS)
                                                .executes(EmbeddingCommand::executeKnnFromState)
                                        )
                                )
                        )
                        // -------- NEW spatial commands --------

                        // /embedding spatial_resonate <slot>
                        .then(CommandManager.literal("spatial_resonate")
                                .then(CommandManager.argument("slot", StringArgumentType.string())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .executes(EmbeddingCommand::executeSpatialResonate)
                                )
                        )

                        // /embedding spatial_knn <k> <slot>
                        .then(CommandManager.literal("spatial_knn")
                                .then(CommandManager.argument("k", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("slot", StringArgumentType.string())
                                                .suggests(SLOT_SUGGESTIONS)
                                                .executes(EmbeddingCommand::executeSpatialKnn)
                                        )
                                )
                        )

                        // /embedding spatial_tp <slot>
                        .then(CommandManager.literal("spatial_tp")
                                .then(CommandManager.argument("slot", StringArgumentType.string())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .executes(EmbeddingCommand::executeSpatialTp)
                                )
                        )

                        // /embedding spatial_closest_knn <k>
                        .then(CommandManager.literal("spatial_closest_knn")
                                .then(CommandManager.argument("k", IntegerArgumentType.integer(1))
                                        .executes(EmbeddingCommand::executeSpatialClosestKnn)
                                )
                        )

                        // /embedding spatial_knn_items <k>
                        .then(CommandManager.literal("spatial_knn_items")
                                .then(CommandManager.argument("k", IntegerArgumentType.integer(1))
                                        .executes(EmbeddingCommand::executeSpatialKnnItems)
                                )
                        )
        );
    }

    // -------------------- spatial helpers --------------------

    private static int executeSpatialResonate(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        String slotName = StringArgumentType.getString(ctx, "slot");

        Optional<Vec384f> itemVec = getItemVecFromSlot(player, slotName);
        if (itemVec.isEmpty()) return 0;

        KnnIndex spatial = SpatialEmbeddingIndexState.get(player.getServer()).getIndex();
        List<Pair<String, Float>> res = spatial.kNN(1, itemVec.get(), Metric.COSINE);
        if (res.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No spatial regions indexed yet.").formatted(Formatting.YELLOW), false);
            return 1;
        }
        Pair<String, Float> top = res.get(0);
        ctx.getSource().sendFeedback(() -> Text.literal(String.format("Best region: %s (dot=%.4f)", top.getKey(), top.getValue()))
                .formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int executeSpatialKnn(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        int k = IntegerArgumentType.getInteger(ctx, "k");
        String slotName = StringArgumentType.getString(ctx, "slot");

        Optional<Vec384f> itemVec = getItemVecFromSlot(player, slotName);
        if (itemVec.isEmpty()) return 0;

        KnnIndex spatial = SpatialEmbeddingIndexState.get(player.getServer()).getIndex();
        List<Pair<String, Float>> results = spatial.kNN(k, itemVec.get(), Metric.COSINE);
        results.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        if (results.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No spatial regions indexed yet.").formatted(Formatting.YELLOW), false);
            return 1;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Top " + results.size() + " regions (DOT):").formatted(Formatting.AQUA), false);
        int i = 1;
        for (Pair<String, Float> p : results) {
            String line = String.format("#%d  %.4f  %s", i++, p.getValue(), p.getKey());
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int executeSpatialTp(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        String slotName = StringArgumentType.getString(ctx, "slot");

        Optional<Vec384f> itemVec = getItemVecFromSlot(player, slotName);
        if (itemVec.isEmpty()) return 0;

        SpatialEmbeddingIndexState spatialState = SpatialEmbeddingIndexState.get(player.getServer());
        List<Pair<String, Float>> res = spatialState.getIndex().kNN(1, itemVec.get(), Metric.COSINE);
        if (res.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No spatial regions indexed yet.").formatted(Formatting.YELLOW), false);
            return 1;
        }

        String regionId = res.get(0).getKey();
        ISpiritualRegion region = spatialState.regionsView().get(regionId);
        if (region == null) {
            ctx.getSource().sendError(Text.literal("Region object missing for id: " + regionId));
            return 0;
        }

        BlockPos dest = region.resolveSpawn(player.getWorld());
        player.networkHandler.requestTeleport(dest.getX() + 0.5, dest.getY() + 1.01, dest.getZ() + 0.5, player.getYaw(), player.getPitch());
        ctx.getSource().sendFeedback(() -> Text.literal("Teleported to " + dest + " via " + regionId).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeSpatialClosestKnn(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        int k = IntegerArgumentType.getInteger(ctx, "k");

        String currentRegionId = spaceIdForCurrentChunk(player);
        SpatialEmbeddingIndexState spatial = SpatialEmbeddingIndexState.get(player.getServer());

        Optional<Vec384f> regionVec = maybeGet(spatial.getIndex(), currentRegionId);
        if (regionVec.isEmpty()) {
            ctx.getSource().sendError(Text.literal("No spatial embedding for current chunk: " + currentRegionId));
            return 0;
        }

        List<Pair<String, Float>> results = spatial.getIndex().kNN(k + 1, regionVec.get(), Metric.COSINE);
        // drop self if present
        results = results.stream()
                .filter(p -> !p.getKey().equals(currentRegionId))
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(k)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No neighbors found for current region.").formatted(Formatting.YELLOW), false);
            return 1;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Nearest regions to current chunk:").formatted(Formatting.AQUA), false);
        int i = 1;
        for (Pair<String, Float> p : results) {
            String line = String.format("#%d  %.4f  %s", i++, p.getValue(), p.getKey());
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int executeSpatialKnnItems(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        int k = IntegerArgumentType.getInteger(ctx, "k");

        String currentRegionId = spaceIdForCurrentChunk(player);
        SpatialEmbeddingIndexState spatial = SpatialEmbeddingIndexState.get(player.getServer());
        Optional<Vec384f> regionVec = maybeGet(spatial.getIndex(), currentRegionId);
        if (regionVec.isEmpty()) {
            ctx.getSource().sendError(Text.literal("No spatial embedding for current chunk: " + currentRegionId));
            return 0;
        }

        ItemEmbeddingIndexState itemState = ItemEmbeddingIndexState.get(player.getServer());
        List<Pair<String, Float>> results = itemState.getIndex().kNN(k, regionVec.get(), Metric.COSINE);
        results.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        if (results.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("Item index empty.").formatted(Formatting.YELLOW), false);
            return 1;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Top " + results.size() + " items related to this chunk:").formatted(Formatting.AQUA), false);
        int i = 1;
        for (Pair<String, Float> p : results) {
            String id = p.getKey();
            String pretty = id;
            try {
                Identifier parsed = Identifier.tryParse(id);
                if (parsed != null) {
                    Item it = Registries.ITEM.get(parsed);
                    if (it != null) pretty = it.getName().getString() + " (" + id + ")";
                }
            } catch (Exception ignored) {}
            String line = String.format("#%d  %.4f  %s", i++, p.getValue(), pretty);
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    // -------------------- existing item commands --------------------

    private static int executeGetFromState(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = src.getPlayerOrThrow();
        String slotName = StringArgumentType.getString(ctx, "slot");

        EquipmentSlot slot = parseSlotName(slotName);
        if (slot == null) {
            src.sendError(Text.literal("Invalid slot: " + slotName + ". Valid: mainhand, offhand, head, chest, legs, feet"));
            return 0;
        }

        ItemStack stack = player.getEquippedStack(slot);
        if (stack.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No item in " + slotName + " slot").formatted(Formatting.YELLOW), false);
            return 1;
        }

        String id = Registries.ITEM.getId(stack.getItem()).toString();
        ItemEmbeddingIndexState state = ItemEmbeddingIndexState.get(src.getServer());
        KnnIndex index = state.getIndex();

        Vec384f vec;
        try {
            vec = index.get(id);
        } catch (Throwable t) {
            src.sendError(Text.literal("No embedding found for " + id + " in ItemEmbeddingIndexState"));
            return 0;
        }

        float[] embedding = vec.data();
        src.sendFeedback(() -> Text.literal("Item: ").formatted(Formatting.AQUA)
                .append(Text.literal(id).formatted(Formatting.WHITE))
                .append(Text.literal(" (Slot: " + slotName + ")").formatted(Formatting.GRAY)), false);

        src.sendFeedback(() -> Text.literal("Embedding length: " + embedding.length).formatted(Formatting.GREEN), false);

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int display = Math.min(10, embedding.length);
        for (int i = 0; i < display; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.4f", embedding[i]));
        }
        if (embedding.length > display) {
            sb.append(", ... (").append(embedding.length - display).append(" more)");
        }
        sb.append("]");

        src.sendFeedback(() -> Text.literal("Embedding: ").formatted(Formatting.GOLD)
                .append(Text.literal(sb.toString()).formatted(Formatting.WHITE)), false);

        Mysticism.LOGGER.info("Embedding (from state) for '{}' loaded", id);
        return 1;
    }

    private static int executeKnnFromState(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = src.getPlayerOrThrow();
        int k = IntegerArgumentType.getInteger(ctx, "k");
        String slotName = StringArgumentType.getString(ctx, "slot");

        if (k <= 0) {
            src.sendError(Text.literal("k must be >= 1"));
            return 0;
        }

        EquipmentSlot slot = parseSlotName(slotName);
        if (slot == null) {
            src.sendError(Text.literal("Invalid slot: " + slotName + ". Valid: mainhand, offhand, head, chest, legs, feet"));
            return 0;
        }

        ItemStack stack = player.getEquippedStack(slot);
        if (stack.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No item in " + slotName + " slot").formatted(Formatting.YELLOW), false);
            return 1;
        }

        String queryId = Registries.ITEM.getId(stack.getItem()).toString();
        ItemEmbeddingIndexState state = ItemEmbeddingIndexState.get(src.getServer());
        KnnIndex index = state.getIndex();

        Vec384f query;
        try {
            query = index.get(queryId);
        } catch (Throwable t) {
            src.sendError(Text.literal("No embedding found for " + queryId + " in ItemEmbeddingIndexState"));
            return 0;
        }

        List<Pair<String, Float>> results = index.kNN(k, query, Metric.COSINE);
        if (results.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No neighbors found (index empty?)").formatted(Formatting.YELLOW), false);
            return 1;
        }

        results.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        src.sendFeedback(() -> Text.literal("Top " + results.size() + " similar to " + queryId + " (cosine):")
                .formatted(Formatting.AQUA), false);

        int rank = 1;
        for (Pair<String, Float> p : results) {
            String id = p.getKey();
            float score = p.getValue();

            String pretty = id;
            try {
                Item item = Registries.ITEM.get(Identifier.tryParse(id));
                if (item != null) pretty = item.getName().getString() + " (" + id + ")";
            } catch (Exception ignored) {}

            int r = rank++;
            String line = String.format("#%d  %.4f  %s", r, score, pretty);
            src.sendFeedback(() -> Text.literal(line).formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    // -------------------- common utils --------------------

    private static Optional<Vec384f> getItemVecFromSlot(ServerPlayerEntity player, String slotName) {
        EquipmentSlot slot = parseSlotName(slotName);
        if (slot == null) {
            player.sendMessage(Text.literal("Invalid slot: " + slotName + ". Valid: mainhand, offhand, head, chest, legs, feet").formatted(Formatting.RED), false);
            return Optional.empty();
        }

        ItemStack stack = player.getEquippedStack(slot);
        if (stack.isEmpty()) {
            player.sendMessage(Text.literal("No item in " + slotName + " slot").formatted(Formatting.YELLOW), false);
            return Optional.empty();
        }

        String id = Registries.ITEM.getId(stack.getItem()).toString();
        ItemEmbeddingIndexState itemState = ItemEmbeddingIndexState.get(player.getServer());
        try {
            return Optional.of(itemState.getIndex().get(id));
        } catch (Throwable t) {
            player.sendMessage(Text.literal("No embedding found for " + id).formatted(Formatting.RED), false);
            return Optional.empty();
        }
    }

    private static Optional<Vec384f> maybeGet(KnnIndex index, String id) {
        try { return Optional.of(index.get(id)); }
        catch (Throwable t) { return Optional.empty(); }
    }

    private static String spaceIdForCurrentChunk(ServerPlayerEntity player) {
        ChunkPos cp = player.getChunkPos();
        Identifier dim = player.getWorld().getRegistryKey().getValue();
        return dim + "|chunk|" + cp.x + "," + cp.z;
    }

    // -------------------- existing get_init --------------------

    private static int executeGetInit(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String slotName = StringArgumentType.getString(context, "slot");

        EquipmentSlot slot = parseSlotName(slotName);
        if (slot == null) {
            source.sendError(Text.literal("Invalid slot: " + slotName + ". Valid slots: mainhand, offhand, head, chest, legs, feet"));
            return 0;
        }

        var itemStack = player.getEquippedStack(slot);
        if (itemStack.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No item found in " + slotName + " slot").formatted(Formatting.YELLOW), false);
            return 1;
        }

        if (!EmbeddingHelper.isReady()) {
            source.sendError(Text.literal("Embedding service is not ready yet. Please wait for initialization to complete."));
            return 0;
        }

        String itemName = itemStack.getName().getString();
        try {
            Vec384f embeddingObj = EmbeddingHelper.getEmbedding(itemName).get();
            float[] embedding = embeddingObj.data();

            source.sendFeedback(() -> Text.literal("Item: ").formatted(Formatting.AQUA)
                    .append(Text.literal(itemName).formatted(Formatting.WHITE))
                    .append(Text.literal(" (Slot: " + slotName + ")").formatted(Formatting.GRAY)), false);

            source.sendFeedback(() -> Text.literal("Embedding Vector Dimensionality: " + embedding.length).formatted(Formatting.GREEN), false);
            source.sendFeedback(() -> Text.literal("Embedding Vector Dimensionality: " + embeddingObj.length()).formatted(Formatting.GREEN), false);

            StringBuilder embeddingStr = new StringBuilder();
            embeddingStr.append("[");
            int displayCount = Math.min(10, embedding.length);
            for (int i = 0; i < displayCount; i++) {
                if (i > 0) embeddingStr.append(", ");
                embeddingStr.append(String.format("%.4f", embedding[i]));
            }
            if (embedding.length > displayCount) {
                embeddingStr.append(", ... (").append(embedding.length - displayCount).append(" more)");
            }
            embeddingStr.append("]");

            source.sendFeedback(() -> Text.literal("Embedding: ").formatted(Formatting.GOLD)
                    .append(Text.literal(embeddingStr.toString()).formatted(Formatting.WHITE)), false);

    //        Mysticism.LOGGER.info("Full embedding for '{}': {}", itemName, Arrays.toString(embedding));

        } catch (Throwable ignored) { };
        return 1;
    }

    private static EquipmentSlot parseSlotName(String slotName) {
        return switch (slotName.toLowerCase()) {
            case "mainhand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
