package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Per-region resumable source-octree traversal. A shared repository reader is held only
 * until one page boundary; decoded leaf/material work and retained geometry stay bounded. */
final class TerrainGeometryStream {
    static final int MAX_NODES=768;
    final List<String> keys;
    final Bounds near;
    final Vec3d focus;
    final Map<SourceMeshBuilder.Key,SourceMeshBuilder.Node> nodes=new HashMap<>();
    private final Map<BlockPalette.State,SourceMeshBuilder.Tile> materials=new HashMap<>();
    int pageIndex;
    boolean far,complete,changed;
    SparseOctree<BlockSample>.Cursor cells;
    GeometryPage page;
    TerrainGeometryStream(LandmarkMetadata metadata,Vec3d focus) {
        keys=List.copyOf(metadata.geometryKeys());this.focus=focus;near=SourceMeshBuilder.range(focus,16);
    }
    boolean current(LandmarkMetadata metadata){return metadata!=null && keys.equals(metadata.geometryKeys());}
    Bounds range(LandmarkMetadata metadata){return far?metadata.header().bounds():near;}
    void accept(GeometryPage geometry){page=geometry;cells=geometry.cells().cursor(far?geometry.bounds():near);}
    /** Node visits, emitted leaves and newly resolved server-thread materials are all budgeted. */
    void advance(String owner,ToDoubleFunction<SourceMeshBuilder.Node> distance) {
        if(cells==null)return;
        for(var cell:cells.advance(128,32)) {
            if(cell.value().occupancy()!=BlockSample.Occupancy.SOLID)continue;
            Bounds b=cell.bounds();BlockPos at=new BlockPos(Math.toIntExact(b.minX()),Math.toIntExact(b.minY()),Math.toIntExact(b.minZ()));
            int side=Math.toIntExact(b.maxX()-b.minX());
            var state=page.palette().state(cell.value().paletteIndex());
            var tile=materials.get(state);
            // No global palette truncation: cold materials omitted from this bounded reservoir can be revisited on a new spatial pass.
            if(tile==null){tile=SourceMeshBuilder.stored(state,at);if(materials.size()<TerrainMeshFrame.MAX_MATERIALS)materials.put(state,tile);}
            var node=new SourceMeshBuilder.Node(at,side,tile,owner);double d=distance.applyAsDouble(node);
            if(d>(double)DiscoveryBudget.RENDER_DISTANCE*DiscoveryBudget.RENDER_DISTANCE)continue;
            var key=new SourceMeshBuilder.Key(at.getX(),at.getY(),at.getZ(),side);
            if(nodes.containsKey(key))continue;
            nodes.put(key,node);changed=true;
        }
        // At most 32 transient additions per advance. Keep body detail AND representatives
        // across the discovered source footprint; far persisted leaves must reach publication.
        if(nodes.size()>MAX_NODES) {
            var retained=MeshPublication.coverageNodes(nodes.values(),MAX_NODES,focus,distance);
            nodes.clear();for(var n:retained)nodes.put(new SourceMeshBuilder.Key(n.position().getX(),n.position().getY(),n.position().getZ(),n.side()),n);
        }
        if(cells.complete()){cells=null;page=null;}
    }
    void endPass(){if(!far){far=true;pageIndex=0;}else complete=true;}
    List<SourceMeshBuilder.Node> snapshot(ToDoubleFunction<SourceMeshBuilder.Node> distance) {
        var result=new ArrayList<>(nodes.values());result.sort(Comparator.comparingDouble(distance).thenComparingLong(n->n.position().asLong()).thenComparingInt(SourceMeshBuilder.Node::side));
        changed=false;return List.copyOf(result);
    }
}
