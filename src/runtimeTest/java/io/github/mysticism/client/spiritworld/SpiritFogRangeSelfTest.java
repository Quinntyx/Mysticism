package io.github.mysticism.client.spiritworld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Discovered by runtimeSelfTest. CPU integration and live-resource wiring, NOT GPU execution. */
public final class SpiritFogRangeSelfTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void near(double value, double expected, double tolerance, String message) {
        check(Math.abs(value - expected) <= tolerance, message + ": " + value + " != " + expected);
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid segment/range accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static double density(double x, double y, double z) {
        double f = 2 * Math.PI / SpiritRenderSettings.DENSITY_PERIOD;
        return 1 + .5 * (.5 + .5 * Math.sin(f * (83 * x + 29 * y)) * Math.sin(f * (61 * z - 17 * y)));
    }
    private static double march(SpiritRenderSettings.FogRange range, double length, int steps,
                                double camera, double constantDensity, double occupancy) {
        double end = Math.min(length, range.opaqueRadius());
        double step = Math.max(0, end - range.visibleRadius()) / steps;
        double transmission = 1, scatter = 0;
        for (int i = 0; i < steps && transmission > .001; i++) {
            double start = range.visibleRadius() + i * step;
            double sample = start + .5 * step;
            double medium = constantDensity == 0 ? density(camera + sample * .3, sample * -.2, sample * .5) : constantDensity;
            double segment = Math.exp(-range.opticalDepth(start, start + step) * (medium + .7 * occupancy));
            scatter += transmission * (1 - segment);
            transmission *= segment;
        }
        near(transmission + scatter, 1, 2e-14, "Beer-Lambert energy conservation");
        return transmission;
    }
    private static void rangesAndIntegration() {
        for (int chunks = 2; chunks <= 32; chunks++) {
            var range = SpiritRenderSettings.fogRange(chunks);
            near(range.opaqueRadius(), chunks * 16, 0, "opaque horizon follows render option");
            near(range.visibleRadius(), chunks * 8, 0, "half-radius clear foreground");
            check(range.containsDistanceSquared(range.opaqueRadius() * range.opaqueRadius()), "horizon mesh retained");
            check(!range.containsDistanceSquared(Math.pow(range.opaqueRadius() + .01, 2)), "far mesh culled");
            double previous = 1;
            for (int d = 0; d <= chunks * 16 + 32; d++) {
                double t = Math.exp(-range.opticalDepth(0, d));
                check(t <= previous && t >= 0, "continuous monotonic range extinction");
                if (d <= range.visibleRadius()) near(t, 1, 0, "clear foreground");
                previous = t;
            }
            for (var quality : SpiritRenderSettings.Quality.values()) {
                int steps = quality.fogSteps;
                check(steps >= 8 && steps <= 64, "bounded fog cost independent of render distance");
                near(march(range, 0, steps, 0, 1, 0), 1, 0, "zero-depth surface");
                near(march(range, range.visibleRadius(), steps, 0, 1.5, 1), 1, 0, "occupancy cannot hide foreground");
                double thin = march(range, range.opaqueRadius(), steps, 0, 1, 0);
                near(thin, SpiritRenderSettings.MAX_TRANSMITTANCE, 2e-16, "exact ramp integral in every tier");
                double useful = march(range, range.opaqueRadius() * .75, steps, 0, 1.5, 0);
                check(useful > .27, "distant terrain remains useful, not already near-opaque");
                double occupied = march(range, range.opaqueRadius() * .75, steps, 0, 1.5, 1);
                check(occupied > .14 && occupied < useful, "bounded occupancy strengthens fog without concealing locality");
                for (double camera : new double[]{-30_000_000, -4096, 0, 12345, 30_000_000}) {
                    double fog = march(range, range.opaqueRadius(), steps, camera, 0, 0);
                    check(fog <= .0010000001, "world-space density horizon bound");
                    near(march(range, range.opaqueRadius() + 100, steps, camera, 0, 0), fog, 0, "sky/far rays capped at horizon");
                }
            }
        }
        var eight = SpiritRenderSettings.fogRange(8);
        near(Math.exp(-eight.opticalDepth(0, 64)), 1, 0, "old 64-block locality is no longer hidden");
        check(eight.containsDistanceSquared(100 * 100), "published meshes beyond old 64-block cutoff are drawable");
        check(SpiritRenderSettings.fogRange(16).containsDistanceSquared(200 * 200), "wider render option immediately increases mesh range");
        check(!eight.containsDistanceSquared(200 * 200), "smaller option immediately decreases mesh range");
        near(SpiritRenderSettings.fogRange(Integer.MIN_VALUE).opaqueRadius(), 32, 0, "lower option guard");
        near(SpiritRenderSettings.fogRange(Integer.MAX_VALUE).opaqueRadius(), 512, 0, "upper option guard");
        check(!eight.containsDistanceSquared(-1) && !eight.containsDistanceSquared(Double.NaN)
                && !eight.containsDistanceSquared(Double.POSITIVE_INFINITY), "invalid cull distance fails closed");
        rejects(() -> new SpiritRenderSettings.FogRange(Double.NaN, 128));
        rejects(() -> new SpiritRenderSettings.FogRange(128, 128));
        rejects(() -> new SpiritRenderSettings.FogRange(-1, 128));
        rejects(() -> eight.opticalDepth(-1, 5));
        rejects(() -> eight.opticalDepth(5, 4));
        rejects(() -> eight.opticalDepth(0, Double.POSITIVE_INFINITY));
        // No opacity discontinuity at either end of the ramp.
        near(eight.opticalDepth(0, eight.visibleRadius() + .001), 0, 1e-12, "ramp starts smoothly");
        near(eight.opticalDepth(0, eight.opaqueRadius() + .001), SpiritRenderSettings.HORIZON_OPTICAL_DEPTH, 1e-14, "ramp ends at cap");
    }
    private static void shaderFloatIntegral() {
        // Reproduce GLSL float arithmetic separately from the production double CPU helper.
        for (int chunks : new int[]{2, 8, 12, 16, 32}) for (var q : SpiritRenderSettings.Quality.values()) {
            var range = SpiritRenderSettings.fogRange(chunks);
            for (int percent = 0; percent <= 100; percent++) {
                float span = (float)(range.opaqueRadius() - range.visibleRadius());
                float length = span * percent / 100f;
                float fraction = length / span, t = 1;
                for (int i = 0; i < q.fogSteps && t > .001f; i++) {
                    float a = fraction * i / q.fogSteps;
                    float b = fraction * (i + 1) / q.fogSteps;
                    float tau = (float)SpiritRenderSettings.HORIZON_OPTICAL_DEPTH * (b * b * b - a * a * a);
                    t *= (float)Math.exp(-tau);
                }
                double expected = Math.exp(-range.opticalDepth(range.visibleRadius(), range.visibleRadius() + length));
                near(t, expected, 2e-6, "GLSL float partial-ray integral");
                if (percent == 100) near(t, .001, 1e-8, "GLSL float integral at horizon");
            }
        }
    }
    private static String source(String path) throws Exception { return Files.readString(Path.of(path)); }
    private static double uniform(String descriptor, String name) {
        var matcher = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"" + name + "\\\"[^}]*\\\"values\\\"\\s*:\\s*\\[([^]]+)]").matcher(descriptor);
        if (!matcher.find()) throw new AssertionError("missing descriptor uniform " + name);
        return Double.parseDouble(matcher.group(1));
    }
    private static void liveWiring() throws Exception {
        String root = "src/client/java/io/github/mysticism/client/spiritworld/";
        String manager = source(root + "ShaderManager.java");
        String mesh = source(root + "terrain/SpiritTerrainClient.java");
        String shader = source("src/main/resources/assets/mysticism/shaders/program/spirit_fog.fsh");
        String descriptor = source("src/main/resources/assets/mysticism/shaders/program/spirit_fog.json");
        check(manager.contains("SpiritRenderSettings.fogRange(client.options.getViewDistance().getValue())"), "use configured client render distance, not source mesh extent/server clamp");
        check(manager.indexOf("fogRange = SpiritRenderSettings.fogRange(client.options") < manager.indexOf("if (sessionWorld == null"), "fallback frames refresh range before early exit");
        // Merge regression: retaining the new distance option must not resurrect the old
        // boolean readiness flags or bypass attachment-identity/depth-capture validation.
        int start = manager.indexOf("WorldRenderEvents.START.register");
        int earlyExit = manager.indexOf("if (sessionWorld == null", start);
        int invalidation = manager.indexOf("frame.invalidate();", start);
        check(invalidation > start && invalidation < earlyExit, "every world frame invalidates stale depth before fallback/entry exits");
        check(!manager.contains("frameReady") && !manager.contains("depthReady"), "fog range integration retains the production lifecycle gate");
        check(manager.contains("frame.prepare(surface(main))")
                && manager.contains("frame.captureDepth(surface(client.getFramebuffer()))")
                && manager.contains("frame.consume(surface(main))"), "wider fog only runs with same-surface captured depth");
        check(manager.contains("if (frame.ready() && !failed && effectStrength()>=.999f)"), "vanilla fog suppression uses lifecycle readiness");
        check(mesh.contains("ShaderManager.fogRange().containsDistanceSquared(distanceSquared(b.bounds,camera))"), "real mesh renderer shares fog range");
        check(!mesh.contains(">64*64"), "no old mesh-locality cutoff");
        for (String name : new String[]{"VisibleRadius", "OpaqueRadius", "HorizonOpticalDepth"}) {
            check(manager.contains("setUniformValue(\"" + name + "\""), "live uniform setter " + name);
            check(shader.contains("uniform float " + name + ";"), "GLSL uniform " + name);
        }
        check(manager.contains("(float)fogRange.visibleRadius()*s") && manager.contains("(float)fogRange.opaqueRadius()*s"), "linear failure fallback shares wider range");
        check(shader.contains("depth >= 0.9999999 ? OpaqueRadius : min(distanceToScene, OpaqueRadius)"), "scene depth limits march and sky marches full horizon");
        check(shader.contains("endDistance - VisibleRadius") && shader.contains("if (lengthToMarch <= 0.0)"), "clear foreground bypasses medium integration");
        check(shader.contains("HorizonOpticalDepth * (b * b * b - a * a * a)"), "shader uses tested analytic ramp");
        check(shader.contains("marchedFraction * float(i) / float(count)")
                && shader.contains("marchedFraction * float(i + 1) / float(count)"), "normalized shader segment endpoints avoid float cancellation");
        check(shader.contains("exp(-opticalDepth * (density(worldSample) + 0.7 * occupied))"), "world-space volumetric modulation retained");
        check(shader.contains("clamp(FogSteps, 8, 64)") && shader.contains("i < 64"), "no unbounded far-distance sampling");
        check(shader.contains("texelFetch(DepthSampler") && shader.contains("CameraModulo + offset"), "nearest scene depth and world-anchored medium retained");
        var defaults = SpiritRenderSettings.DEFAULT_FOG_RANGE;
        near(uniform(descriptor, "VisibleRadius"), defaults.visibleRadius(), 0, "descriptor clear-radius default");
        near(uniform(descriptor, "OpaqueRadius"), defaults.opaqueRadius(), 0, "descriptor opaque-radius default");
        near(uniform(descriptor, "HorizonOpticalDepth"), SpiritRenderSettings.HORIZON_OPTICAL_DEPTH, 1e-8, "descriptor optical-depth default");
        check(!shader.contains("MinExtinction") && !descriptor.contains("MinExtinction"), "fixed 64-block extinction removed");
    }
    public static void main(String[] args) throws Exception {
        rangesAndIntegration(); shaderFloatIntegral(); liveWiring();
        System.out.println("Spirit fog range: " + checks + " checks passed (CPU/resource contracts; no GPU/gameplay claim)");
    }
}
