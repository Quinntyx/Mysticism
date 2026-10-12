package io.github.mysticism.dimension.spiritworld.terrain;

/** Single-writer revision gate between network transport and the observer. Incoming frames land
 * in pending; they are promoted to committed only once their display upload has succeeded, so
 * displayed terrain, fog medium, occlusion and predicted collision always describe the SAME
 * geometry revision. A frame that cannot be displayed never displaces the coherent prior one. */
public final class TerrainRevisionGate {
    private TerrainMeshFrame committed,pending;
    /** Returns false for frames older than or equal to anything already held. */
    public boolean accept(TerrainMeshFrame incoming) {
        if(incoming==null)return false;
        if(pending!=null && incoming.revision()<=pending.revision())return false;
        if(committed!=null && incoming.revision()<=committed.revision())return false;
        pending=incoming;return true;
    }
    public TerrainMeshFrame committed(){return committed;}
    public TerrainMeshFrame pending(){return pending;}
    /** Display upload finished for the pending frame: promote it atomically for collision/fog/occlusion. */
    public boolean commit(TerrainMeshFrame uploaded){
        if(uploaded==null)return false;
        if(pending!=null){if(uploaded!=pending)return false;committed=pending;pending=null;return true;}
        return uploaded==committed; // resource reload of the same immutable revision
    }
    /** True while a shallow-mode flip is awaiting display commit (client collision cache reset due then). */
    public boolean shallowFlipPending(){return pending!=null && committed!=null && pending.shallow()!=committed.shallow();}
    public void clear(){committed=null;pending=null;}
}
