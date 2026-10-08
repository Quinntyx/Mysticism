package io.github.mysticism.dimension.spiritworld.terrain;

import io.github.mysticism.landmark.BlockPalette;
import io.github.mysticism.landmark.Bounds;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.BlockView;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.world.LightType;
import java.util.*;

/** Bounded source-grid adapter and uniform octree compactor. Never writes or generates source blocks. */
final class SourceMeshBuilder {
    static final Box UNIT=new Box(0,0,0,1,1,1);
    record Tile(TerrainMeshFrame.Material material,List<Box> collision,int color,int light,boolean air,boolean cube) {}
    record Node(BlockPos position,int side,Tile tile,String ownerId) {
        Node(BlockPos position,int side,Tile tile){this(position,side,tile,"");}
    }
    record Key(int x,int y,int z,int side) {}
    static Tile read(ServerWorld world,BlockPos position) {
        if(!world.isChunkLoaded(position))return null;
        BlockState state=world.getBlockState(position);
        int color=0xffffff;
        var biome=world.getBiome(position).value();
        if(state.getBlock() instanceof LeavesBlock)color=biome.getFoliageColor();
        else if(state.getBlock() instanceof GrassBlock || state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN) || state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS))color=biome.getGrassColorAt(position.getX(),position.getZ());
        int light=(world.getLightLevel(LightType.BLOCK,position)<<4)|(world.getLightLevel(LightType.SKY,position)<<20);
        return tile(state,world,position,color,light);
    }
    static Tile stored(BlockPalette.State material,BlockPos position) {
        return tile(resolve(new TerrainMeshFrame.Material(material.blockId(),material.properties())),EmptyBlockView.INSTANCE,position,0xffffff,0xf000f0);
    }
    static Tile stored(BlockPalette.State material,BlockPos position,Map<BlockPos,Tile> neighbors) {
        BlockState current=resolve(new TerrainMeshFrame.Material(material.blockId(),material.properties()));
        BlockView context=new BlockView() {
            public BlockEntity getBlockEntity(BlockPos p){return null;}
            public BlockState getBlockState(BlockPos p){
                if(p.equals(position))return current;
                Tile t=neighbors.get(p);return t==null?Blocks.AIR.getDefaultState():resolve(t.material());
            }
            public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public int getHeight(){return 1024;}
            public int getBottomY(){return -512;}
        };
        return tile(current,context,position,0xffffff,0xf000f0);
    }
    static Tile tile(BlockState state,net.minecraft.world.BlockView world,BlockPos position,int color,int light) {
        var collision=state.getCollisionShape(world,position).getBoundingBoxes();
        if(collision.size()>TerrainMeshFrame.MAX_SHAPES)throw new IllegalArgumentException("source shape exceeds eight primitives");
        Map<String,String> properties=new TreeMap<>();state.getEntries().forEach((p,v)->properties.put(p.getName(),propertyName(p,v)));
        var material=new TerrainMeshFrame.Material(Registries.BLOCK.getId(state.getBlock()).toString(),properties);
        boolean cube=collision.size()==1 && collision.getFirst().equals(UNIT) && state.isOpaque();
        return new Tile(material,List.copyOf(collision),color,light,state.isAir(),cube);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String propertyName(Property property,Comparable value){return property.name(value);}
    static BlockState resolve(TerrainMeshFrame.Material material) {
        Identifier id=Identifier.of(material.blockId());
        if(!Registries.BLOCK.containsId(id))throw new IllegalArgumentException("unknown source material "+id);
        BlockState state=Registries.BLOCK.get(id).getDefaultState();
        for(var e:material.properties().entrySet()) {
            Property<?> p=state.getBlock().getStateManager().getProperty(e.getKey());
            if(p==null)throw new IllegalArgumentException("unknown source property "+e.getKey());
            state=apply(state,p,e.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState apply(BlockState state,Property<T> property,String value) {
        return state.with(property,property.parse(value).orElseThrow(()->new IllegalArgumentException("unknown source property value")));
    }
    static Bounds range(Vec3d origin,int side) {
        int x=MathHelper.floor(origin.x)-side/2,y=MathHelper.floor(origin.y)-side/2,z=MathHelper.floor(origin.z)-side/2;
        return new Bounds(x,y,z,(long)x+side,(long)y+side,(long)z+side);
    }
    static List<Node> compact(Map<BlockPos,Tile> source,Vec3d fineCenter) {return compact(source,fineCenter,Map.of());}
    static List<Node> compact(Map<BlockPos,Tile> source,Vec3d fineCenter,Map<BlockPos,String> exactOwners) {
        Map<Key,Tile> nodes=new HashMap<>();Map<Key,String> owners=new HashMap<>();
        source.forEach((p,t)->{if(!t.air){Key key=new Key(p.getX(),p.getY(),p.getZ(),1);nodes.put(key,t);owners.put(key,exactOwners.getOrDefault(p,""));}});
        for(int side=1;side<16;side*=2) {
            Set<Key> parents=new HashSet<>();
            for(Key k:nodes.keySet())if(k.side==side)parents.add(new Key(Math.floorDiv(k.x,side*2)*side*2,Math.floorDiv(k.y,side*2)*side*2,Math.floorDiv(k.z,side*2)*side*2,side*2));
            for(Key p:parents) {
                Box bounds=new Box(p.x,p.y,p.z,p.x+p.side,p.y+p.side,p.z+p.side);
                if(distanceSquared(bounds,fineCenter)<49)continue;
                Tile common=null;String owner=null;boolean equal=true;
                for(int child=0;child<8;child++) {
                    Key key=new Key(p.x+((child&1)==0?0:side),p.y+((child&2)==0?0:side),p.z+((child&4)==0?0:side),side);
                    Tile t=nodes.get(key);String id=owners.getOrDefault(key,"");
                    if(t==null || !t.cube || id.isEmpty() || owner!=null && !owner.equals(id) || common!=null && !t.equals(common)){equal=false;break;}
                    common=t;owner=id;
                }
                if(!equal)continue;
                for(int child=0;child<8;child++) {
                    Key key=new Key(p.x+((child&1)==0?0:side),p.y+((child&2)==0?0:side),p.z+((child&4)==0?0:side),side);
                    nodes.remove(key);owners.remove(key);
                }
                nodes.put(p,common);owners.put(p,owner);
            }
        }
        var result=new ArrayList<Node>();
        nodes.forEach((k,t)->result.add(new Node(new BlockPos(k.x,k.y,k.z),k.side,t,owners.getOrDefault(k,""))));
        result.sort(Comparator.comparingDouble((Node n)->distanceSquared(new Box(n.position).expand(n.side-1),fineCenter))
                .thenComparingLong(n->n.position.asLong()).thenComparingInt(Node::side));
        return result;
    }
    /** Replace only PROVEN sampled source cells; every unsampled cell keeps its persisted geometry.
     *  Unlike a bounding-box cut, gaps between disjoint sampled patches (and never-sampled areas inside
     *  the overall extent) retain their base nodes instead of being shattered and erased. */
    static List<Node> replaceNear(List<Node> base,List<Node> near,Set<BlockPos> sampled,Vec3d center) {
        Map<Long,Integer> buckets=new HashMap<>();
        for(BlockPos p:sampled)buckets.merge(bucket(p),1,Integer::sum);
        List<Node> result=new ArrayList<>(near);
        for(Node node:base)replace(node,sampled,buckets,result);
        result.sort(Comparator.comparingDouble(n->distanceSquared(nodeBox(n),center)));return List.copyOf(result);
    }
    /** 16-block sampled-volume buckets: nodes never straddle one (sides are powers of two <=16 dividing 16,
     *  aligned), so a zero bucket rejects in O(1) and large nodes aggregate a handful of bucket counts. */
    private static Box nodeBox(Node n){BlockPos p=n.position();return new Box(p.getX(),p.getY(),p.getZ(),(double)p.getX()+n.side(),(double)p.getY()+n.side(),(double)p.getZ()+n.side());}
    private static long bucket(BlockPos p) {
        return ((long)(p.getX()>>4)&0x3FFFFFL)<<42 | ((long)(p.getZ()>>4)&0x3FFFFFL)<<20 | ((long)(p.getY()>>4)&0xFFFFFL);
    }
    private static void replace(Node n,Set<BlockPos> sampled,Map<Long,Integer> buckets,List<Node> result) {
        BlockPos p=n.position();int side=n.side();long cells=(long)side*side*side;
        long sampledVolume=0;
        if(side<=16) {
            sampledVolume=buckets.getOrDefault(bucket(p),0);
            if(sampledVolume<=0){result.add(n);return;} // nothing sampled in this bucket: persisted geometry survives
            if(side==16 && sampledVolume>=cells)return; // node coincides with a fully sampled bucket
        } else {
            for(int bx=p.getX()>>4;bx<=(p.getX()+side-1)>>4;bx++)
                for(int by=p.getY()>>4;by<=(p.getY()+side-1)>>4;by++)
                    for(int bz=p.getZ()>>4;bz<=(p.getZ()+side-1)>>4;bz++)
                        sampledVolume+=buckets.getOrDefault(bucket(new BlockPos(bx<<4,by<<4,bz<<4)),0);
            if(sampledVolume<=0){result.add(n);return;} // nothing sampled under this node: survives
            int half=side/2; // some sampled volume: refine instead of scanning up to 64^3 cells
            for(int child=0;child<8;child++)replace(new Node(p.add((child&1)==0?0:half,(child&2)==0?0:half,(child&4)==0?0:half),half,n.tile(),n.ownerId()),sampled,buckets,result);
            return;
        }
        boolean any=false,all=true;
        for(BlockPos cell:BlockPos.iterate(p,p.add(side-1,side-1,side-1))) {
            if(sampled.contains(cell))any=true;else{all=false;if(any)break;}
        }
        if(!any){result.add(n);return;} // partial bucket without samples under this node: survives
        if(all)return;
        if(side==1)return; // sampled unit leaf: the exact near tile covers it
        int half=side/2;
        for(int child=0;child<8;child++)replace(new Node(p.add((child&1)==0?0:half,(child&2)==0?0:half,(child&4)==0?0:half),half,n.tile(),n.ownerId()),sampled,buckets,result);
    }
    /** Refine a persisted coarse leaf only inside the bounded exact-mask window, preserving far geometry. */
    static List<Node> splitOwnership(List<Node> source,Map<BlockPos,String> owners,Bounds range,Vec3d center) {
        List<Node> result=new ArrayList<>();for(Node node:source)splitOwner(node,owners,range,center,result);
        result.sort(Comparator.comparingDouble(n->distanceSquared(nodeBox(n),center)));
        return List.copyOf(result);
    }
    private static void splitOwner(Node node,Map<BlockPos,String> owners,Bounds range,Vec3d center,List<Node> result) {
        BlockPos p=node.position();int side=node.side();Bounds box=new Bounds(p.getX(),p.getY(),p.getZ(),(long)p.getX()+side,(long)p.getY()+side,(long)p.getZ()+side);
        if(!box.intersects(range)){result.add(node);return;}
        String owner=null;boolean uniform=range.contains(box);
        if(uniform)for(BlockPos cell:BlockPos.iterate(p,p.add(side-1,side-1,side-1))) {
            String id=owners.getOrDefault(cell,"");
            if(id.isEmpty() || owner!=null && !owner.equals(id)){uniform=false;break;}owner=id;
        }
        if(side>1 && distanceSquared(nodeBox(node),center)<49)uniform=false;
        if(side==1 || uniform){result.add(new Node(p,side,node.tile(),owners.getOrDefault(p,"")));return;}
        int half=side/2;
        for(int child=0;child<8;child++)splitOwner(new Node(p.add((child&1)==0?0:half,(child&2)==0?0:half,(child&4)==0?0:half),half,node.tile(),node.ownerId()),owners,range,center,result);
    }
    static long key(String owner,Vec3d source,int side) {
        long h=0xcbf29ce484222325L;
        for(int i=0;i<owner.length();i++)h=(h^owner.charAt(i))*0x100000001b3L;
        h=(h^Double.doubleToLongBits(source.x))*0x100000001b3L;
        h=(h^Double.doubleToLongBits(source.y))*0x100000001b3L;
        h=(h^Double.doubleToLongBits(source.z))*0x100000001b3L;
        h=(h^side)*0x100000001b3L;
        h^=h>>>30;h*=0xbf58476d1ce4e5b9L;h^=h>>>27;h*=0x94d049bb133111ebL;return h^(h>>>31);
    }
    static double distanceSquared(Box b,Vec3d p) {
        double x=Math.max(Math.max(b.minX-p.x,0),p.x-b.maxX),y=Math.max(Math.max(b.minY-p.y,0),p.y-b.maxY),z=Math.max(Math.max(b.minZ-p.z,0),p.z-b.maxZ);
        return x*x+y*y+z*z;
    }
}
