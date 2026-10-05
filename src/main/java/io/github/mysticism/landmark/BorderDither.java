package io.github.mysticism.landmark;

/** Pure border/material decision keyed only by absolute block coordinates, stable feature salt
 * and world seed. Adjacent chunks/frames must pass identical coordinates and blend weights.
 */
public final class BorderDither {
    private BorderDither() {}
    private static long mix(long x) { x=(x^(x>>>30))*0xbf58476d1ce4e5b9L; x=(x^(x>>>27))*0x94d049bb133111ebL; return x^(x>>>31); }
    public static double sample(long worldSeed,long featureSalt,long x,long y,long z) {
        long h=mix(worldSeed)^mix(featureSalt+0x632be59bd9b4e019L);
        h=mix(h^mix(x)); h=mix(h^mix(y+0x9e3779b97f4a7c15L)); h=mix(h^mix(z+0x4f1bbcdc6762c5d9L));
        return (h>>>11)*0x1.0p-53;
    }
    public static double smoothstep(double t) {
        if(!Double.isFinite(t)) throw new IllegalArgumentException("blend weight");
        t=Math.max(0,Math.min(1,t)); return t*t*(3-2*t);
    }
    public static boolean chooseIncoming(long worldSeed,long featureSalt,long x,long y,long z,double incomingWeight) {
        return sample(worldSeed,featureSalt,x,y,z)<smoothstep(incomingWeight);
    }
}
