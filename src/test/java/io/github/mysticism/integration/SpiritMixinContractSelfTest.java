package io.github.mysticism.integration;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Offline resource/mapped-target guards; not a claim of live mixin or GPU execution. */
public final class SpiritMixinContractSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static com.google.gson.JsonObject resource(String name) throws Exception {
        var stream = SpiritMixinContractSelfTest.class.getClassLoader().getResourceAsStream(name);
        check(stream != null, "Missing packaged resource " + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    public static void main(String[] args) throws Exception {
        var manifest = resource("fabric.mod.json");
        int bindings = 0;
        for (var entry : manifest.getAsJsonArray("mixins"))
            if (entry.isJsonPrimitive() && entry.getAsString().equals("mysticism.spirit.mixins.json")) bindings++;
        check(bindings == 1, "Spirit common mixins must be registered exactly once");
        var config = resource("mysticism.spirit.mixins.json");
        check(config.get("required").getAsBoolean(), "Gameplay hooks must not silently disappear");
        check(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1, "Missing target must fail visibly");
        check(config.get("package").getAsString().equals("io.github.mysticism"), "Wrong mixin package");
        Set<String> actual = new HashSet<>();
        for (var entry : config.getAsJsonArray("mixins")) check(actual.add(entry.getAsString()), "Duplicate common hook");
        Set<String> expected = Set.of("landmark.extract.SourceBlockUpdateMixin", "activity.mixin.ActivityStatMixin",
                "activity.mixin.ActivityPlacementMixin", "activity.mixin.ActivitySpawnMixin");
        check(actual.equals(expected), "Incomplete source edits, placement, stat or spawn hooks");
        for (String name : actual) {
            Class.forName("io.github.mysticism." + name, false, SpiritMixinContractSelfTest.class.getClassLoader());
            check(true, "Hook class exists");
        }
        check(World.class.getDeclaredMethod("setBlockState", BlockPos.class, BlockState.class, int.class, int.class).getReturnType() == boolean.class,
                "Source edit target descriptor changed");
        check(ServerPlayerEntity.class.getDeclaredMethod("increaseStat", Stat.class, int.class).getReturnType() == void.class,
                "Stat delta target descriptor changed");
        check(BlockItem.class.getDeclaredMethod("place", ItemPlacementContext.class).getReturnType() == net.minecraft.util.ActionResult.class,
                "Placement target descriptor changed");
        check(ServerWorld.class.getDeclaredMethod("spawnEntity", Entity.class).getReturnType() == boolean.class,
                "Spawn target descriptor changed");
        System.out.println("SpiritMixinContractSelfTest: " + checks + " checks passed (offline contracts only)");
    }
}
