package io.github.mysticism.landmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Persistent sparse source-space octree. Null is UNKNOWN, not air. Values must be immutable.
 * Coarse uniform cells refine on partial writes and coalesce when all children agree.
 * Resolution and maximum root side are powers of two, in blocks. Updates are atomic/budgeted.
 */
public final class SparseOctree<T> {
    public record Cell<T>(Bounds bounds, T value) {}
    private record Node<T>(T value, List<Node<T>> children) { boolean leaf() { return children.isEmpty(); } }
    private final Bounds root;
    private final int resolution;
    private final long maxSide;
    private final Node<T> node;
    private SparseOctree(Bounds root, int resolution, long maxSide, Node<T> node) {
        this.root=root; this.resolution=resolution; this.maxSide=maxSide; this.node=node;
    }
    public static <T> SparseOctree<T> empty(Bounds root, int resolution, long maxSide) {
        long side=root.maxX()-root.minX();
        if (!powerOfTwo(resolution) || !powerOfTwo(side) || !powerOfTwo(maxSide) || side<resolution || side>maxSide
                || root.maxY()-root.minY()!=side || root.maxZ()-root.minZ()!=side || maxSide>(1L<<32))
            throw new IllegalArgumentException("octree size/resolution");
        aligned(root,resolution);
        return new SparseOctree<>(root,resolution,maxSide,null);
    }
    private static boolean powerOfTwo(long n) { return n>0 && (n&(n-1))==0; }
    private static void aligned(Bounds b, int r) {
        if (Math.floorMod(b.minX(),r)!=0 || Math.floorMod(b.minY(),r)!=0 || Math.floorMod(b.minZ(),r)!=0
                || Math.floorMod(b.maxX(),r)!=0 || Math.floorMod(b.maxY(),r)!=0 || Math.floorMod(b.maxZ(),r)!=0)
            throw new IllegalArgumentException("unaligned resolution");
    }
    @Override public boolean equals(Object other) {
        return other instanceof SparseOctree<?> tree && resolution==tree.resolution && maxSide==tree.maxSide
                && root.equals(tree.root) && Objects.equals(node,tree.node);
    }
    @Override public int hashCode() { return Objects.hash(root,resolution,maxSide,node); }
    public Bounds rootBounds() { return root; }
    public int resolution() { return resolution; }
    public long maxSide() { return maxSide; }
    public T sample(long x, long y, long z) {
        if (!root.contains(x,y,z)) return null;
        Node<T> n=node; Bounds b=root;
        while (n!=null && !n.leaf()) {
            int i=childIndex(b,x,y,z); n=n.children.get(i); b=child(b,i);
        }
        return n==null?null:n.value;
    }
    public SparseOctree<T> with(Bounds region, T value, int nodeBudget) {
        aligned(region,resolution); if (nodeBudget<=0) throw new IllegalArgumentException("budget");
        Bounds b=root; Node<T> n=node; int[] budget={nodeBudget};
        while (!b.contains(region)) {
            if(--budget[0]<0) throw new IllegalArgumentException("octree expansion budget exceeded");
            long side=b.maxX()-b.minX(); if (side>=maxSide) throw new IllegalArgumentException("root expansion limit");
            long x=region.minX()<b.minX()?Math.subtractExact(b.minX(),side):b.minX();
            long y=region.minY()<b.minY()?Math.subtractExact(b.minY(),side):b.minY();
            long z=region.minZ()<b.minZ()?Math.subtractExact(b.minZ(),side):b.minZ();
            Bounds bigger=Bounds.cube(x,y,z,side*2);
            if (n!=null) {
                List<Node<T>> kids=new ArrayList<>(Collections.nCopies(8,null));
                kids.set(childIndex(bigger,b.minX(),b.minY(),b.minZ()),n);
                n=new Node<>(null,Collections.unmodifiableList(kids));
            }
            b=bigger;
        }
        return new SparseOctree<>(b,resolution,maxSide,assign(n,b,region,value,budget));
    }
    private Node<T> assign(Node<T> n, Bounds b, Bounds region, T value, int[] budget) {
        if (!b.intersects(region)) return n;
        if (--budget[0]<0) throw new IllegalArgumentException("octree update budget exceeded");
        if (region.contains(b)) return value==null?null:new Node<>(value,List.of());
        if (b.maxX()-b.minX()<=resolution) throw new IllegalArgumentException("partial minimum cell");
        List<Node<T>> kids=new ArrayList<>(8);
        for(int i=0;i<8;i++) {
            Node<T> previous=n==null?null:n.leaf()?n:n.children.get(i);
            kids.add(assign(previous,child(b,i),region,value,budget));
        }
        Node<T> first=kids.getFirst(); boolean equal=true;
        for(Node<T> k:kids) if ((k!=null && !k.leaf()) || (first!=null && !first.leaf()) || !Objects.equals(k,first)) { equal=false; break; }
        return equal?first:new Node<>(null,Collections.unmodifiableList(kids));
    }
    private static int childIndex(Bounds b,long x,long y,long z) {
        long h=(b.maxX()-b.minX())/2;
        return (x>=b.minX()+h?1:0)|(y>=b.minY()+h?2:0)|(z>=b.minZ()+h?4:0);
    }
    private static Bounds child(Bounds b,int i) {
        long h=(b.maxX()-b.minX())/2;
        return Bounds.cube(b.minX()+((i&1)!=0?h:0),b.minY()+((i&2)!=0?h:0),b.minZ()+((i&4)!=0?h:0),h);
    }
    /** Returns known leaves intersecting the source bounds; rejects over-budget queries, never truncates geometry. */
    public List<Cell<T>> query(Bounds range,int maxCells) {
        if (maxCells<0) throw new IllegalArgumentException("cell budget");
        List<Cell<T>> cells=new ArrayList<>(); collect(node,root,range,maxCells,cells); return List.copyOf(cells);
    }
    public List<Cell<T>> cells(int maxCells) { return query(root,maxCells); }
    private void collect(Node<T> n,Bounds b,Bounds range,int max,List<Cell<T>> out) {
        if (n==null || !b.intersects(range)) return;
        if(n.leaf()) {
            if(out.size()>=max) throw new IllegalArgumentException("octree query budget exceeded");
            out.add(new Cell<>(b,n.value));
        } else for(int i=0;i<8;i++) collect(n.children.get(i),child(b,i),range,max,out);
    }
}
