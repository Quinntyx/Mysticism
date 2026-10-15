package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;

/** Real settings/routing/command handlers and shipped shader contracts, not GPU execution. */
public final class SpiritVisualControlsSelfTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        combinations();
        lifecycle();
        commands();
        shaderContracts();
        System.out.println("Spirit visual controls: " + checks + " checks passed (no game/GPU boot)");
    }

    private static void combinations() {
        for (var quality : SpiritRenderSettings.Quality.values()) {
            for (int bits = 0; bits < 16; bits++) {
                boolean enabled = (bits & 1) != 0, fog = (bits & 2) != 0;
                boolean painter = (bits & 4) != 0, saturation = (bits & 8) != 0;
                var settings = new SpiritVisualControls.Settings(enabled, fog, painter, saturation, quality);
                var controls = new SpiritVisualControls(quality);
                controls.configure(settings);
                var plan = controls.beginFrame(true, false, 0);
                check(plan.fog() == (enabled && fog), "fog master gate");
                check(plan.kuwahara() == (enabled && painter), "Kuwahara master gate");
                check(plan.saturation() == (enabled && saturation), "saturation master gate");
                check(plan.anyEnabled() == settings.anyEnabled(), "all-off chain bypass");
                check(plan.fogSteps() == quality.fogSteps && plan.kernelRadius() == quality.kernelRadius,
                        "live quality controls real step/tap budgets");
                check(plan.saturationValue() == (enabled && saturation ? 0f : 1f), "saturation off is full color");
                check(settings.passes(100).saturationValue() == 1f, "fade completes");
                check(settings.passes(-1).saturationValue() == (enabled && saturation ? 0f : 1f), "negative elapsed clamp");
                for (int conditions = 0; conditions < 16; conditions++) {
                    boolean prepared = (conditions & 1) != 0, depth = (conditions & 2) != 0;
                    boolean failed = (conditions & 4) != 0, hook = (conditions & 8) != 0;
                    check(plan.canRender(prepared, depth, failed, hook)
                                    == (settings.anyEnabled() && prepared && depth && !failed && (!(enabled && fog) || hook)),
                            "render eligibility: hook needed ONLY when fog requested");
                    var expected = !plan.fog() ? SpiritVisualControls.FogMode.VANILLA
                            : !prepared || failed ? SpiritVisualControls.FogMode.LINEAR_FALLBACK
                            : SpiritVisualControls.FogMode.VOLUMETRIC;
                    check(plan.fogMode(true, 1f, prepared, failed) == expected, "native fog policy");
                    check(plan.fogMode(false, 1f, prepared, failed) == SpiritVisualControls.FogMode.VANILLA,
                            "never change source/menu fog");
                    check(plan.fogMode(true, 0f, prepared, failed) == SpiritVisualControls.FogMode.VANILLA,
                            "zero entry strength retains native fog");
                }
                for (float strength : new float[]{.002f, .05f, .5f, .998f, 1f})
                    check(plan.fogMode(true, strength, true, false) == (plan.fog()
                                    ? SpiritVisualControls.FogMode.VOLUMETRIC : SpiritVisualControls.FogMode.VANILLA),
                            "volume owns transformed branch throughout fade, but respects fog toggle");
                var off = settings.withEnabled(false);
                check(off.fog() == fog && off.kuwahara() == painter && off.saturation() == saturation,
                        "master switch preserves individual preferences");
                check(off.withEnabled(enabled).equals(settings), "master off/on roundtrip");
            }
        }
    }

    private static void lifecycle() {
        var controls = new SpiritVisualControls(SpiritRenderSettings.Quality.MEDIUM);
        check(controls.state() == SpiritVisualControls.State.INACTIVE && controls.applied() == null, "initial state is not success");
        var plan = controls.beginFrame(true, false, 1.25);
        check(plan.saturationValue() == .5f, "actual midpoint smoothstep");
        controls.markApplied(plan, true);
        check(controls.state() == SpiritVisualControls.State.APPLIED && controls.applied() == plan, "last completed frame evidence");
        check(controls.status().contains("Actual: applied") && controls.status().contains("fog=volumetric"), "truthful success status");
        check(controls.detail().contains("factor 0.5"), "applied saturation amount, not just enabled flag");
        controls.configure(controls.settings().withEffect(SpiritVisualControls.Effect.FOG, false));
        check(controls.applied() == null && controls.state() == SpiritVisualControls.State.PENDING, "toggle invalidates stale success");
        plan = controls.beginFrame(true, false, 4);
        check(plan.canRender(true, true, false, false), "painter/saturation work even without fog mixin");
        controls.markApplied(plan, false);
        check(controls.detail().contains("fog=off (vanilla unchanged)") && controls.detail().contains("awaiting custom mesh"),
                "no fog or mesh success claim when absent");
        controls.fallback("driver failure; retry");
        check(controls.applied() == null && controls.state() == SpiritVisualControls.State.FALLBACK,
                "failed depth restoration cannot retain success");
        check(!controls.summary().contains("volumetric"), "fallback not labelled volumetric");
        controls.configure(controls.settings().withEnabled(false));
        plan = controls.beginFrame(true, true, 0);
        check(!plan.anyEnabled() && controls.state() == SpiritVisualControls.State.DISABLED, "disabled wins over waiting");
        check(controls.hudStatus().contains("disabled") && controls.hudStatus().contains("/spiritvisual status")
                && controls.hudStatus().length() < 52, "compact usable HUD with detail command");
        controls.configure(controls.settings().withEnabled(true));
        controls.beginFrame(false, true, 0);
        check(controls.state() == SpiritVisualControls.State.WAITING && controls.applied() == null, "wait for canonical mesh");
        controls.waiting(false);
        check(controls.detail().contains("no valid source snapshot"), "no false held-source claim");
        controls.waiting(true);
        check(controls.detail().contains("source snapshot held"), "real held-source result");
        controls.beginFrame(false, false, 5);
        check(controls.state() == SpiritVisualControls.State.INACTIVE, "exit clears previous frame status");
        controls.markApplied(controls.settings().passes(4), true);
        controls.inactive();
        check(controls.applied() == null && controls.state() == SpiritVisualControls.State.INACTIVE, "disconnect clears frame evidence");
        controls.configure(controls.settings().withEnabled(false));
        controls.inactive();
        check(controls.state() == SpiritVisualControls.State.DISABLED, "disconnect retains disabled preference");
    }

    private static void commands() throws Exception {
        var controls = new SpiritVisualControls(SpiritRenderSettings.Quality.MEDIUM);
        int[] retries = {0};
        var actions = new SpiritVisualCommands.Actions(
                value -> controls.configure(controls.settings().withEnabled(value)),
                (effect, value) -> controls.configure(controls.settings().withEffect(effect, value)),
                quality -> controls.configure(controls.settings().withQuality(quality)),
                () -> { retries[0]++; controls.configure(controls.settings()); }, controls::status);
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        dispatcher.register(SpiritVisualCommands.commandTree(actions));
        var feedback = new ArrayList<String>();
        var source = (FabricClientCommandSource) Proxy.newProxyInstance(FabricClientCommandSource.class.getClassLoader(),
                new Class<?>[]{FabricClientCommandSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendFeedback")) { feedback.add(((Text) args[0]).getString()); return null; }
                    throw new AssertionError("Unexpected game access: " + method.getName());
                });
        check(dispatcher.execute("spiritvisual", source) == 1 && feedback.getLast().contains("Actual: inactive"), "root status command");
        dispatcher.execute("spiritvisual off", source);
        check(!controls.settings().enabled() && feedback.getLast().contains("Actual: disabled"), "master command applies, not just logs");
        dispatcher.execute("spiritvisual on", source);
        check(controls.settings().enabled() && feedback.getLast().contains("Actual: pending"), "enable awaits real frame");
        for (var effect : SpiritVisualControls.Effect.values()) {
            String name = effect.name().toLowerCase(Locale.ROOT);
            dispatcher.execute("spiritvisual " + name + " off", source);
            var plan = controls.settings().passes(0);
            check(switch (effect) { case FOG -> !plan.fog(); case KUWAHARA -> !plan.kuwahara(); case SATURATION -> !plan.saturation(); },
                    "effect off command reaches actual pass plan");
            dispatcher.execute("spiritvisual " + name + " on", source);
            plan = controls.settings().passes(0);
            check(switch (effect) { case FOG -> plan.fog(); case KUWAHARA -> plan.kuwahara(); case SATURATION -> plan.saturation(); },
                    "effect on command reaches actual pass plan");
        }
        for (var quality : SpiritRenderSettings.Quality.values()) {
            dispatcher.execute("spiritvisual quality " + quality.name().toLowerCase(Locale.ROOT), source);
            check(controls.settings().quality() == quality, "each quality command connected");
        }
        dispatcher.execute("spiritvisual retry", source);
        check(retries[0] == 1 && controls.applied() == null, "retry requests recovery, not success");
        dispatcher.execute("spiritvisual status", source);
        check(feedback.getLast().contains("Requested:") && feedback.getLast().contains("Actual:"), "requested versus applied state");
        dispatcher.execute("spiritvisual help", source);
        check(feedback.getLast().contains("Client session only") && feedback.getLast().contains("not spirit terrain or collision"), "scope documented in client");
        var before = controls.settings();
        for (String invalid : new String[]{"quality ultra", "fog maybe", "kuwahara", "off extra"}) {
            try { dispatcher.execute("spiritvisual " + invalid, source); throw new AssertionError("accepted: " + invalid); }
            catch (CommandSyntaxException expected) { check(controls.settings().equals(before), "invalid commands cannot change settings"); }
        }
    }

    private static void shaderContracts() throws Exception {
        for (String[] effect : new String[][]{{"spirit_fog", "FogEnabled", "sceneOffset(texCoord"},
                {"kuwahara", "KuwaharaEnabled", "textureSize(DiffuseSampler"}}) {
            var program = json("shaders/program/" + effect[0] + ".json");
            var uniforms = program.getAsJsonArray("uniforms");
            var gate = uniforms.asList().stream().map(e -> e.getAsJsonObject())
                    .filter(u -> u.get("name").getAsString().equals(effect[1])).findFirst().orElseThrow();
            check(gate.get("type").getAsString().equals("int") && gate.get("count").getAsInt() == 1
                    && gate.getAsJsonArray("values").get(0).getAsInt() == 1, "declared gate uniform, compatible default");
            String shader = resource("shaders/program/" + effect[0] + ".fsh");
            int bypass = shader.indexOf("if (" + effect[1] + " == 0)");
            check(bypass >= 0 && bypass < shader.indexOf(effect[2]), "bypass BEFORE expensive fog/kernel work");
            check(shader.substring(bypass, shader.indexOf('}', bypass)).contains("return;"), "disabled pass actually returns");
        }
        var chain = json("shaders/post/kuwahara.json").getAsJsonArray("passes");
        check(chain.size() == 4 && chain.get(0).getAsJsonObject().get("name").getAsString().equals("mysticism:kuwahara")
                && chain.get(1).getAsJsonObject().get("name").getAsString().equals("mysticism:spirit_fog")
                && chain.get(2).getAsJsonObject().get("name").getAsString().equals("mysticism:spirit_saturation")
                && chain.get(3).getAsJsonObject().get("outtarget").getAsString().equals("minecraft:main"),
                "filter unfogged terrain before depth-aware fog, saturation and source transition");
        // Wiring guards supplement behavioral tests; do not stand in for executing GLSL on a GPU.
        String manager = Files.readString(Path.of("src/client/java/io/github/mysticism/client/spiritworld/ShaderManager.java"));
        check(manager.contains("setUniformValue(\"FogEnabled\", framePasses.fog() ? 1 : 0)"), "production fog gate upload");
        check(manager.contains("setUniformValue(\"KuwaharaEnabled\", framePasses.kuwahara() ? 1 : 0)"), "production painter gate upload");
        check(manager.contains("setUniformValue(\"Saturation\", framePasses.saturationValue())"), "production saturation upload");
        check(manager.indexOf("controls.markApplied(framePasses") > manager.indexOf("checkGl(\"restoring scene depth\")"), "success only after depth restoration");
        check(manager.contains("if (!framePasses.anyEnabled()) return;"), "master/all-off avoids source-scene replacement and chain");
        check(manager.contains("SpiritVisualCommands.init();"), "runtime command registration is wired");
        check(manager.contains("if (!depthReady) {") && manager.contains("fail(\"scene depth snapshot not observed\""),
                "missing depth stops the chain and selects native-fog fallback, not perpetual pending/suppression");
        check(manager.contains("setSamplerUniform(\"OccupancySampler\",medium);")
                && !manager.contains("if (medium!=0) KUWAHARA_SHADER.setSamplerUniform"), "clear stale sampler ID when fog disabled/unavailable");
    }
    private static JsonObject json(String path) throws Exception { return JsonParser.parseString(resource(path)).getAsJsonObject(); }
    private static String resource(String path) throws Exception {
        try (var stream = SpiritVisualControlsSelfTest.class.getResourceAsStream("/assets/mysticism/" + path)) {
            if (stream == null) throw new AssertionError("missing shipped resource: " + path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var out = new StringBuilder(); char[] buffer = new char[4096]; int n;
                while ((n = reader.read(buffer)) != -1) out.append(buffer, 0, n);
                return out.toString();
            }
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
