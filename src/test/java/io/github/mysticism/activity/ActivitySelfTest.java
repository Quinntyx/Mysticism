package io.github.mysticism.activity;

import io.github.mysticism.vector.*;
import java.util.*;

public final class ActivitySelfTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Vec384f axis(int n){float[] data=new float[EmbeddingSpace.DIMENSIONS];data[n]=1;return new Vec384f(data);}
    public static void main(String[] args){
        Vec384f x=axis(0),y=axis(1);ActivityMath.Window window=new ActivityMath.Window();
        for(int i=0;i<10000;i++)window.add("item test:"+i,2);
        check(window.size()==64,"window bound");var top=window.take();check(top.size()==8,"request budget");check(window.take().isEmpty(),"no cumulative replay");
        window.add("stone",10000);window.add("stone",10000);check(window.take().get("stone")==64,"saturating weights");
        window.add("invalid",Double.NaN);check(window.size()==0,"finite weights");
        for(int i=0;i<10000;i++){
            x=ActivityMath.drift(x,y,0.025);check(Math.abs(x.length()-1)<1e-5,"normalized drift");
        }
        check(x.squareDistance(y)<1e-6,"dwell converges to personal attractor");
        Vec384f copy=ActivityMath.drift(x,y,0);copy.mul(0);check(x.length()>0.99,"copy isolation");
        check(ActivityMath.relevance(24*24,24)==0,"strict radius");check(ActivityMath.relevance(25*25,24)==0,"outside radius");
        check(ActivityMath.relevance(0,24)==1,"inside radius");
        Vec384f opposing=ActivityMath.drift(y,y.clone().mul(-1),0.5);check(opposing.squareDistance(y)==0,"opposing drift continuity");
        Vec384f weighted=ActivityMath.weighted(List.of(axis(0),axis(1)),List.of(1.0,3.0));check(weighted.data()[1]>2.9*weighted.data()[0],"weighted item semantics");
        Basis384f basis=new Basis384f();Vec384f landmark=axis(4);
        Vec384f jump=TraversalSteering.supported(landmark,basis,0,1,0,0,1,0);
        check(jump.squareDistance(landmark)==0,"normal jump never moves semantic floor");
        Vec384f tangent=TraversalSteering.supported(landmark,basis,10000,0,0,0,1,0);
        check(tangent.squareDistance(landmark)<0.001,"orbit displacement cap");
        check(tangent.squareDistance(landmark)>0,"tangent walk steers");check(landmark.data()[0]==0,"support snapshot immutable");
        try{ActivityMath.drift(landmark,new Vec384f(landmark.data(),"alien"),0.1);throw new AssertionError("profile accepted");}catch(IllegalArgumentException expected){checks++;}
        check(TraversalSteering.supported(landmark,basis,0,0,0,0,1,0).squareDistance(landmark)==0,"idle stable");
        System.out.println("ActivitySelfTest: "+checks+" checks passed");
    }
}
