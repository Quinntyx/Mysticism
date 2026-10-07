package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.block.BlockRenderType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Cheap shared affine border field; near-five-meter source patch is unchanged. No SDF or invented floor. */
final class MeshStitcher {
    private static final double WIDTH=3,MAX_SHIFT=.12;
    private record Shape(Vec3d min,Vec3d x,Vec3d y,Vec3d z,List<Box> collision) {}
    private static final class Region {
        final String id;Box bounds;TerrainMeshFrame.Cell skin;
        Region(TerrainMeshFrame.Cell c){id=c.landmarkId();bounds=c.bounds();}
    }
    static List<TerrainMeshFrame.Cell> stitch(List<TerrainMeshFrame.Cell> input,List<TerrainMeshFrame.Material> palette,
                                             String local,boolean shallow,Vec3d eye) {
        boolean[] cube=new boolean[palette.size()];
        if(!shallow)for(int i=0;i<cube.length;i++) {
            var state=TerrainMaterials.resolve(palette.get(i));cube[i]=state.isOpaque() && !state.hasBlockEntity() && state.getRenderType()==BlockRenderType.MODEL;
        }
        Map<String,Region> regions=new LinkedHashMap<>();
        for(var c:input) {
            Region r=regions.computeIfAbsent(c.landmarkId(),ignored->new Region(c));r.bounds=r.bounds.union(c.bounds());
            if(!shallow && r.skin==null && compatible(c,cube))r.skin=c;
        }
        List<TerrainMeshFrame.Cell> deformed=new ArrayList<>(input.size());
        for(var c:input) {
            Region own=regions.get(c.landmarkId());
            double strength=shallow || c.landmarkId().equals(local)?0:smooth((Math.sqrt(SourceMeshBuilder.distanceSquared(c.bounds(),eye))-5)/WIDTH);
            if(strength==0){deformed.add(c);continue;}
            double origin=shift(c.min(),own,regions.values())*strength;
            double x=shift(c.min().add(c.axisX()),own,regions.values())*strength-origin;
            double y=shift(c.min().add(c.axisY()),own,regions.values())*strength-origin;
            double z=shift(c.min().add(c.axisZ()),own,regions.values())*strength-origin;
            double scale=Math.min(1,MAX_SHIFT/Math.max(1e-9,Math.abs(origin)+Math.abs(x)+Math.abs(y)+Math.abs(z)));
            Vec3d min=c.min().add(0,origin*scale,0),ax=c.axisX().add(0,x*scale,0),ay=c.axisY().add(0,y*scale,0),az=c.axisZ().add(0,z*scale,0);
            double old=c.axisX().dotProduct(c.axisY().crossProduct(c.axisZ())),next=ax.dotProduct(ay.crossProduct(az));
            if(next*old<=0 || Math.abs(next)<Math.abs(old)*.5){min=c.min();ax=c.axisX();ay=c.axisY();az=c.axisZ();}
            TerrainMeshFrame.Cell skin=c;
            if(compatible(c,cube))for(Region other:regions.values()) {
                if(other==own || other.skin==null || !other.bounds.intersects(own.bounds) || !other.bounds.expand(.5).contains(c.bounds().getCenter()))continue;
                double weight=strength*(1-smooth(border(c.bounds().getCenter(),own.bounds)/WIDTH))*.5;
                String pair=own.id.compareTo(other.id)<0?own.id+other.id:other.id+own.id;
                double sample=(SourceMeshBuilder.key(pair,c.sourceMin(),1)>>>11)*0x1.0p-53;
                if(sample<weight){skin=other.skin;break;}
            }
            // Models and SAT use exactly these same bounded columns. Only equal full-cube collision skins may dither.
            deformed.add(new TerrainMeshFrame.Cell(c.key(),skin.material(),c.landmarkId(),c.sourceMin(),min,c.size(),ax,ay,az,
                    skin.color(),skin.light(),c.opacity(),c.collision()));
        }
        Map<Shape,Integer> index=new HashMap<>();var output=new ArrayList<TerrainMeshFrame.Cell>();
        for(var cell:deformed) {
            Shape shape=new Shape(cell.min(),cell.axisX(),cell.axisY(),cell.axisZ(),cell.collision());Integer at=index.putIfAbsent(shape,output.size());
            if(at==null){output.add(cell);continue;}
            var previous=output.get(at);
            if(shallow && previous.landmarkId().equals(local))continue;
            if(shallow && cell.landmarkId().equals(local)){output.set(at,cell);continue;}
            if(!compatible(cell,cube) || !compatible(previous,cube))continue;
            double weight=cell.opacity()/Math.max(1e-6,cell.opacity()+previous.opacity());
            String first=previous.landmarkId().compareTo(cell.landmarkId())<0?previous.landmarkId():cell.landmarkId();
            double sample=(SourceMeshBuilder.key(first,cell.sourceMin(),1)>>>11)*0x1.0p-53;
            var skin=sample<weight?cell:previous;
            output.set(at,new TerrainMeshFrame.Cell(previous.key(),skin.material(),previous.landmarkId(),previous.sourceMin(),previous.min(),previous.size(),
                    previous.axisX(),previous.axisY(),previous.axisZ(),skin.color(),skin.light(),Math.max(previous.opacity(),cell.opacity()),previous.collision()));
        }
        return List.copyOf(output);
    }
    private static boolean compatible(TerrainMeshFrame.Cell c,boolean[] cube){return cube[c.material()] && c.collision().size()==1 && c.collision().getFirst().equals(SourceMeshBuilder.UNIT);}
    private static double shift(Vec3d p,Region own,Collection<Region> regions) {
        double sum=0,weight=0;
        for(Region other:regions) {
            if(other==own || !own.bounds.intersects(other.bounds) || !other.bounds.expand(.5).contains(p))continue;
            double difference=other.bounds.maxY-own.bounds.maxY;
            if(Math.abs(difference)>.5)continue; // Keep actual cliffs; only soften compatible nearby height borders.
            double w=(1-smooth(border(p,own.bounds)/WIDTH))*(1-smooth(Math.sqrt(SourceMeshBuilder.distanceSquared(other.bounds,p))/.5));
            sum+=Math.max(-MAX_SHIFT,Math.min(MAX_SHIFT,difference*.5))*w;weight+=w;
        }
        return weight==0?0:sum/Math.max(1,weight);
    }
    private static double border(Vec3d p,Box b){return Math.max(0,Math.min(Math.min(p.x-b.minX,b.maxX-p.x),Math.min(Math.min(p.y-b.minY,b.maxY-p.y),Math.min(p.z-b.minZ,b.maxZ-p.z))));}
    private static double smooth(double x){x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);}
}
