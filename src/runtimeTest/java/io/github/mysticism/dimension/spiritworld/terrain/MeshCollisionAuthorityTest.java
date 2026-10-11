package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.List;
import java.util.Map;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Exercises the production affine collision/packet/transition guards, without bootstrapping Minecraft. */
public final class MeshCollisionAuthorityTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;
        if(!value)throw new AssertionError(message);
    }
    private static TerrainMeshFrame.Cell cell(long key,Vec3d min,Vec3d x,Vec3d y,Vec3d z,float opacity,Box... shapes) {
        return new TerrainMeshFrame.Cell(key,0,"source",Vec3d.ZERO,min,new Vec3d(1,1,1),x,y,z,
                -1,0,opacity,List.of(shapes));
    }
    private static TerrainMeshFrame.Cell cube(long key,double x,float opacity,Box... shapes) {
        return cell(key,new Vec3d(x,0,0),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),opacity,shapes);
    }
    private static TerrainMeshFrame frame(TerrainMeshFrame.Cell... cells) {
        return new TerrainMeshFrame(1,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:stone",Map.of())),List.of(cells));
    }
    public static void main(String[] args) {
        Box unit=new Box(0,0,0,1,1,1),inside=new Box(.2,.2,.2,.8,.8,.8),outside=inside.offset(2,0,0);
        var solid=frame(cube(1,0,1,unit));
        var hidden=frame(cube(1,0,0,unit));
        check(!MeshCollision.bodyClear(solid,inside),"Visible mesh is authoritative even without carrier blocks");
        check(MeshCollision.bodyClear(hidden,inside),"Fully hidden cell cannot block body clearance");
        check(new MeshCollision.Index(hidden).sweep(outside,new Vec3d(-2,0,0)).isEmpty(),"Hidden cells cannot block motion");
        check(new MeshCollision.Index(solid).sweep(outside,new Vec3d(-2,0,0)).isPresent(),"Swept motion still collides with visible terrain");
        check(MeshCollision.newlyColliding(solid,outside,inside),"Server endpoint guard must reject entering the mesh");
        check(!MeshCollision.newlyColliding(solid,inside,inside.offset(.05,0,0)),"Existing overlap is not a new packet collision");
        check(!MeshCollision.newlyColliding(solid,inside,outside),"Escaping overlap must not rubber-band back into it");
        check(!MeshCollision.newlyColliding(hidden,outside,inside),"Server cannot see phantom hidden obstacles");
        check(MeshCollision.bodyClear(solid,new Box(1,.2,.2,1.6,.8,.8)),"Touching is not penetration");
        check(!MeshCollision.newlyColliding(solid,outside,new Box(1,.2,.2,1.6,.8,.8)),"Endpoint touching must not reject legal movement");
        check(MeshCollision.transitionClear(solid,hidden,inside),"Fade-out must release body/transition guards");
        check(!MeshCollision.transitionClear(hidden,solid,inside),"Fade-in at identical coordinates is a new visible collision");
        check(!MeshCollision.transitionClear(frame(cube(1,2,1,unit)),solid,inside),"Visible mesh moving into the body must remain guarded");
        check(MeshCollision.transitionClear(frame(cube(1,2,1,unit)),hidden,inside),"Moving an invisible cell cannot pin frame publication");
        check(MeshCollision.bodyClear(frame(cube(1,0,1)),inside),"Rendered non-collidable models do not invent full block obstacles");
        var slab=frame(cube(1,0,1,new Box(0,0,0,1,.5,1)));
        check(MeshCollision.bodyClear(slab,new Box(.2,.5,.2,.8,1.2,.8)),"Clearance respects partial source shape");
        check(!MeshCollision.bodyClear(slab,new Box(.2,.4,.2,.8,1.2,.8)),"Partial shapes still stop penetration");
        // Fence-like shapes may extend beyond unit-model coordinates. Broad phases must use SHAPE bounds.
        var extended=frame(cube(1,0,1,new Box(0,0,0,1,1.5,1)));
        Box above=new Box(.2,1.1,.2,.8,1.4,.8);
        check(!MeshCollision.bodyClear(extended,above),"Extended local source collision cannot be lost to unit bounds");
        check(MeshCollision.newlyColliding(extended,above.offset(2,0,0),above),"Packet guard respects extended shapes");
        check(!MeshCollision.transitionClear(frame(),extended,above),"Acquiring an extended shape must check body clearance");
        var distantExtended=frame(cube(1,0,1,new Box(2,0,0,3,1,1)));
        var grown=frame(cell(1,Vec3d.ZERO,new Vec3d(10,0,0),new Vec3d(0,1,0),new Vec3d(0,0,1),1,new Box(2,0,0,3,1,1)));
        check(!MeshCollision.transitionClear(distantExtended,grown,new Box(22,.2,.2,23,.8,.8)),"Transition broad phase includes transformed shapes outside unit model");
        // A rotated cell's enclosing AABB has empty corners; no voxelized/full-AABB fallback is allowed.
        double d=Math.sqrt(.5);
        var rotated=frame(cell(1,Vec3d.ZERO,new Vec3d(d,0,d),new Vec3d(0,1,0),new Vec3d(-d,0,d),1,unit));
        Box emptyCorner=new Box(.55,.2,.05,.65,.8,.15),center=new Box(-.1,.2,.6,.1,.8,.8);
        check(rotated.cells().getFirst().bounds().intersects(emptyCorner),"Fixture actually lies in conservative bounds");
        check(MeshCollision.bodyClear(rotated,emptyCorner),"Rotated mesh empty corner is not a phantom full-block obstacle");
        check(!MeshCollision.bodyClear(rotated,center),"Affine SAT retains the actual rotated interior");
        check(!MeshCollision.newlyColliding(rotated,outside,emptyCorner),"Packet validation shares SAT rather than AABB voxels");
        check(MeshCollision.newlyColliding(rotated,outside,center),"Packet validation rejects real distorted terrain");
        check(new MeshCollision.Index(rotated).sweep(emptyCorner,new Vec3d(0,-.1,0)).isEmpty(),"Prediction shares empty-corner clearance");
        check(MeshCollision.bodyClear(frame(cube(1,10,1,unit)),inside),"Another observer's projection must not leak collision into this frame");
        check(!MeshCollision.bodyClear(frame(cube(1,0,.01f,unit)),inside),"Partially visible fading terrain still collides");
        System.out.println("MeshCollisionAuthorityTest: "+checks+" production geometry checks passed (no live game)");
    }
}
