package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/** Explicit offscreen GPU suite, never silently skipped by the canonical CPU tests.
 * Executes shipped GLSL + vanilla blit vertex + descriptor defaults/actual post graph.
 * Does not bootstrap Minecraft or pretend to test Fabric/Satin callbacks in gameplay.
 */
public final class KuwaharaGlProbe implements AutoCloseable {
    private final int size;
    private final boolean rgba8;
    private final float tolerance;
    private final int fbo, vao, vbo;
    private final ArrayList<Integer> textures = new ArrayList<>();
    private final Map<String, Integer> programs = new HashMap<>();
    private int checks;

    private KuwaharaGlProbe(int size, boolean rgba8) {
        this.size = size;
        this.rgba8 = rgba8;
        tolerance = rgba8 ? .002f : 1e-6f;
        fbo = glGenFramebuffers();
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, new float[]{0,0,0,1, size,0,0,1, size,size,0,1,
                0,0,0,1, size,size,0,1, 0,size,0,1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0L);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
    }
    private void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private int texture(float[] pixels, boolean depth) {
        int texture = glGenTextures();
        textures.add(texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, depth ? GL_DEPTH_COMPONENT32F : (rgba8 ? GL_RGBA8 : GL_RGBA32F),
                size, size, 0, depth ? GL_DEPTH_COMPONENT : GL_RGBA, GL_FLOAT, pixels);
        return texture;
    }
    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new AssertionError("GLSL compile failed: " + glGetShaderInfoLog(shader));
        return shader;
    }
    private int program(String name) throws Exception {
        Integer cached = programs.get(name);
        if (cached != null) return cached;
        int vertex = compile(GL_VERTEX_SHADER, KuwaharaPassTest.resource("minecraft/shaders/program/blit.vsh"));
        int fragment = compile(GL_FRAGMENT_SHADER, KuwaharaPassTest.resource("mysticism/shaders/program/" + name + ".fsh"));
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, "Position");
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        check(glGetProgrami(program, GL_LINK_STATUS) != GL_FALSE, "link " + name + ": " + glGetProgramInfoLog(program));
        programs.put(name, program);
        return program;
    }
    private void matrix(int program, String name, Matrix4f value) {
        var buffer = BufferUtils.createFloatBuffer(16);
        value.get(buffer);
        glUniformMatrix4fv(glGetUniformLocation(program, name), false, buffer);
    }
    private int draw(String name, int input, Map<String, Integer> samplers, Map<String, Number> uniforms) throws Exception {
        int program = program(name);
        glUseProgram(program);
        JsonObject descriptor = KuwaharaPassTest.descriptor(name);
        // Initialize exactly the packaged JSON defaults; missing/optimized-out live uniforms fail.
        for (var entry : descriptor.getAsJsonArray("uniforms")) {
            var uniform = entry.getAsJsonObject();
            String key = uniform.get("name").getAsString();
            int location = glGetUniformLocation(program, key);
            check(location >= 0, "live descriptor uniform " + name + "/" + key);
            var values = uniform.getAsJsonArray("values");
            String type = uniform.get("type").getAsString();
            if (type.equals("matrix4x4")) {
                float[] data = new float[16];
                for (int i = 0; i < 16; i++) data[i] = values.get(i).getAsFloat();
                glUniformMatrix4fv(location, false, data);
            } else if (type.equals("int")) glUniform1i(location, values.get(0).getAsInt());
            else if (values.size() == 1) glUniform1f(location, values.get(0).getAsFloat());
            else if (values.size() == 2) glUniform2f(location, values.get(0).getAsFloat(), values.get(1).getAsFloat());
            else if (values.size() == 3) glUniform3f(location, values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
            else throw new AssertionError("Unsupported uniform fixture " + key);
        }
        matrix(program, "ProjMat", new Matrix4f().setOrtho(0, size, 0, size, .1f, 1000));
        glUniform2f(glGetUniformLocation(program, "OutSize"), size, size);
        if (name.equals("spirit_fog")) {
            matrix(program, "InverseViewProjection", new Matrix4f().perspective((float)Math.toRadians(70), 1, .05f, 1024).invert());
        }
        for (var entry : uniforms.entrySet()) {
            int location = glGetUniformLocation(program, entry.getKey());
            if (location < 0) continue; // Chain-level overrides belong to only one program.
            if (entry.getValue() instanceof Integer) glUniform1i(location, entry.getValue().intValue());
            else glUniform1f(location, entry.getValue().floatValue());
        }
        int output = texture(new float[size * size * 4], false);
        int unit = 0;
        for (var entry : descriptor.getAsJsonArray("samplers")) {
            String key = entry.getAsJsonObject().get("name").getAsString();
            int location = glGetUniformLocation(program, key);
            check(location >= 0, "live sampler " + key);
            int source = key.equals("DiffuseSampler") ? input : samplers.getOrDefault(key, 0);
            check(source != 0 && source != output, "bound sampler without feedback: " + key);
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, source);
            glUniform1i(location, unit++);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "complete output FBO");
        glViewport(0, 0, size, size);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        check(glGetError() == GL_NO_ERROR, "GL pass " + name);
        return output;
    }
    private float[] read(int texture) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        float[] result = new float[size * size * 4];
        glReadPixels(0, 0, size, size, GL_RGBA, GL_FLOAT, result);
        check(glGetError() == GL_NO_ERROR, "readback");
        for (float value : result) check(Float.isFinite(value), "finite output including clamped borders");
        return result;
    }
    private float[] solid(float red, float green, float blue, float alpha) {
        float[] data = new float[size * size * 4];
        for (int i = 0; i < size * size; i++) {
            data[4*i] = red; data[4*i+1] = green; data[4*i+2] = blue; data[4*i+3] = alpha;
        }
        return data;
    }
    private float[] depth(float value) {
        float[] data = new float[size * size];
        Arrays.fill(data, value);
        return data;
    }
    private int center() { return (size / 2 * size + size / 2) * 4; }
    private float[] filter(float[] color, float[] depth, int radius, int stride) throws Exception {
        return read(draw("kuwahara", texture(color, false), Map.of("DepthSampler", texture(depth, true)),
                Map.of("KernelRadius", radius, "SampleStride", stride)));
    }
    private void fixtures() throws Exception {
        int c = center();
        var color = solid(.1f, .2f, .3f, .37f);
        color[c] = .9f;
        float[] plane = depth(.995f);
        for (int radius = 1; radius <= 3; radius++) {
            var flat = filter(color, plane, radius, 1);
            check(flat[c] < .65f, "visible texture filtering at quality radius " + radius);
            check(Math.abs(flat[c+3] - .37f) < tolerance, "preserve alpha");
            var slope = plane.clone();
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                slope[y*size+x] += .0001f * (x + y - size / 2 * 2);
            var sloped = filter(color, slope, radius, 1);
            check(sloped[c] < .65f, "single-sample quadrant cannot dominate sloped terrain at radius " + radius);
            var disabled = filter(color, slope, 0, 1);
            for (int i = 0; i < color.length; i++) check(Math.abs(disabled[i] - color[i]) < tolerance, "disabled pixel-exact passthrough");
        }
        var isolated = depth(1);
        isolated[c/4] = .995f;
        check(Math.abs(filter(color, isolated, 2, 1)[c] - .9f) < tolerance, "isolated silhouette fallback");
        var wall = solid(.2f, .3f, .4f, 1);
        var wallDepth = depth(.995f);
        for (int y = 0; y < size; y++) for (int x = 0; x < size/2; x++) {
            wall[(y*size+x)*4] = 1;
            wallDepth[y*size+x] = .9958f; // Background glyph: old symmetric threshold would admit it.
        }
        check(Math.abs(filter(wall, wallDepth, 3, 1)[c] - .2f) < tolerance, "background glyph cannot bleed onto wall");
        wallDepth = depth(.995f);
        for (int y = 0; y < size; y++) for (int x = 0; x < size/2; x++) wallDepth[y*size+x] = 1;
        check(Math.abs(filter(wall, wallDepth, 3, 1)[c] - .2f) < tolerance, "sky cannot bleed onto wall");
        wallDepth = depth(1);
        for (int y = 0; y < size; y++) for (int x = 0; x < size/2; x++) wallDepth[y*size+x] = .995f;
        check(Math.abs(filter(wall, wallDepth, 3, 1)[c] - .2f) < tolerance, "terrain cannot bleed onto sky");
        for (int stride = 1; stride <= 4; stride++) {
            var output = filter(color, plane, 2, stride);
            int offset = c + stride * 4;
            check(output[offset] > color[offset] + .005f, "resolution-aware brush reaches stride " + stride);
        }
        var nearDepth = depth(.97f); // <5 blocks: fog bubble keeps fixture contrast.
        int input = texture(color, false);
        Map<String, Integer> samplers = Map.of("DepthSampler", texture(nearDepth, true),
                "OccupancySampler", texture(depth(0), true), "OriginalSampler", input);
        var passes = JsonParser.parseString(KuwaharaPassTest.resource("mysticism/shaders/post/kuwahara.json"))
                .getAsJsonObject().getAsJsonArray("passes");
        float filtered = 0;
        for (int radius : new int[]{0, 2}) for (float strength : new float[]{0, 1}) {
            var targets = new HashMap<String, Integer>();
            targets.put("minecraft:main", input);
            for (var entry : passes) {
                var pass = entry.getAsJsonObject();
                String name = pass.get("name").getAsString().substring("mysticism:".length());
                int output = draw(name, targets.get(pass.get("intarget").getAsString()), samplers,
                        Map.of("KernelRadius", radius, "SampleStride", 1, "Saturation", 1f, "EffectStrength", strength));
                targets.put(pass.get("outtarget").getAsString(), output);
            }
            var result = read(targets.get("minecraft:main"));
            if (strength == 0 || radius == 0) check(Math.abs(result[c] - .9f) < tolerance, "chain disabled/transition-start retains original");
            else { filtered = result[c]; check(filtered < .65f, "enabled chain visibly reaches FINAL world output"); }
        }
        System.out.println("Kuwahara GPU " + size + "x" + size + " "
                + (rgba8 ? "RGBA8 (game targets)" : "RGBA32F") + ": " + checks
                + " checks; enabled final impulse .9 -> " + filtered);
    }

    /** Exercise the merged wider fog in the actual GLSL, not a CPU implementation.
     * Paired black/white draws cancel in-scattering, leaving measured transmission.
     * Real nonlinear perspective depth includes the long-distance precision loss.
     */
    private void fogRanges() throws Exception {
        int before = checks;
        int black = texture(solid(0, 0, 0, 0), false);
        int white = texture(solid(1, 1, 1, 0), false);
        int occupancy = texture(depth(0), true);
        var projection = new Matrix4f().perspective((float)Math.toRadians(70), 1, .05f, 1024);
        float quantization = rgba8 ? 2f / 255 : .00005f;
        for (int chunks : new int[]{2, 8, 12, 16, 32}) {
            var range = SpiritRenderSettings.fogRange(chunks);
            for (var quality : SpiritRenderSettings.Quality.values()) {
                var uniforms = Map.<String, Number>of("VisibleRadius", (float)range.visibleRadius(),
                        "OpaqueRadius", (float)range.opaqueRadius(), "HorizonOpticalDepth",
                        (float)SpiritRenderSettings.HORIZON_OPTICAL_DEPTH, "FogSteps", quality.fogSteps);
                for (float fraction : new float[]{.4f, .75f, 1f, Float.POSITIVE_INFINITY}) {
                    float distance = fraction * (float)range.opaqueRadius();
                    float windowDepth = Float.isFinite(distance)
                            ? .5f + .5f * projection.transformProject(new org.joml.Vector3f(0, 0, -distance)).z
                            : 1f;
                    check(windowDepth > 0 && windowDepth <= 1, "valid nonlinear scene/sky depth");
                    var samplers = Map.of("DepthSampler", texture(depth(windowDepth), true),
                            "OccupancySampler", occupancy);
                    var dark = read(draw("spirit_fog", black, samplers, uniforms));
                    var bright = read(draw("spirit_fog", white, samplers, uniforms));
                    float transmission = bright[center()] - dark[center()];
                    String fixture = chunks + " chunks / " + quality + " / " + fraction + " radius";
                    check(transmission >= -quantization && transmission <= 1 + quantization,
                            "energy-conserving fog: " + fixture + " = " + transmission);
                    if (fraction < .5f) check(Math.abs(transmission - 1) < quantization,
                            "wide foreground is not hidden by locality fog: " + fixture);
                    else if (fraction < 1) check(transmission > .27f - quantization && transmission < .43f + quantization,
                            "distant terrain still has useful contrast: " + fixture + " = " + transmission);
                    else check(transmission <= .0012f + quantization,
                            "geometry and sky reach shared opaque horizon: " + fixture + " = " + transmission);
                    check(Math.abs(bright[center() + 3] - 1) < quantization,
                            "opaque fog composition covers clear main alpha");
                }
            }
        }
        System.out.println("Wider fog GPU " + size + "x" + size + " " + (rgba8 ? "RGBA8" : "RGBA32F")
                + ": " + (checks - before) + " checks; clear foreground/useful distant contrast/geometry and sky horizon in all tiers");
    }
    @Override public void close() {
        textures.forEach(texture -> glDeleteTextures(texture));
        programs.values().forEach(program -> glDeleteProgram(program));
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteFramebuffers(fbo);
    }
    public static void main(String[] args) throws Exception {
        var errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try {
            if (!GLFW.glfwInit()) throw new IllegalStateException("No GLFW display: run with an existing display or xvfb-run; this suite never skips");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(32, 32, "Mysticism Kuwahara regression", 0, 0);
            if (window == 0) throw new IllegalStateException("OpenGL 3.3 context unavailable");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("Offscreen OpenGL: " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));
            // Delete/recreate every program/attachment at different actual pixel dimensions,
            // then the original size again. This complements the production frame-gate tests;
            // it does NOT pretend to exercise Minecraft/Satin's window/reload callbacks.
            for (boolean rgba8 : new boolean[]{false, true}) for (int size : new int[]{25, 17, 33, 25})
                try (var probe = new KuwaharaGlProbe(size, rgba8)) {
                    probe.fixtures();
                    probe.fogRanges();
                }
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            errors.free();
        }
    }
}
