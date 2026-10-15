package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.block.BlockRenderType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Remove only redundant full-cube surfaces. Never warp individual projected source leaves. */
final class MeshStitcher {
    private static final Box UNIT=new Box(0,0,0,1,1,1);
    private record Shape(Vec3d min,Vec3d x,Vec3d y,Vec3d z,List<Box> collision) {}
    static List<TerrainMeshFrame.Cell> stitch(List<TerrainMeshFrame.Cell> input,List<TerrainMeshFrame.Material> palette,
                                             String local,boolean shallow,Vec3d eye) {
        // The source-window projection already supplies the distortion shared by models and SAT.
        // A position-dependent field fitted independently to each affine leaf is NOT continuous:
        // adjacent leaves (especially across octree LOD/near-five-meter boundaries) disagree on
        // their common face. Preserve the projection instead of filling real cliffs or cracks
        // with fabricated support. Material blending is limited to exact coincident cubes.
        if(shallow)return List.copyOf(input);
        boolean[] cube=new boolean[palette.size()];
        for(int i=0;i<cube.length;i++) {
            var state=TerrainMaterials.resolve(palette.get(i));
            cube[i]=state.isOpaque() && !state.hasBlockEntity() && state.getRenderType()==BlockRenderType.MODEL;
        }
        return deduplicate(input,cube,local);
    }
    static List<TerrainMeshFrame.Cell> deduplicate(List<TerrainMeshFrame.Cell> input,boolean[] cube,String local) {
        Map<Shape,Integer> index=new HashMap<>();var output=new ArrayList<TerrainMeshFrame.Cell>();
        for(var cell:input) {
            // Empty/partial collision says nothing about a baked model's surface. Flowers,
            // fences and other non-cubes must not disappear just because shapes match.
            if(!compatible(cell,cube)){output.add(cell);continue;}
            Shape shape=new Shape(cell.min(),cell.axisX(),cell.axisY(),cell.axisZ(),cell.collision());
            Integer at=index.putIfAbsent(shape,output.size());
            if(at==null){output.add(cell);continue;}
            var previous=output.get(at);
            // Keep a whole producing cell, not another owner's material under a stale key.
            // Opaque support beats a fading duplicate; ties are input-order independent.
            if(prefer(cell,previous,local))output.set(at,cell);
        }
        return List.copyOf(output);
    }
    private static boolean prefer(TerrainMeshFrame.Cell a,TerrainMeshFrame.Cell b,String local) {
        int alpha=Float.compare(a.opacity(),b.opacity());if(alpha!=0)return alpha>0;
        boolean al=a.landmarkId().equals(local),bl=b.landmarkId().equals(local);if(al!=bl)return al;
        int owner=a.landmarkId().compareTo(b.landmarkId());if(owner!=0)return owner<0;
        return Long.compareUnsigned(a.key(),b.key())<0;
    }
    private static boolean compatible(TerrainMeshFrame.Cell c,boolean[] cube){return cube[c.material()] && c.collision().size()==1 && c.collision().getFirst().equals(UNIT);}
}
