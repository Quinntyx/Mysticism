package io.github.mysticism.client.spiritworld;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Checks processed resources, not a second copy of descriptors or a source-tree-only fixture. */
public final class SpiritPostprocessResourcesTest {
    private static int checks;
    private static final String ROOT = "assets/mysticism/shaders/";
    private static final Pattern UNIFORM = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Map<String, String> TYPES = Map.of("float", "float", "int", "int", "vec2", "float",
            "vec3", "float", "vec4", "float", "mat4", "matrix4x4");
    private static final Map<String, Integer> COUNTS = Map.of("float", 1, "int", 1, "vec2", 2,
            "vec3", 3, "vec4", 4, "mat4", 16);

    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static String resource(String path) throws IOException {
        try (var stream = SpiritPostprocessResourcesTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("Missing processed shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static JsonObject json(String path) throws IOException {
        return JsonParser.parseString(resource(path)).getAsJsonObject();
    }
    private static void validateProgram(String name, JsonObject descriptor) throws IOException {
        check(descriptor.get("vertex").getAsString().equals("blit"), name + ": expected vanilla full-screen blit vertex");
        check(descriptor.get("fragment").getAsString().equals("mysticism:" + name), name + ": wrong fragment identifier");
        check(descriptor.getAsJsonArray("attributes").size() == 1
                && descriptor.getAsJsonArray("attributes").get(0).getAsString().equals("Position"), name + ": blit attribute contract");
        String fragment = resource(ROOT + "program/" + name + ".fsh");
        check(fragment.startsWith("#version 150"), name + ": Minecraft 1.21 GLSL baseline");
        Map<String, String> declared = new HashMap<>();
        declared.put("ProjMat", "mat4"); // vanilla blit.vsh uniforms
        declared.put("OutSize", "vec2");
        var matcher = UNIFORM.matcher(fragment);
        while (matcher.find()) check(declared.put(matcher.group(2), matcher.group(1)) == null, name + ": duplicate GLSL uniform");
        Set<String> samplers = new HashSet<>();
        for (var sampler : descriptor.getAsJsonArray("samplers")) {
            String uniform = sampler.getAsJsonObject().get("name").getAsString();
            check(samplers.add(uniform), name + ": duplicate sampler");
            check("sampler2D".equals(declared.remove(uniform)), name + ": sampler does not match GLSL: " + uniform);
        }
        check(samplers.contains("DiffuseSampler"), name + ": input color must be bound by PostEffectPass");
        for (var element : descriptor.getAsJsonArray("uniforms")) {
            var uniform = element.getAsJsonObject();
            String uniformName = uniform.get("name").getAsString();
            String type = declared.remove(uniformName);
            check(type != null && TYPES.get(type) != null, name + ": undeclared/duplicate uniform " + uniformName);
            check(uniform.get("type").getAsString().equals(TYPES.get(type)), name + ": mismatched uniform type " + uniformName);
            int count = uniform.get("count").getAsInt();
            check(count == COUNTS.get(type), name + ": mismatched uniform count " + uniformName);
            var defaults = uniform.getAsJsonArray("values");
            check(defaults.size() == count || defaults.size() == 1, name + ": invalid startup values " + uniformName);
            for (var value : defaults) check(Double.isFinite(value.getAsDouble()), name + ": nonfinite startup value");
        }
        check(declared.isEmpty(), name + ": unbound shader uniforms " + declared);
    }
    private static void validateChain(JsonObject chain) throws IOException {
        Set<String> targets = new HashSet<>();
        targets.add("minecraft:main");
        for (var target : chain.getAsJsonArray("targets")) {
            // String targets are full-size and will participate in PostEffectProcessor.setupDimensions.
            check(target.isJsonPrimitive() && target.getAsJsonPrimitive().isString(), "Targets must resize with main");
            check(targets.add(target.getAsString()), "Duplicate target");
        }
        List<String> order = new ArrayList<>();
        String previous = "minecraft:main";
        for (var element : chain.getAsJsonArray("passes")) {
            var pass = element.getAsJsonObject();
            String name = pass.get("name").getAsString();
            order.add(name);
            String input = pass.get("intarget").getAsString(), output = pass.get("outtarget").getAsString();
            check(targets.contains(input) && targets.contains(output), "Undefined pass target");
            check(input.equals(previous) && !input.equals(output), "Broken chain or texture feedback");
            previous = output;
            check(name.startsWith("mysticism:"), "Unexpected program namespace");
            String shortName = name.substring("mysticism:".length());
            validateProgram(shortName, json(ROOT + "program/" + shortName + ".json"));
        }
        check(previous.equals("minecraft:main"), "Must compose final color to main");
        check(order.equals(List.of("mysticism:spirit_fog", "mysticism:kuwahara",
                "mysticism:spirit_saturation", "mysticism:spirit_transition")), "Fog/filter/saturation/entry composition order");
    }
    private interface Check { void run() throws Exception; }
    private static void rejects(Check action) throws Exception {
        try { action.run(); } catch (AssertionError expected) { checks++; return; }
        throw new AssertionError("Broken shader contract accepted by regression checker");
    }
    public static void main(String[] args) throws Exception {
        var chain = json(ROOT + "post/kuwahara.json");
        validateChain(chain);
        var badTarget = chain.deepCopy();
        badTarget.getAsJsonArray("passes").get(0).getAsJsonObject().addProperty("outtarget", "not-allocated");
        rejects(() -> validateChain(badTarget));
        var fixedSize = chain.deepCopy();
        fixedSize.getAsJsonArray("targets").set(0, JsonParser.parseString("{\"name\":\"fogged\",\"width\":1,\"height\":1}"));
        rejects(() -> validateChain(fixedSize));
        var descriptor = json(ROOT + "program/kuwahara.json");
        var missingSize = descriptor.deepCopy();
        missingSize.getAsJsonArray("uniforms").remove(1);
        rejects(() -> validateProgram("kuwahara", missingSize));
        var wrongCount = descriptor.deepCopy();
        wrongCount.getAsJsonArray("uniforms").get(2).getAsJsonObject().addProperty("count", 2);
        rejects(() -> validateProgram("kuwahara", wrongCount));
        var unboundDepth = descriptor.deepCopy();
        unboundDepth.getAsJsonArray("samplers").remove(1);
        rejects(() -> validateProgram("kuwahara", unboundDepth));
        var wrongFragment = descriptor.deepCopy();
        wrongFragment.addProperty("fragment", "kuwahara");
        rejects(() -> validateProgram("kuwahara", wrongFragment));
        System.out.println("SpiritPostprocessResourcesTest passed: " + checks + " checks (including 6 rejection cases)");
    }
}
