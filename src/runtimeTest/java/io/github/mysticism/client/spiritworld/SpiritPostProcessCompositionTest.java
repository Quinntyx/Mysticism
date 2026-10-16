package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Packaged graph + production fog policy, with a CPU shader reference (not GPU execution). */
public final class SpiritPostProcessCompositionTest {
    private static final String ROOT = "assets/mysticism/shaders/";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = SpiritPostProcessCompositionTest.class.getClassLoader().getResourceAsStream(ROOT + path)) {
            if (in == null) throw new AssertionError("Missing packaged shader " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String[] graph(JsonObject chain) {
        JsonArray passes = chain.getAsJsonArray("passes");
        String[] expectedNames = {"mysticism:kuwahara", "mysticism:spirit_fog",
                "mysticism:spirit_saturation", "mysticism:spirit_transition"};
        String[] expectedTargets = {"painted", "fogged", "saturated", "minecraft:main"};
        check(passes.size() == 4, "all four stages must run exactly once");
        check(chain.getAsJsonArray("targets").size() == 3, "bounded full-size targets");
        Set<String> targets = new HashSet<>();
        for (var target : chain.getAsJsonArray("targets")) targets.add(target.getAsString());
        check(targets.equals(Set.of("painted", "fogged", "saturated")), "exact unique scratch targets");
        String previous = "minecraft:main";
        String[] names = new String[4];
        for (int i = 0; i < 4; i++) {
            JsonObject pass = passes.get(i).getAsJsonObject();
            names[i] = pass.get("name").getAsString();
            String input = pass.get("intarget").getAsString(), output = pass.get("outtarget").getAsString();
            check(names[i].equals(expectedNames[i]), "paint BEFORE fog, independent saturation and final transition");
            check(input.equals(previous) && output.equals(expectedTargets[i]), "continuous graph, no dropped effect");
            check(!input.equals(output), "no framebuffer read/write feedback");
            check(!pass.has("auxtargets"), "original scene depth/color are bound after snapshot, not scratch depth");
            previous = output;
        }
        return names;
    }

    private static void resourcesAndOrder() throws IOException {
        JsonObject chain = JsonParser.parseString(resource("post/kuwahara.json")).getAsJsonObject();
        graph(chain);
        // Prove regressions are caught: former fog-first order and an accidentally dropped stage.
        JsonObject oldOrder = chain.deepCopy();
        JsonArray oldPasses = oldOrder.getAsJsonArray("passes");
        var first = oldPasses.get(0); oldPasses.set(0, oldPasses.get(1)); oldPasses.set(1, first);
        rejectGraph(oldOrder);
        JsonObject missing = chain.deepCopy(); missing.getAsJsonArray("passes").remove(1); rejectGraph(missing);
        JsonObject feedback = chain.deepCopy();
        feedback.getAsJsonArray("passes").get(0).getAsJsonObject().addProperty("outtarget", "minecraft:main");
        rejectGraph(feedback);

        for (String name : graph(chain)) {
            String program = name.substring(name.indexOf(':') + 1);
            JsonObject descriptor = JsonParser.parseString(resource("program/" + program + ".json")).getAsJsonObject();
            String source = resource("program/" + program + ".fsh");
            check(descriptor.get("vertex").getAsString().equals("blit"), "fullscreen vertex interface");
            check(descriptor.get("fragment").getAsString().equals(name), "fragment resource linkage");
            check(!descriptor.has("blend"), "replace scratch output, never alpha-blend fog twice");
            Set<String> samplers = new HashSet<>();
            for (var sampler : descriptor.getAsJsonArray("samplers")) samplers.add(sampler.getAsJsonObject().get("name").getAsString());
            Set<String> declared = new HashSet<>();
            var matcher = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;").matcher(source);
            while (matcher.find()) declared.add(matcher.group(1));
            check(samplers.equals(declared), "all live sampler names are described");
            JsonObject uniforms = new JsonObject();
            for (var uniform : descriptor.getAsJsonArray("uniforms")) {
                JsonObject value = uniform.getAsJsonObject();
                uniforms.add(value.get("name").getAsString(), value);
            }
            matcher = Pattern.compile("uniform\\s+(mat4|vec3|vec4|float|int)\\s+(\\w+)\\s*;").matcher(source);
            while (matcher.find()) {
                String type = matcher.group(1), variable = matcher.group(2);
                check(uniforms.has(variable), "live uniform described: " + variable);
                JsonObject value = uniforms.getAsJsonObject(variable);
                int count = switch (type) { case "mat4" -> 16; case "vec3" -> 3; case "vec4" -> 4; default -> 1; };
                String jsonType = switch (type) { case "mat4" -> "matrix4x4"; case "int" -> "int"; default -> "float"; };
                check(value.get("count").getAsInt() == count && value.get("type").getAsString().equals(jsonType),
                        "shader/descriptor uniform type agreement");
                check(value.getAsJsonArray("values").size() == count, "complete default uniform values");
            }
            if (program.equals("kuwahara") || program.equals("spirit_fog") || program.equals("spirit_transition"))
                check(samplers.contains("DepthSampler"), "painting, volume and live-source fog consume ORIGINAL scene depth");
        }
        String fog = resource("program/spirit_fog.fsh");
        check(fog.contains("HorizonOpticalDepth * (b * b * b - a * a * a)"), "CPU reference matches exact quadratic ramp integral");
        check(fog.contains("exp(-opticalDepth * (density(worldSample) + 0.7 * occupied))"), "Beer-Lambert composition");
        check(!fog.contains("NearBubble") && !fog.contains("MinExtinction"), "no obsolete tiny-radius profile remains");
        check(fog.contains("depth >= 0.9999999 ? OpaqueRadius"), "sky marches to opaque horizon");
        check(fog.contains("texelFetch(DepthSampler, pixel, 0)"), "depth is not interpolated at silhouettes");
        check(fog.contains("scene.rgb * transmittance + scattering, 1.0"), "alpha-zero sky still receives fog");
        check(fog.contains("CameraModulo + offset") && fog.contains("occupancy(offset)"), "world-space density and terrain occupancy retained");
        String painterly = resource("program/kuwahara.fsh");
        check(painterly.contains("sampleDepth > centerDepth ||"), "no background pigment through foreground terrain");
        check(painterly.contains("clamp(KernelRadius, 0, 3)"), "bounded painterly quality retained");
        String transition = resource("program/spirit_transition.fsh");
        check(transition.contains("mix(original, transformed, clamp(EffectStrength, 0.0, 1.0))"), "one crossfade after BOTH effects");

        // Ordering contract only; this does not claim mixin or OpenGL execution.
        String manager = Files.readString(Path.of("src/client/java/io/github/mysticism/client/spiritworld/ShaderManager.java"));
        int snapshot = manager.indexOf("sceneDepth.copyDepthFrom(client.getFramebuffer())");
        int bind = manager.indexOf("setSamplerUniform(\"DepthSampler\", sceneDepth.getDepthAttachment())");
        int render = manager.indexOf("KUWAHARA_SHADER.render(context.tickCounter()");
        int restore = manager.indexOf("main.copyDepthFrom(sceneDepth)");
        check(snapshot >= 0 && snapshot < bind && bind < render && render < restore, "stable original depth through both passes, restored before hand");
        check(manager.contains("framePasses.fogMode(inSpiritWorld(), effectStrength(), frame.ready(), failed)"),
                "production uses tested toggle-aware analytic/volume ownership policy");
        var settings = new SpiritVisualControls(SpiritRenderSettings.Quality.MEDIUM).settings();
        for (boolean enabled : new boolean[]{false,true}) for (boolean fogEnabled : new boolean[]{false,true})
            for (float strength : new float[]{0,.001f,.002f,.05f,.5f,.998f,1}) {
                var passes=settings.withEnabled(enabled).withEffect(SpiritVisualControls.Effect.FOG,fogEnabled).passes(1);
                var expected=enabled && fogEnabled && SpiritRenderSettings.useVolumetricFog(true,false,strength)
                        ? SpiritVisualControls.FogMode.VOLUMETRIC : SpiritVisualControls.FogMode.VANILLA;
                check(passes.fogMode(true,strength,true,false)==expected,"toggle and fade compose without analytic double fog");
            }
        check(manager.indexOf("fogHookSeen = false;") < snapshot, "each frame must observe the vanilla fog hook");
        check(manager.contains("WorldRenderEvents.AFTER_TRANSLUCENT.register") && manager.contains("WorldRenderEvents.END.register"), "world color/depth before hand and HUD");
    }

    private static void rejectGraph(JsonObject graph) {
        boolean rejected = false;
        try { graph(graph); } catch (AssertionError expected) { rejected = true; }
        check(rejected, "invalid composition graph must fail");
    }

    private static void fogOwnership() {
        for (float strength : new float[]{.002f, .05f, .25f, .5f, .998f, 1f}) {
            check(SpiritRenderSettings.useVolumetricFog(true, false, strength), "volume owns fog throughout entry/exit fade");
            check(!SpiritRenderSettings.useVolumetricFog(false, false, strength), "missing framebuffer/matrix uses analytic fallback");
            check(!SpiritRenderSettings.useVolumetricFog(true, true, strength), "shader failure uses analytic fallback");
        }
        check(!SpiritRenderSettings.useVolumetricFog(true, false, 0), "normal source scene retains vanilla fog");
        check(!SpiritRenderSettings.useVolumetricFog(true, false, .001f), "inactive threshold agrees with world-frame gate");
    }

    private record Fog(double transmission, double scattering) {}

    /** The production exact quadratic ramp, with fixed medium fixtures. */
    private static Fog fog(SpiritRenderSettings.FogRange range, double distance, int count, double medium) {
        double end = Math.min(distance, range.opaqueRadius());
        double step = Math.max(0, end - range.visibleRadius()) / count;
        double t = 1, scattering = 0;
        for (int i = 0; i < count && t > .001; i++) {
            double start = range.visibleRadius() + i * step;
            double segment = Math.exp(-medium * range.opticalDepth(start, start + step));
            scattering += t * (1 - segment);
            t *= segment;
        }
        near(t + scattering, 1, 2e-14, "energy conservation");
        return new Fog(t, scattering);
    }

    private static void usefulHorizon() {
        for (int chunks : new int[]{2, 4, 12, 32}) {
            var range = SpiritRenderSettings.fogRange(chunks);
            near(range.opaqueRadius(), chunks * 16, 0, "horizon follows configured render distance, not sparse locality");
            for (var quality : SpiritRenderSettings.Quality.values()) {
                for (double medium : new double[]{1, 1.25, 1.5, 2.2}) {
                    double previous = 1;
                    for (int distance = 0; distance <= range.opaqueRadius() * 2; distance++) {
                        Fog f = fog(range, distance, quality.fogSteps, medium);
                        check(Math.max(.001, f.transmission) <= Math.max(.001, previous) + 1e-14,
                                "monotonic optical depth above early-out cutoff");
                        check(f.transmission >= 0 && f.transmission <= 1, "bounded transmission");
                        if (distance <= range.visibleRadius()) near(f.transmission, 1, 0, "clear half-radius foreground");
                        if (distance >= range.opaqueRadius()) check(f.transmission <= .001 + 1e-14, "opaque terrain AND sky horizon at every quality");
                        previous = f.transmission;
                    }
                    double middle = range.opaqueRadius() * .75;
                    check(fog(range, middle, quality.fogSteps, medium).transmission > .14, "mid-distance landmarks remain useful even in dense medium");
                    near(fog(range, middle, quality.fogSteps, medium).transmission,
                            Math.exp(-medium * range.opticalDepth(0, middle)), 1e-14,
                            "exact integration, no quality-dependent opacity drift before cutoff");
                }
            }
        }
    }

    private static double depth(double distance) {
        double near = .05, far = 1024;
        return far / (far - near) - far * near / ((far - near) * distance);
    }

    /** Scalar pigment fixture, same shared quadrant weights and original-depth guard as GLSL. */
    private static double paint(double[] colors, double[] depths, int radius) {
        double[] sums = new double[4], squares = new double[4], weights = new double[4];
        for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
            int index = (y + 3) * 7 + x + 3;
            double d = depths[index], center = depths[24];
            if ((center >= .9999999) != (d >= .9999999) || d > center || Math.abs(center - d) > .0015) continue;
            for (int q = 0; q < 4; q++) {
                if (((q == 0 || q == 2) ? x <= 0 : x >= 0) && (q < 2 ? y <= 0 : y >= 0)) {
                    sums[q] += colors[index]; squares[q] += colors[index] * colors[index]; weights[q]++;
                }
            }
        }
        double result = 0, total = 0;
        for (int q = 0; q < 4; q++) {
            check(weights[q] > 0, "center keeps every quadrant populated");
            double mean = sums[q] / weights[q], variance = Math.max(0, squares[q] / weights[q] - mean * mean);
            double weight = 1 / (.001 + variance);
            result += mean * weight; total += weight;
        }
        return result / total;
    }

    private static double composite(double color, Fog fog) {
        return color * fog.transmission + .75 * fog.scattering; // constant-light red-channel fixture
    }

    private static void bothEffectsAndOcclusion() throws IOException {
        String[] stages = graph(JsonParser.parseString(resource("post/kuwahara.json")).getAsJsonObject());
        for (var quality : SpiritRenderSettings.Quality.values()) {
            double[] pigment = new double[49], depths = new double[49];
            for (int i = 0; i < 49; i++) { pigment[i] = .2 + (i % 3) * .03; depths[i] = depth(20); }
            pigment[24] = .95;
            double painted = paint(pigment, depths, quality.kernelRadius);
            check(Math.abs(painted - pigment[24]) > .1, "painterly changes noisy un-fogged terrain");
            for (double distance : new double[]{3, 96, 128, 192}) {
                Fog f = fog(SpiritRenderSettings.DEFAULT_FOG_RANGE, distance, quality.fogSteps, 1.25);
                double output = pigment[24];
                for (String stage : stages) {
                    output = switch (stage) {
                        case "mysticism:kuwahara" -> painted;
                        case "mysticism:spirit_fog" -> composite(output, f);
                        // Full saturation/full entry fixtures: these stages preserve both effects.
                        case "mysticism:spirit_saturation", "mysticism:spirit_transition" -> output;
                        default -> throw new AssertionError("Unexpected stage " + stage);
                    };
                }
                near(output, composite(painted, f), 0, "graph applies both effects to the same scene");
                if (distance == 128) {
                    check(Math.abs(output - painted) > .02, "fog not dropped by painterly pass");
                    check(Math.abs(output - composite(pigment[24], f)) > .02, "fog does not erase painterly at useful distances");
                    check(Math.abs(output - composite(output, f)) > .02, "a second fog layer would change the result");
                }
                for (double strength : new double[]{0, .25, .5, 1}) {
                    double original = .4;
                    double transition = original * (1 - strength) + output * strength;
                    near(transition, original + (output - original) * strength, 1e-15, "crossfade only after combined effect");
                }
            }
            double wall = painted;
            // A background glyph within the old nonlinear-depth tolerance must never contribute.
            check(depth(22) - depth(20) < .0015, "fixture catches symmetric-depth bleed regression");
            for (int i = 0; i < 49; i++) if (i != 24) { pigment[i] = 1; depths[i] = depth(22); }
            near(paint(pigment, depths, quality.kernelRadius), .95, 1e-14, "background rejected at original wall depth");
            check(wall < .95, "occlusion fixture also distinguishes enabled painterly");
        }
    }

    public static void main(String[] args) throws Exception {
        resourcesAndOrder(); fogOwnership(); usefulHorizon(); bothEffectsAndOcclusion();
        System.out.println("Spirit postprocess composition: " + checks + " checks passed (resources/policy/CPU reference; no GPU)");
    }
}
