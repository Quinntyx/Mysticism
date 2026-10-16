package io.github.mysticism.embedding;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Launcher argument regression only; the real model probe is separately opt-in. */
public final class EmbeddedIntellijInferenceProbeTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        Path cwd = Path.of("build/Minecraft cwd with spaces").toAbsolutePath();
        String model = "-Dmysticism.embedding.modelPath=" + cwd.resolve("model with spaces.gguf");
        var options = List.of(model, "-Dmysticism.embedding.download=true", "-Dmysticism.embedding.nativeThreads=2");
        var command = EmbeddedIntellijInferenceProbe.childCommand(options, cwd);
        check(command.contains(model), "Model path was split/quoted instead of retained as one argv element");
        check(command.indexOf("-Dmysticism.embedding.download=false") > command.indexOf("-Dmysticism.embedding.download=true"),
                "Real probe could download weights");
        check(command.contains("-Dmysticism.embedding.endpoint=not-an-http-url"), "Real probe permits service fallback");
        check(command.stream().noneMatch(a -> a.startsWith("-Dmysticism.embedding.provider=")), "Default provider selection bypassed");
        check(command.contains("-Dnet.ladenthin.llama.tmpdir=" + cwd.resolve("native runtime")), "JNI extraction escaped owned checkout");
        check(command.get(command.size() - 1).equals("--child"), "Missing isolated child entrypoint");
        for (String entry : command.get(command.indexOf("-cp") + 1).split(java.util.regex.Pattern.quote(File.pathSeparator)))
            check(Path.of(entry).isAbsolute(), "Relative classpath depends on game cwd");
        String relative = "build/classes" + File.pathSeparator + "build/resources with spaces";
        String absolute = EmbeddedIntellijInferenceProbe.absoluteClasspath(relative);
        check(absolute.equals(Path.of("build/classes").toAbsolutePath() + File.pathSeparator
                + Path.of("build/resources with spaces").toAbsolutePath()), "Relative classpath not correctly rebased");
        for (var invalid : List.of(List.of(model, model), List.of("--child"), List.of("-Dmysticism.embedding.provider=http"),
                List.of("-javaagent:other.jar"), List.of("-Dmysticism.embedding.modelPath"))) {
            try {
                EmbeddedIntellijInferenceProbe.childCommand(invalid, cwd);
                throw new AssertionError("Invalid/duplicate option accepted: " + invalid);
            } catch (IllegalArgumentException expected) { /* fail closed */ }
        }
        try {
            EmbeddedIntellijInferenceProbe.childCommand(List.of(), Path.of("relative cwd"));
            throw new AssertionError("Relative launch cwd accepted");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
        System.out.println("EmbeddedIntellijInferenceProbeTest passed: argv/classpath/default-selection/offline guards; no model inference");
    }
}
