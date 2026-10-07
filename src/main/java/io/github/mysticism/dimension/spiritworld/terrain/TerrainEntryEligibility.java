package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import java.util.*;

/** Entry authorization is personal and current; a union desired set cannot authorize landing. */
final class TerrainEntryEligibility {
    static Set<String> owners(RepresentativeSelector.RepresentativeSet selection,LandmarkEmbedding query,
            Point3 player,Map<String,TerrainField.Layer> layers,TerrainConfig config) {
        if(selection==null)return Set.of();
        selection.profile().requireCompatible(query.profile());
        Set<String> result=new TreeSet<>();
        for(var rep:selection.representatives()) {
            var layer=layers.get(rep.landmarkId()); if(layer==null)continue;
            query.profile().requireCompatible(layer.embedding().profile());
            if(query.distanceSquared(layer.embedding())>=config.semanticRadius()*config.semanticRadius())continue;
            if(!config.fog().shouldPrefetch(layer.placement().projectedBounds(layer.metadata().header().bounds()).distanceSquared(player)))continue;
            result.add(rep.landmarkId());
        }
        return result;
    }
}
