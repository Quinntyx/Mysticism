package io.github.mysticism.dimension.spiritworld;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** Source ghosts and physical carrier coordinates are never an interaction authority. */
public final class SpiritInteractionGuard {
    private static boolean initialized;
    private SpiritInteractionGuard() {}
    private static boolean spirit(World world) {
        return world.getRegistryKey().getValue().equals(Identifier.of("mysticism", "spirit"));
    }
    public static void init() {
        if (initialized) return;
        initialized = true;
        AttackBlockCallback.EVENT.register((player, world, hand, pos, side) ->
                spirit(world) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                spirit(world) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                spirit(world) ? ActionResult.FAIL : ActionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                spirit(world) ? ActionResult.FAIL : ActionResult.PASS);
        // Semantic touch uses its authenticated, mutually validated custom packet.
        // ItemEntity collision pickup and held-item use (e.g. magnets) remain unchanged.
    }
}
