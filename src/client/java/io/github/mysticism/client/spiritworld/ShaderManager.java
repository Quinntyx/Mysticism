package io.github.mysticism.client.spiritworld;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.ladysnake.satin.api.event.ShaderEffectRenderCallback;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;
import net.minecraft.util.Identifier;

public final class ShaderManager {
    // 1. Declare the shader effect, using the new simplified identifier.
    //    "mysticism" is your mod ID, "red_channel" is the name of your .json file.
    public static final ManagedShaderEffect KUWAHARA_SHADER = ShaderEffectManager.getInstance()
            .manage(Identifier.of("mysticism", "shaders/post/kuwahara.json"));

    public static void init() {
        ShaderEffectRenderCallback.EVENT.register(delta -> {
            // Check if the shader is not null to prevent crashes
            if (KUWAHARA_SHADER.getShaderEffect() != null) {
                // Render the shader effect
                KUWAHARA_SHADER.render(delta);
            }
        });
    }
}