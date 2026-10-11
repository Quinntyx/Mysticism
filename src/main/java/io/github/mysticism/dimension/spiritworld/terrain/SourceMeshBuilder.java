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
    /** Mesh batches may be exact rectangular unions; authoritative source ownership remains octree-native. */
    record Node(BlockPos position,int side,Tile tile,String ownerId,Vec3d size) {
        Node(BlockPos position,int side,Tile tile,String ownerId){this(position,side,tile,ownerId,new Vec3d(side,side,side));}
        Node(BlockPos position,int side,Tile tile){this(position,side,tile,"");}
        Node(BlockPos position,Vec3d size,Tile tile,String ownerId){this(position,(int)Math.min(size.x,Math.min(size.y,size.z)),tile,ownerId,size);}
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
    static List<Node> compact(Map<BlockPos,Tile> source,Vec3d fineCenter) {return compact(source,fineCenter,Map.of(),false);}
    static List<Node> compact(Map<BlockPos,Tile> source,Vec3d fineCenter,Map<BlockPos,String> exactOwners) {return compact(source,fineCenter,exactOwners,false);}
    /** mergeUnowned merges uniform tiles that have no exact owner yet (render-distance discovery
     * far field). Near-field compaction keeps strict per-owner provenance and never merges these. */
    static List<Node> compact(Map<BlockPos,Tile> source,Vec3d fineCenter,Map<BlockPos,String> exactOwners,boolean mergeUnowned) {
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
                    if(t==null || !t.cube || id.isEmpty() && !mergeUnowned || owner!=null && !owner.equals(id) || common!=null && !t.equals(common)){equal=false;break;}
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
    /** Replace only an actually sampled source patch; retain the producing region's far surfaces. */
    static List<Node> replaceNear(List<Node> base,List<Node> near,Bounds cut,Vec3d center) {
        List<Node> result=new ArrayList<>(near);for(Node node:base)outside(node,cut,result);
        result.sort(Comparator.comparingDouble(n->distanceSquared(nodeBox(n),center)));return List.copyOf(result);
    }
    static Box nodeBox(Node n){BlockPos p=n.position();return new Box(p.getX(),p.getY(),p.getZ(),p.getX()+n.size().x,p.getY()+n.size().y,p.getZ()+n.size().z);}
    private static Bounds nodeBounds(Node n){BlockPos p=n.position();return new Bounds(p.getX(),p.getY(),p.getZ(),p.getX()+(long)n.size().x,p.getY()+(long)n.size().y,p.getZ()+(long)n.size().z);}
    private static void piece(Node n,long x,long y,long z,long xx,long yy,long zz,List<Node> result) {
        if(xx>x && yy>y && zz>z)result.add(new Node(new BlockPos((int)x,(int)y,(int)z),new Vec3d(xx-x,yy-y,zz-z),n.tile(),n.ownerId()));
    }
    private static void outside(Node n,Bounds cut,List<Node> result) {
        Bounds b=nodeBounds(n);
        if(!b.intersects(cut)){result.add(n);return;}if(cut.contains(b))return;
        if(n.size().x==n.size().y && n.size().y==n.size().z && Integer.bitCount((int)n.size().x)==1) {
            int half=(int)n.size().x/2;
            for(int child=0;child<8;child++)outside(new Node(n.position().add((child&1)==0?0:half,(child&2)==0?0:half,(child&4)==0?0:half),half,n.tile(),n.ownerId()),cut,result);
            return; // Preserve the existing power-of-two octree refinement path.
        }
        // Exact rectangular subtraction, never fill a hole or extend beyond observed source cells.
        long x=Math.max(b.minX(),cut.minX()),y=Math.max(b.minY(),cut.minY()),z=Math.max(b.minZ(),cut.minZ());
        long xx=Math.min(b.maxX(),cut.maxX()),yy=Math.min(b.maxY(),cut.maxY()),zz=Math.min(b.maxZ(),cut.maxZ());
        piece(n,b.minX(),b.minY(),b.minZ(),x,b.maxY(),b.maxZ(),result);
        piece(n,xx,b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),result);
        piece(n,x,b.minY(),b.minZ(),xx,y,b.maxZ(),result);
        piece(n,x,yy,b.minZ(),xx,b.maxY(),b.maxZ(),result);
        piece(n,x,y,b.minZ(),xx,yy,z,result);
        piece(n,x,y,zz,xx,yy,b.maxZ(),result);
    }
    /** Refine a persisted coarse leaf only inside the bounded exact-mask window, preserving far geometry. */
    static List<Node> splitOwnership(List<Node> source,Map<BlockPos,String> owners,Bounds range,Vec3d center) {
        List<Node> result=new ArrayList<>();for(Node node:source)splitOwner(node,owners,range,center,result);
        result.sort(Comparator.comparingDouble(n->distanceSquared(nodeBox(n),center)));
        return List.copyOf(result);
    }
    private static void splitOwner(Node node,Map<BlockPos,String> owners,Bounds range,Vec3d center,List<Node> result) {
        BlockPos p=node.position();Bounds box=nodeBounds(node);
        if(!box.intersects(range)){result.add(node);return;}
        String owner=null;boolean uniform=range.contains(box);
        if(uniform)for(BlockPos cell:BlockPos.iterate(p,new BlockPos((int)box.maxX()-1,(int)box.maxY()-1,(int)box.maxZ()-1))) {
            String id=owners.getOrDefault(cell,"");
            if(id.isEmpty() || owner!=null && !owner.equals(id)){uniform=false;break;}owner=id;
        }
        boolean unit=node.size().equals(new Vec3d(1,1,1));
        if(!unit && distanceSquared(nodeBox(node),center)<49)uniform=false;
        if(unit || uniform){result.add(new Node(p,node.size(),node.tile(),owners.getOrDefault(p,"")));return;}
        // Bisect each non-unit dimension. Cubic inputs still split as an octree; mesh prisms
        // refine exactly at ownership boundaries without attributing an entire slab to one owner.
        int sx=(int)node.size().x,sy=(int)node.size().y,sz=(int)node.size().z;
        int hx=Math.max(1,sx/2),hy=Math.max(1,sy/2),hz=Math.max(1,sz/2);
        for(int x=0;x<sx;x+=hx)for(int y=0;y<sy;y+=hy)for(int z=0;z<sz;z+=hz)
            splitOwner(new Node(p.add(x,y,z),new Vec3d(Math.min(hx,sx-x),Math.min(hy,sy-y),Math.min(hz,sz-z)),node.tile(),node.ownerId()),owners,range,center,result);
    }
    static long key(String owner,Vec3d source,Vec3d size) {
        long h=key(owner,source,(int)size.x);
        if(size.x==size.y && size.y==size.z)return h; // Existing cubic keys stay stable.
        h=(h^Double.doubleToLongBits(size.y))*0x100000001b3L;
        return (h^Double.doubleToLongBits(size.z))*0x100000001b3L;
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
