package io.github.mysticism.client.spiritworld.terrain;

import io.github.mysticism.dimension.spiritworld.terrain.MeshCollision;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainEvidence;
import io.github.mysticism.dimension.spiritworld.terrain.TerrainMeshFrame;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.Map;

/** Combined uploaded-vertex bounds, fog hints and actual affine support; no client/GL bootstrap. */
public final class UploadedTerrainEvidenceTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static boolean any(float[] field) { for(float value:field)if(value>0)return true;return false; }
    private static TerrainMeshFrame frame(float alpha) {
        // Fence/extended fallback fixture: unit model ends at y=8; actual top is at y=12.
        var cell=new TerrainMeshFrame.Cell(9,0,"source-owner",new Vec3d(10,20,30),Vec3d.ZERO,
                new Vec3d(1,1,1),new Vec3d(8,0,0),new Vec3d(0,8,0),new Vec3d(0,0,8),
                0xffffff,0xf000f0,alpha,List.of(new Box(0,0,0,1,1.5,1)));
        return new TerrainMeshFrame(2,false,"minecraft:overworld",Vec3d.ZERO,Vec3d.ZERO,
                List.of(new TerrainMeshFrame.Material("minecraft:oak_fence",Map.of())),List.of(cell));
    }
    public static void main(String[] args) {
        var f=frame(1);var model=new TerrainVertexConsumer.Bounds();var batch=new TerrainVertexConsumer.Bounds();
        var empty=new TerrainVertexConsumer.Bounds();batch.include(empty);
        check(batch.empty(),"an empty baked model cannot manufacture culling/density bounds");
        model.add(0,0,0);model.add(8,12,8);batch.include(model);
        Box uploaded=model.world(Vec3d.ZERO);
        check(batch.world(Vec3d.ZERO).equals(uploaded),"batch culling uses the same measured model vertices as density");
        List<SpiritMediumField.Surface> surfaces=List.of(new SpiritMediumField.Surface(uploaded,1));
        var medium=new SpiritMediumField();Vec3d camera=Vec3d.ZERO;
        float[] density=medium.update(f,camera,surfaces).orElseThrow().occupancy();
        check(density[8+16*(9+16*8)]==.5f,"extended uploaded geometry contributes above old unit-model bounds");
        check(medium.update(f,camera,surfaces).isEmpty(),"stable uploaded list reuses cache");
        check(!any(medium.update(f,camera,List.of()).orElseThrow().occupancy()),"no emitted geometry clears hints even for unchanged frame");
        check(any(medium.update(f,camera,surfaces).orElseThrow().occupancy()),"resource-reupload republishes measured hints without packet");
        List<SpiritMediumField.Surface> hidden=List.of(new SpiritMediumField.Surface(uploaded,0));
        check(!any(medium.update(frame(0),camera,hidden).orElseThrow().occupancy()),"fully faded uploaded surface absent from medium");
        medium.clear();
        check(medium.update(f,camera,surfaces).isPresent(),"disconnect/failure clear invalidates uploaded-bounds cache");
        check(new SpiritMediumField().update(f,camera).orElseThrow().occupancy()[8+16*(9+16*8)]==.5f,
                "frame-only conservative hints also include extended source collision bounds");
        Box body=new Box(2,12,2,6,13.8,6);
        var evidence=TerrainEvidence.inspect(f,body);
        var contact=evidence.contact().orElseThrow();
        check(contact.key()==9 && contact.meshOwner().equals("source-owner"),"diagnostic evidence names the real mesh producer");
        check(Math.abs(contact.point().y-12)<1e-7 && uploaded.contains(contact.point()),"affine contact lies on the actual uploaded top, not unit proxy");
        check(contact.normal().y>.99 && Math.abs(contact.sourcePosition().y-21.5)<1e-5,
                "support normal/source witness describe the extended visible surface");
        check(new MeshCollision.Index(f).ground(body).isPresent(),"same rendered top remains usable for walking contact");
        check(TerrainEvidence.inspect(frame(0),body).contact().isEmpty(),"faded evidence and render density agree on absent support");
        System.out.println("UploadedTerrainEvidenceTest: "+checks+" combined geometry checks passed (no live GL)");
    }
}
