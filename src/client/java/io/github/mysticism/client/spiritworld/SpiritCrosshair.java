package io.github.mysticism.client.spiritworld;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Carrier coordinates have no pickable terrain. Semantic feedback is not a vanilla entity hit. */
public final class SpiritCrosshair {
    private static final Identifier SPIRIT = Identifier.of("mysticism", "spirit");

    private SpiritCrosshair() {}

    public static boolean inSpiritWorld(MinecraftClient client) {
        return client.world != null && client.world.getRegistryKey().getValue().equals(SPIRIT);
    }

    public static BlockHitResult miss(Vec3d from, Vec3d direction) {
        Vec3d end = from.add(direction.multiply(SpiritTouchPicking.REACH));
        return BlockHitResult.createMissed(end, Direction.getFacing(direction.x, direction.y, direction.z),
                BlockPos.ofFloored(end));
    }

    /** Replaces both fields every frame, even before navigation/mesh packets arrive. */
    public static void clearCarrierTarget(MinecraftClient client, float tickDelta) {
        var cameraEntity = client.getCameraEntity();
        Vec3d from = cameraEntity == null ? Vec3d.ZERO : cameraEntity.getCameraPosVec(tickDelta);
        Vec3d direction = cameraEntity == null ? new Vec3d(0, 0, 1) : cameraEntity.getRotationVec(tickDelta);
        client.targetedEntity = null;
        client.crosshairTarget = miss(from, direction);
    }

    /** Four cyan corners mean a projected deep peer is locally in reach and mesh-unoccluded.
     * The server still validates mutual reach/alignment; this is not a combat indicator. */
    public static void drawTouchHint(MinecraftClient client, DrawContext draw) {
        if (!inSpiritWorld(client) || client.player == null || client.player.isSpectator()
                || !client.options.getPerspective().isFirstPerson() || client.options.hudHidden
                || client.currentScreen != null
                || SpiritSemanticEntityRenderer.touchTarget(client, SpiritTouchPicking.REACH).isEmpty()) return;
        int x = draw.getScaledWindowWidth() / 2, y = draw.getScaledWindowHeight() / 2;
        int color = 0xff80e8ff;
        for (int dx : new int[] {-1, 1}) for (int dy : new int[] {-1, 1}) {
            int left = x + (dx < 0 ? -6 : 4), top = y + (dy < 0 ? -6 : 4);
            draw.fill(left, top, left + 2, top + 2, color);
        }
    }
}
