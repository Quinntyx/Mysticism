package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.Optional;

/** Evidence from one accepted geometry frame. Publication is not a client draw/landing acknowledgement. */
public record TerrainEvidence(long revision,int visibleCells,int collisionShapes,Optional<Contact> contact) {
    public record Contact(long key,String meshOwner,Vec3d point,Vec3d normal,Vec3d sourcePosition) {}
    public static TerrainEvidence inspect(TerrainMeshFrame frame,Box body) {
        int visible=0,shapes=0;
        for(var c:frame.cells())if(c.opacity()>0){visible++;shapes+=c.collision().size();}
        var contact=new MeshCollision.Index(frame).groundContact(body).map(g->{
            var hit=g.hit();var c=hit.cell();
            return new Contact(c.key(),c.landmarkId(),g.point(),hit.normal(),
                    MeshContact.source(c,g.interiorPoint()));
        });
        return new TerrainEvidence(frame.revision(),visible,shapes,contact);
    }
}
