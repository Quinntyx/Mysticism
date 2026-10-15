package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Discovered by runtimeSelfTest; no game bootstrap, display or model inference. */
public final class KuwaharaPassTest {
    private static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static String resource(String path) throws IOException {
        try (var stream = KuwaharaPassTest.class.getResourceAsStream("/assets/" + path)) {
            if (stream == null) throw new IOException("Missing shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static JsonObject descriptor(String name) throws IOException {
        return JsonParser.parseString(resource("mysticism/shaders/program/" + name + ".json")).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        var chain = JsonParser.parseString(resource("mysticism/shaders/post/kuwahara.json")).getAsJsonObject();
        var names = List.of("spirit_fog", "kuwahara", "spirit_saturation", "spirit_transition");
        var outputs = List.of("fogged", "painted", "saturated", "minecraft:main");
        String previous = "minecraft:main";
        var passes = chain.getAsJsonArray("passes");
        check(passes.size() == 4, "exactly one fog, painterly, saturation and transition pass");
        for (int i = 0; i < passes.size(); i++) {
            var pass = passes.get(i).getAsJsonObject();
            String name = names.get(i);
            check(pass.get("name").getAsString().equals("mysticism:" + name), "ordered live pass " + name);
            check(pass.get("intarget").getAsString().equals(previous), "continuous graph");
            String output = pass.get("outtarget").getAsString();
            check(!previous.equals(output) && output.equals(outputs.get(i)), "no framebuffer feedback");
            previous = output;
            var program = descriptor(name);
            check(program.get("vertex").getAsString().equals("blit"), "vanilla blit interface");
            check(program.get("fragment").getAsString().equals("mysticism:" + name), "namespaced fragment");
            String source = resource("mysticism/shaders/program/" + name + ".fsh");
            check(source.startsWith("#version 150"), "GLSL 150");
            for (var sampler : program.getAsJsonArray("samplers"))
                check(source.contains("uniform sampler2D " + sampler.getAsJsonObject().get("name").getAsString() + ";"), "declared sampler");
            for (var entry : program.getAsJsonArray("uniforms")) {
                var uniform = entry.getAsJsonObject();
                check(uniform.getAsJsonArray("values").size() == uniform.get("count").getAsInt(), "uniform arity");
            }
            check(!program.has("blend"), "replace intermediate target");
        }
        var kuwahara = descriptor("kuwahara");
        for (String name : List.of("KernelRadius", "SampleStride")) {
            var uniform = kuwahara.getAsJsonArray("uniforms").asList().stream()
                    .map(element -> element.getAsJsonObject()).filter(u -> u.get("name").getAsString().equals(name)).findFirst().orElseThrow();
            check(uniform.get("type").getAsString().equals("int") && uniform.get("count").getAsInt() == 1, "live integer " + name);
        }
        check(SpiritRenderSettings.painterlyStride(0) == 1, "bounded invalid height");
        check(SpiritRenderSettings.painterlyStride(540) == 1, "540p brush");
        check(SpiritRenderSettings.painterlyStride(1080) == 2, "1080p brush");
        check(SpiritRenderSettings.painterlyStride(1440) == 3, "1440p brush");
        check(SpiritRenderSettings.painterlyStride(2160) == 4, "4K brush");
        check(SpiritRenderSettings.painterlyStride(Integer.MAX_VALUE) == 4, "bounded taps at very large resolution");
        System.out.println("Kuwahara resource/settings: " + checks + " checks passed (GPU execution is the separate kuwaharaGpuTest task)");
    }
}
