package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.block.BlockState;

/** Same registry/property resolution for server source shapes and client baked models. */
public final class TerrainMaterials {
    private TerrainMaterials() {}
    public static BlockState resolve(TerrainMeshFrame.Material material) { return SourceMeshBuilder.resolve(material); }
}
