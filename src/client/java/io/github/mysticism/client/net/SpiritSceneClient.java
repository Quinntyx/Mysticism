package io.github.mysticism.client.net;
import io.github.mysticism.net.*;
import java.util.*;
/** Client-thread immutable authenticated observer snapshots; no geometry anchor or world lookup. */
public final class SpiritSceneClient {
    private static SpiritScenePayload scene;private static SpiritFramePayload.Session lastAccepted;
    private SpiritSceneClient(){}
    public static Optional<SpiritScenePayload> snapshot(){return Optional.ofNullable(scene);}
    public static Optional<SpiritFramePayload.Session> session(){return scene==null?Optional.empty():Optional.of(scene.session());}
    static boolean accept(SpiritScenePayload next){
        if(lastAccepted!=null){var old=lastAccepted;var s=next.session();if(!old.connection().equals(s.connection())||s.generation()<old.generation())return false;if(s.generation()==old.generation()&&(scene==null||!old.equals(s)||next.sequence()<=scene.sequence()))return false;}
        if(next.session().frameEpoch()!=next.session().generation())return false;scene=next;lastAccepted=next.session();return true;
    }
    public static void clear(){scene=null;} // Preserve entry high-water mark across world/player replacement.
    static void resetConnection(){scene=null;lastAccepted=null;}
}
