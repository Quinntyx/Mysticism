package io.github.mysticism.client.util;

public class Color {
    public static int packRGBA(float r, float g, float b, float a) {
        int ri = (int)(r * 255f);
        int gi = (int)(g * 255f);
        int bi = (int)(b * 255f);
        int ai = (int)(a * 255f);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }
}
