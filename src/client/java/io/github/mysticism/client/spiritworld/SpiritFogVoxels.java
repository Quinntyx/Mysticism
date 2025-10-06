package io.github.mysticism.client.spiritworld;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

@Environment(EnvType.CLIENT)
public final class SpiritFogVoxels {
    private static final Identifier SPIRIT_DIM = Identifier.of("mysticism", "spirit");

    // 1x1 white texture for translucent entity layer
    private static Identifier WHITE_TEX_ID;
    private static NativeImageBackedTexture WHITE_TEX;

    // ---- NEW: world/latent phase accumulators (no API changes) ----
    private static double CAM_X, CAM_Y, CAM_Z;
    private static boolean PHASE_INIT = false;
    private static float phaseX = 0f, phaseY = 0f, phaseZ = 0f;
    private static float prevOx = 0f, prevOy = 0f, prevOz = 0f;

    private SpiritFogVoxels() {}

    public static void init() {
        WorldRenderEvents.LAST.register(ctx -> {
            ensureWhiteTexture(); // safe + idempotent

            var mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.player == null) return;
            if (!mc.world.getRegistryKey().getValue().equals(SPIRIT_DIM)) return;

            MatrixStack matrices = ctx.matrixStack();
            VertexConsumerProvider consumers = ctx.consumers();
            Camera camera = ctx.camera();
            if (matrices == null || consumers == null || camera == null) return;

            // -------- Tunables (yours) --------
            final float scale         = 120.0f;   // noise world→latent scale (bigger => larger features)
            final float cellSize      = 32.0f;    // base cell extent (meters)
            final float maxRadius     = 512.0f;   // half-extent of top-level volume
            final float alphaPerBlock = 0.03f;    // base alpha for a 1m block (scaled by size)
            final float threshold     = 0.45f;    // density threshold
            final float detailScale   = 1.5f;     // LOD aggressiveness
            final float jitterFrac    = 0.0125f;  // per-box jitter fraction (anti z-fight)

            // NEW: how much latent motion warps the field (phase integrator gain)
            final float latentGain    = 0.02f;    // try 0.01–0.05

            Vec3d cam = camera.getPos();

            // ---- latent slice offsets (unchanged logic, reflection-safe) ----
            float ox = 0f, oy = 0f, oz = 0f;
            try {
                Object basis = io.github.mysticism.client.spiritworld.ClientSpiritCache.playerLatentBasis;
                Object you   = io.github.mysticism.client.spiritworld.ClientSpiritCache.playerLatentPos;

                Object iVec = null, jVec = null, kVec = null;
                try { iVec = basis.getClass().getField("i").get(basis); } catch (Throwable ignored) {}
                try { jVec = basis.getClass().getField("j").get(basis); } catch (Throwable ignored) {}
                try { kVec = basis.getClass().getField("k").get(basis); } catch (Throwable ignored) {}
                if (iVec == null) try { iVec = basis.getClass().getMethod("i").invoke(basis); } catch (Throwable ignored) {}
                if (jVec == null) try { jVec = basis.getClass().getMethod("j").invoke(basis); } catch (Throwable ignored) {}
                if (kVec == null) try { kVec = basis.getClass().getMethod("k").invoke(basis); } catch (Throwable ignored) {}

                if (iVec != null && jVec != null && kVec != null && you != null) {
                    var dot = iVec.getClass().getMethod("dot", you.getClass());
                    ox = ((Number) dot.invoke(iVec, you)).floatValue();
                    oy = ((Number) dot.invoke(jVec, you)).floatValue();
                    oz = ((Number) dot.invoke(kVec, you)).floatValue();
                    final float WRAP = 4096f;
                    ox = ox % WRAP; oy = oy % WRAP; oz = oz % WRAP;
                }
            } catch (Throwable ignored) {}

            // NEW: world anchor and latent phase integration
            CAM_X = cam.x; CAM_Y = cam.y; CAM_Z = cam.z;
            if (!PHASE_INIT) {
                prevOx = ox; prevOy = oy; prevOz = oz;
                PHASE_INIT = true;
            } else {
                float dOx = ox - prevOx;
                float dOy = oy - prevOy;
                float dOz = oz - prevOz;
                phaseX += latentGain * dOx;
                phaseY += latentGain * dOy;
                phaseZ += latentGain * dOz;
                prevOx = ox; prevOy = oy; prevOz = oz;
                // keep phases in a sane numeric range (mod a big-ish period)
                final float PHASE_WRAP = 8192f / scale; // wraps about every ~8192 world units of noise-space
                phaseX = wrapPhase(phaseX, PHASE_WRAP);
                phaseY = wrapPhase(phaseY, PHASE_WRAP);
                phaseZ = wrapPhase(phaseZ, PHASE_WRAP);
            }

            // NOTE: DO NOT translate by -cam; matrices are already camera-relative in this event
            matrices.push();

            RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(WHITE_TEX_ID);
            VertexConsumer vc = consumers.getBuffer(layer);
            MatrixStack.Entry e = matrices.peek();

            // Top-level grid centered around camera
            for (float cx = -maxRadius; cx < maxRadius; cx += cellSize) {
                for (float cy = -maxRadius; cy < maxRadius; cy += cellSize) {
                    for (float cz = -maxRadius; cz < maxRadius; cz += cellSize) {
                        // LOD: more depth near camera, less far away
                        float dist = MathHelper.sqrt(cx*cx + cy*cy + cz*cz);
                        int maxDepth = Math.min(
                                (int)Math.ceil((1.0f / Math.max(0.001f, dist)) * 128.0f * detailScale),
                                6 // cap recursion
                        );

                        // Construct the top box in camera-centered space
                        Box box = new Box(
                                cx - cellSize * 0.5f, cy - cellSize * 0.5f, cz - cellSize * 0.5f,
                                cx + cellSize * 0.5f, cy + cellSize * 0.5f, cz + cellSize * 0.5f
                        );

                        emitBoxRecursive(vc, e, box, alphaPerBlock, maxDepth, scale, ox, oy, oz, threshold, jitterFrac);
                    }
                }
            }

            matrices.pop();
        });
    }

    private static void emitBoxRecursive(
            VertexConsumer vc, MatrixStack.Entry e, Box box,
            float alphaPerBlock, int maxDepth, float scale, float ox, float oy, float oz,
            float threshold, float jitterFrac
    ) {
        if (maxDepth <= 0) return;

        // World-space corners for sampling (camera-centered geometry, so add CAM_* for world)
        final double x0 = box.minX + CAM_X, x1 = box.maxX + CAM_X;
        final double y0 = box.minY + CAM_Y, y1 = box.maxY + CAM_Y;
        final double z0 = box.minZ + CAM_Z, z1 = box.maxZ + CAM_Z;

        // --- sample density at 8 corners ---
        // Add: (corner_world / scale)  + integrated latent phase per axis
        float f000 = fbm((float)(x0/scale + phaseX), (float)(y0/scale + phaseY), (float)(z0/scale + phaseZ));
        float f001 = fbm((float)(x0/scale + phaseX), (float)(y0/scale + phaseY), (float)(z1/scale + phaseZ));
        float f010 = fbm((float)(x0/scale + phaseX), (float)(y1/scale + phaseY), (float)(z0/scale + phaseZ));
        float f011 = fbm((float)(x0/scale + phaseX), (float)(y1/scale + phaseY), (float)(z1/scale + phaseZ));
        float f100 = fbm((float)(x1/scale + phaseX), (float)(y0/scale + phaseY), (float)(z0/scale + phaseZ));
        float f101 = fbm((float)(x1/scale + phaseX), (float)(y0/scale + phaseY), (float)(z1/scale + phaseZ));
        float f110 = fbm((float)(x1/scale + phaseX), (float)(y1/scale + phaseY), (float)(z0/scale + phaseZ));
        float f111 = fbm((float)(x1/scale + phaseX), (float)(y1/scale + phaseY), (float)(z1/scale + phaseZ));

        float minF = min8(f000,f001,f010,f011,f100,f101,f110,f111);
        float maxF = max8(f000,f001,f010,f011,f100,f101,f110,f111);

        if (maxF < threshold) return; // coherent void
        if (minF >= threshold) {
            // emit once with size-scaled alpha
            float sizeX = (float)(x1 - x0);
            float sizeY = (float)(y1 - y0);
            float sizeZ = (float)(z1 - z0);
            float avgSide = (sizeX + sizeY + sizeZ) / 3f;
            float alpha = MathHelper.clamp(alphaPerBlock * avgSide, 0f, 1f);

            // jitter in CAMERA space so de-align neighbors visually; convert back by removing CAM_* shift
            float jx = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX)),
                    fastFloor((float)((y0+y1)*0.5/sizeY)),
                    fastFloor((float)((z0+z1)*0.5/sizeZ))) - 0.5f) * jitterFrac * sizeX;
            float jy = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX))*7+3,
                    fastFloor((float)((y0+y1)*0.5/sizeY))*11+5,
                    fastFloor((float)((z0+z1)*0.5/sizeZ))) - 0.5f) * jitterFrac * sizeY;
            float jz = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX)),
                    fastFloor((float)((y0+y1)*0.5/sizeY))*13+1,
                    fastFloor((float)((z0+z1)*0.5/sizeZ))*5) - 0.5f) * jitterFrac * sizeZ;

            // Build the box back in CAMERA space for rendering (subtract CAM_*)
            Box jb = new Box(
                    x0 - CAM_X + jx, y0 - CAM_Y + jy, z0 - CAM_Z + jz,
                    x1 - CAM_X + jx, y1 - CAM_Y + jy, z1 - CAM_Z + jz
            );
            int argb = packRGBA(1, 1, 1, alpha);
            emitBox(vc, e, jb, argb);
            return;
        }

        if (maxDepth <= 1) {
            // last level: render if majority inside
            int inside = (f000>=t(threshold)?1:0)+(f001>=t(threshold)?1:0)+(f010>=t(threshold)?1:0)+(f011>=t(threshold)?1:0)+
                    (f100>=t(threshold)?1:0)+(f101>=t(threshold)?1:0)+(f110>=t(threshold)?1:0)+(f111>=t(threshold)?1:0);
            if (inside >= 5) {
                float sizeX = (float)(x1 - x0);
                float sizeY = (float)(y1 - y0);
                float sizeZ = (float)(z1 - z0);
                float avgSide = (sizeX + sizeY + sizeZ) / 3f;
                float alpha = MathHelper.clamp(alphaPerBlock * avgSide, 0f, 1f);

                float jx = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX)),
                        fastFloor((float)((y0+y1)*0.5/sizeY)),
                        fastFloor((float)((z0+z1)*0.5/sizeZ))) - 0.5f) * jitterFrac * sizeX;
                float jy = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX))*7+3,
                        fastFloor((float)((y0+y1)*0.5/sizeY))*11+5,
                        fastFloor((float)((z0+z1)*0.5/sizeZ))) - 0.5f) * jitterFrac * sizeY;
                float jz = (hash3(fastFloor((float)((x0+x1)*0.5/sizeX)),
                        fastFloor((float)((y0+y1)*0.5/sizeY))*13+1,
                        fastFloor((float)((z0+z1)*0.5/sizeZ))*5) - 0.5f) * jitterFrac * sizeZ;

                Box jb = new Box(
                        x0 - CAM_X + jx, y0 - CAM_Y + jy, z0 - CAM_Z + jz,
                        x1 - CAM_X + jx, y1 - CAM_Y + jy, z1 - CAM_Z + jz
                );
                int argb = packRGBA(1, 1, 1, alpha);
                emitBox(vc, e, jb, argb);
            }
            return;
        }

        for (Box child : splitBoxOct(new Box(
                x0 - CAM_X, y0 - CAM_Y, z0 - CAM_Z,
                x1 - CAM_X, y1 - CAM_Y, z1 - CAM_Z
        ))) {
            emitBoxRecursive(vc, e, child, alphaPerBlock, maxDepth - 1, scale, ox, oy, oz, threshold, jitterFrac);
        }
    }

    // ---- Vertex emission helpers ----
    private static void emitBox(VertexConsumer vc, MatrixStack.Entry e, Box box, int argb) {
        float x0 = (float) box.maxX;
        float y0 = (float) box.maxY;
        float z0 = (float) box.maxZ;
        float x1 = (float) box.minX;
        float y1 = (float) box.minY;
        float z1 = (float) box.minZ;
        boolean billboardNormals = false;

        emit(vc, e, x1, y0, z0, argb, 0,0); emit(vc, e, x1, y1, z0, argb, 0,1);
        emit(vc, e, x1, y1, z1, argb, 1,1); emit(vc, e, x1, y0, z1, argb, 1,0);

        emitN(vc, e, x0, y0, z1, argb, 0,0, -1,0,0, billboardNormals);
        emitN(vc, e, x0, y1, z1, argb, 0,1, -1,0,0, billboardNormals);
        emitN(vc, e, x0, y1, z0, argb, 1,1, -1,0,0, billboardNormals);
        emitN(vc, e, x0, y0, z0, argb, 1,0, -1,0,0, billboardNormals);

        emitN(vc, e, x0, y1, z0, argb, 0,0, 0,1,0, billboardNormals);
        emitN(vc, e, x0, y1, z1, argb, 0,1, 0,1,0, billboardNormals);
        emitN(vc, e, x1, y1, z1, argb, 1,1, 0,1,0, billboardNormals);
        emitN(vc, e, x1, y1, z0, argb, 1,0, 0,1,0, billboardNormals);

        emitN(vc, e, x1, y0, z0, argb, 0,0, 0,-1,0, billboardNormals);
        emitN(vc, e, x1, y0, z1, argb, 0,1, 0,-1,0, billboardNormals);
        emitN(vc, e, x0, y0, z1, argb, 1,1, 0,-1,0, billboardNormals);
        emitN(vc, e, x0, y0, z0, argb, 1,0, 0,-1,0, billboardNormals);

        emitN(vc, e, x0, y0, z1, argb, 0,0, 0,0,1, billboardNormals);
        emitN(vc, e, x0, y1, z1, argb, 0,1, 0,0,1, billboardNormals);
        emitN(vc, e, x1, y1, z1, argb, 1,1, 0,0,1, billboardNormals);
        emitN(vc, e, x1, y0, z1, argb, 1,0, 0,0,1, billboardNormals);

        emitN(vc, e, x1, y0, z0, argb, 0,0, 0,0,-1, billboardNormals);
        emitN(vc, e, x1, y1, z0, argb, 0,1, 0,0,-1, billboardNormals);
        emitN(vc, e, x0, y1, z0, argb, 1,1, 0,0,-1, billboardNormals);
        emitN(vc, e, x0, y0, z0, argb, 1,0, 0,0,-1, billboardNormals);
    }

    private static void emit(VertexConsumer vc, MatrixStack.Entry e,
                             float x, float y, float z, int argb, float u, float v) {
        emitN(vc, e, x, y, z, argb, u, v, 0,0,1, false);
    }

    private static void emitN(VertexConsumer vc, MatrixStack.Entry e,
                              float x, float y, float z, int argb, float u, float v,
                              float nx, float ny, float nz, boolean billboard) {
        int lightUV = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        int overlayUV = OverlayTexture.DEFAULT_UV;
        vc.vertex(e.getPositionMatrix(), x, y, z);
        vc.color(argb);
        vc.texture(u, v);
        vc.overlay(overlayUV);
        vc.light(lightUV);
        if (billboard) {
            vc.normal(0f, 0f, 1f);
        } else {
            vc.normal(nx, ny, nz);
        }
    }

    private static int packRGBA(float r, float g, float b, float a) {
        int ri = (int)(r * 255f);
        int gi = (int)(g * 255f);
        int bi = (int)(b * 255f);
        int ai = (int)(a * 255f);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static void ensureWhiteTexture() {
        if (WHITE_TEX != null) return;
        var mc = MinecraftClient.getInstance();
        WHITE_TEX_ID = Identifier.of("mysticism", "textures/misc/white.png");
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        img.setColorArgb(0, 0, 0xFFFFFFFF);
        WHITE_TEX = new NativeImageBackedTexture(() -> "mysticism:fog", img);
        mc.getTextureManager().registerTexture(WHITE_TEX_ID, WHITE_TEX);
    }

    // ---- compact CPU fBm (yours) ----

    private static float fbm(float x, float y, float z) {
        float a = 0.5f, f = 0f;
        for (int i=0;i<5;i++){ f += a*noise3(x,y,z); x*=2.02f; y*=2.02f; z*=2.02f; a*=0.5f; }
        return f;
    }
    private static float noise3(float x, float y, float z) {
        int xi = fastFloor(x), yi = fastFloor(y), zi = fastFloor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = smooth(xf), v = smooth(yf), w = smooth(zf);
        float n000 = hash3(xi, yi, zi);
        float n100 = hash3(xi+1, yi, zi);
        float n010 = hash3(xi, yi+1, zi);
        float n110 = hash3(xi+1, yi+1, zi);
        float n001 = hash3(xi, yi, zi+1);
        float n101 = hash3(xi+1, yi, zi+1);
        float n011 = hash3(xi, yi+1, zi+1);
        float n111 = hash3(xi+1, yi+1, zi+1);
        float nx00 = lerp(n000, n100, u);
        float nx10 = lerp(n010, n110, u);
        float nx01 = lerp(n001, n101, u);
        float nx11 = lerp(n011, n111, u);
        float nxy0 = lerp(nx00, nx10, v);
        float nxy1 = lerp(nx01, nx11, v);
        return lerp(nxy0, nxy1, w);
    }
    private static int fastFloor(float n){ int i=(int)n; return n<i ? i-1 : i; }
    private static float smooth(float t){ return t*t*(3f-2f*t); }
    private static float lerp(float a,float b,float t){ return a + t*(b-a); }
    private static float hash3(int x,int y,int z){
        int h = x*374761393 + y*668265263 + z*2147483647;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7fffffff) / (float)0x7fffffff;
    }
    private static float min8(float a,float b,float c,float d,float e,float f,float g,float h){
        return Math.min(a,Math.min(b,Math.min(c,Math.min(d,Math.min(e,Math.min(f,Math.min(g,h)))))));
    }
    private static float max8(float a,float b,float c,float d,float e,float f,float g,float h){
        return Math.max(a,Math.max(b,Math.max(c,Math.max(d,Math.max(e,Math.max(f,Math.max(g,h)))))));
    }

    private static Box[] splitBoxOct(Box box) {
        final double x0 = box.minX, x1 = box.maxX;
        final double y0 = box.minY, y1 = box.maxY;
        final double z0 = box.minZ, z1 = box.maxZ;

        final double cx = (x0 + x1) * 0.5;
        final double cy = (y0 + y1) * 0.5;
        final double cz = (z0 + z1) * 0.5;

        return new Box[] {
                new Box(x0, y0, z0, cx, cy, cz),
                new Box(x0, y0, cz, cx, cy, z1),
                new Box(x0, cy, z0, cx, y1, cz),
                new Box(x0, cy, cz, cx, y1, z1),
                new Box(cx, y0, z0, x1, cy, cz),
                new Box(cx, y0, cz, x1, cy, z1),
                new Box(cx, cy, z0, x1, y1, cz),
                new Box(cx, cy, cz, x1, y1, z1)
        };
    }

    private static float wrapPhase(float p, float span) {
        // map to [-span, +span] without heavy modulo
        if (p >  span) p -= 2f*span;
        if (p < -span) p += 2f*span;
        return p;
    }

    private static float t(float threshold) { return threshold; }
}
