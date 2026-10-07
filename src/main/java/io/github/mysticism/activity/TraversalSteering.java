package io.github.mysticism.activity;

import io.github.mysticism.vector.*;

/** Bounded semantic orbit, not a velocity/teleport controller. Collision is always vanilla terrain. */
public final class TraversalSteering {
    private TraversalSteering(){}
    public static Vec384f supported(Vec384f landmark,Basis384f basis,double dx,double dy,double dz,double nx,double ny,double nz){
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(!Double.isFinite(length)||length<1e-6)return landmark.clone();
        nx/=length;ny/=length;nz/=length;
        double dot=dx*nx+dy*ny+dz*nz;
        dx-=dot*nx;dy-=dot*ny;dz-=dot*nz;
        double travel=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!Double.isFinite(travel)||travel==0)return landmark.clone();
        double scale=Math.min(0.03,travel*0.004)/travel;
        Vec384f orbit=landmark.clone().add(basis.i.clone().mul((float)(dx*scale)))
                .add(basis.j.clone().mul((float)(dy*scale))).add(basis.k.clone().mul((float)(dz*scale)));
        return orbit.length()==0?landmark.clone():new Vec384f(orbit.norm());
    }
}
