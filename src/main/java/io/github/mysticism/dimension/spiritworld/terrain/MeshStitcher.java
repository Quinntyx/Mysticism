package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Bounded exact shared-surface stitch: removes coplanar duplicates without creating holes or invisible physics.
 * Material dithering is source-grid deterministic; unequal collision primitives remain a true union. */
final class MeshStitcher {
    private record Shape(Vec3d min,Vec3d x,Vec3d y,Vec3d z,List<Box> collision) {}
    static List<TerrainMeshFrame.Cell> stitch(List<TerrainMeshFrame.Cell> input,String local,boolean shallow) {
        Map<Shape,Integer> index=new HashMap<>();var output=new ArrayList<TerrainMeshFrame.Cell>();
        for(var cell:input) {
            Shape shape=new Shape(cell.min(),cell.axisX(),cell.axisY(),cell.axisZ(),cell.collision());
            Integer at=index.putIfAbsent(shape,output.size());
            if(at==null){output.add(cell);continue;}
            var previous=output.get(at);
            if(shallow && previous.landmarkId().equals(local))continue;
            if(shallow && cell.landmarkId().equals(local)){output.set(at,cell);continue;}
            double weight=cell.opacity()/Math.max(1e-6,cell.opacity()+previous.opacity());
            String first=previous.landmarkId().compareTo(cell.landmarkId())<0?previous.landmarkId():cell.landmarkId();
            long h=SourceMeshBuilder.key(first,cell.sourceMin(),1);
            double sample=(h>>>11)*0x1.0p-53;
            int material=sample<weight?cell.material():previous.material();
            int color=sample<weight?cell.color():previous.color(),light=sample<weight?cell.light():previous.light();
            // Preserve the existing source owner/key; only the physically identical skin is blended.
            output.set(at,new TerrainMeshFrame.Cell(previous.key(),material,previous.landmarkId(),previous.sourceMin(),previous.min(),previous.size(),
                    previous.axisX(),previous.axisY(),previous.axisZ(),color,light,Math.max(previous.opacity(),cell.opacity()),previous.collision()));
        }
        return List.copyOf(output);
    }
}
