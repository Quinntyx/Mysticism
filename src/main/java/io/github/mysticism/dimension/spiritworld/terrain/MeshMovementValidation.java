package io.github.mysticism.dimension.spiritworld.terrain;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Lag-compensated mesh validation for accepted deep-flight prediction. Deep flight runs through
 * per-player rotating observer-local geometry whose authoritative frame necessarily lags the
 * client's prediction, so a claimed move can diverge from the CURRENT server frame while still
 * being exactly what the player's own (slightly older, server-published) frame produced. The
 * server therefore retains a bounded history of its recently published frames and accepts a
 * claimed move when the player's own recent frame reproduces it; a move that crosses mesh walls
 * under EVERY recent frame is rejected and vanilla teleports the mover back. No frame is ever
 * invented: an empty history simply has no surfaces to cross. */
public final class MeshMovementValidation {
    /** Published every 2 ticks; covers typical latency several times over before eviction. */
    public static final int HISTORY = 16;
    /** Vanilla moved-wrongly distance scale; matched-frame re-simulation is normally exact. */
    static final double PREDICTION_TOLERANCE = 0.25;
    private static final Map<UUID,ArrayDeque<Entry>> FRAMES = new ConcurrentHashMap<>();
    private MeshMovementValidation() {}
    private static final class Entry {
        final TerrainMeshFrame frame;private volatile MeshCollision.Index index;
        Entry(TerrainMeshFrame frame){this.frame=frame;}
        MeshCollision.Index index(){MeshCollision.Index i=index;if(i==null){i=new MeshCollision.Index(frame);index=i;}return i;}
    }
    /** Records one accepted published frame; identical consecutive frames are not duplicated. */
    public static void record(UUID player,TerrainMeshFrame frame) {
        if(player==null || frame==null)return;
        var frames=FRAMES.computeIfAbsent(player,id->new ArrayDeque<>());
        synchronized(frames) {
            if(!frames.isEmpty() && frames.peekLast().frame==frame)return;
            frames.addLast(new Entry(frame));
            while(frames.size()>HISTORY)frames.removeFirst();
        }
    }
    public static void clear(UUID player){if(player!=null)FRAMES.remove(player);}
    /** Test/verification visibility only. */
    public static int historySize(UUID player){var frames=FRAMES.get(player);return frames==null?0:frames.size();}
    /** True when the claimed feet position is reachable from the pre-move body under the player's
     *  own recent mesh frame (prediction divergence tolerated), false when every recent frame
     *  clips the move (mesh wall crossing). */
    public static boolean allowsMeshMove(UUID player,Box preMove,Vec3d claimedFeet,boolean flying) {
        var frames=FRAMES.get(player);
        if(frames==null || frames.isEmpty())return true; // no published geometry yet: nothing to cross
        Vec3d start=new Vec3d((preMove.minX+preMove.maxX)/2,preMove.minY,(preMove.minZ+preMove.maxZ)/2);
        Vec3d wanted=claimedFeet.subtract(start);
        synchronized(frames) {
            var iterator=frames.descendingIterator(); // newest first: the client's own frame usually matches immediately
            while(iterator.hasNext()) {
                Vec3d result=MeshCollision.predicted(iterator.next().index(),preMove,wanted,flying,false,0);
                if(result.subtract(wanted).lengthSquared()<=PREDICTION_TOLERANCE*PREDICTION_TOLERANCE)return true;
            }
        }
        return false;
    }
}
