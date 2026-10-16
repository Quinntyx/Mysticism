package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.ladenthin.llama.LlamaModel;

/** Opt-in real inference using Loom's actual client embedding options in a fresh JVM.
 * Not a *Test: ordinary builds must not depend on approved weights or the network.
 */
public final class EmbeddedIntellijInferenceProbe {
    private static final Set<String> OPTIONS = Set.of("bundle", "modelPath", "resourceCache", "download",
            "preparationDeadlineMillis", "nativeThreads");
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        if (Arrays.equals(args, new String[]{"--child"})) { child(); return; }
        Path root = Path.of("build/embedding-intellij-inference-probe").toAbsolutePath();
        Files.createDirectories(root);
        Path cwd = Files.createTempDirectory(root, "Minecraft run with spaces ");
        Files.createDirectory(cwd.resolve("native runtime"));
        Process process = new ProcessBuilder(childCommand(List.of(args), cwd)).directory(cwd.toFile()).inheritIO().start();
        // This bounds only the probe's own child, never a game/user process.
        if (!process.waitFor(6, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Real IntelliJ-cwd inference child timed out");
        }
        check(process.exitValue() == 0, "Real IntelliJ-cwd inference child failed: " + process.exitValue());
        System.out.println("EmbeddedIntellijInferenceProbe passed: real model, fresh JVM, Loom client options, unrelated cwd=" + cwd
                + "; NOT an IntelliJ GUI/gameplay launch");
    }

    static List<String> childCommand(List<String> options, Path cwd) {
        if (!cwd.isAbsolute()) throw new IllegalArgumentException("Probe cwd must be absolute");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-ea");
        var seen = new HashSet<String>();
        for (String option : options) {
            String prefix = "-Dmysticism.embedding.";
            int equals = option.indexOf('=');
            if (!option.startsWith(prefix) || equals < prefix.length())
                throw new IllegalArgumentException("Expected Loom embedding JVM option: " + option);
            String name = option.substring(prefix.length(), equals);
            if (!OPTIONS.contains(name) || !seen.add(name))
                throw new IllegalArgumentException("Unknown/duplicate Loom embedding JVM option: " + option);
            command.add(option); // Keep spaces as part of ONE argv element, never shell-quote/split.
        }
        // The opt-in probe never downloads, starts a service, or permits an HTTP fallback.
        command.add("-Dmysticism.embedding.download=false");
        command.add("-Dmysticism.embedding.endpoint=not-an-http-url");
        command.add("-Dnet.ladenthin.llama.tmpdir=" + cwd.resolve("native runtime"));
        command.addAll(List.of("-cp", absoluteClasspath(System.getProperty("java.class.path")),
                EmbeddedIntellijInferenceProbe.class.getName(), "--child"));
        return List.copyOf(command);
    }

    static String absoluteClasspath(String classpath) {
        return String.join(File.pathSeparator, Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
    }

    private static void child() throws Exception {
        check(System.getProperty(EmbeddingProviders.PROVIDER_PROPERTY) == null, "Probe must exercise default provider selection");
        check("false".equals(System.getProperty("mysticism.embedding.download")), "Probe must be offline");
        check(EmbeddingSpace.SCHEMA == 2 && EmbeddingSpace.DIMENSIONS == 768 && EmbeddingSpace.NATIVE_DIMENSIONS == 768,
                "Probe requires fresh native v2, not a remapped profile");
        try (var selection = EmbeddingProviders.configured()) {
            check(selection instanceof EmbeddedEmbeddingProvider, "Default selection is not in-process");
        }
        String bundle = System.getProperty(EmbeddedRuntimeResources.BUNDLE_PROPERTY);
        if (bundle == null) {
            try (var pinned = new LocalNomicV2ModelAsset()) {
                System.out.println("Real launch model resolved and SHA256 verified: " + pinned.resolve());
            }
        }
        ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();
        for (int launch = 1; launch <= 2; launch++) {
            try {
                EmbeddingHelper.initializeServer(2);
                EmbeddingHelper.readiness().get(3, TimeUnit.MINUTES);
                check(EmbeddingHelper.isReady(), "Real helper readiness failed");
                var oak = EmbeddingHelper.getEmbedding("block minecraft:oak_log. minecraft oak log.");
                var lava = EmbeddingHelper.getEmbedding("block minecraft:lava. minecraft lava.");
                Vec384f a = oak.get(30, TimeUnit.SECONDS), b = lava.get(30, TimeUnit.SECONDS);
                valid(a); valid(b);
                check(a.dot(b) < .999f, "Different real descriptors produced identical embeddings");
                a.mul(0);
                valid(EmbeddingHelper.getEmbedding("block minecraft:oak_log. minecraft oak log.").get(5, TimeUnit.SECONDS));
                System.out.println("Real helper lifecycle " + launch + " passed: full native 768, normalized finite tail, distinct descriptors, cache isolation");
            } finally { EmbeddingHelper.shutdown(); }
            check(!EmbeddingHelper.isReady() && EmbeddingHelper.getCacheSize() == 0, "Shutdown retained readiness/cache");
            check(EmbeddingHelper.getEmbedding("stopped").isCompletedExceptionally(), "Stopped helper accepts inference");
            check(Thread.currentThread().getContextClassLoader() == callerLoader, "Runtime loader leaked to launch caller");
        }
        System.out.println("REAL IntelliJ-resource launch inference passed; JNI=" + LlamaModel.getLlamaCppBuildInfo()
                + ", SHA256=" + PinnedEmbeddingModel.SHA256 + ", dimensions=768, no HTTP service; cwd=" + Path.of("").toAbsolutePath());
    }

    private static void valid(Vec384f vector) {
        EmbeddingSpace.requireCurrent(vector);
        check(vector.data().length == 768 && Math.abs(vector.length() - 1) < 1e-5, "Invalid native shape/norm");
        for (float coordinate : vector.data()) check(Float.isFinite(coordinate), "Nonfinite native coordinate");
        check(vector.data()[767] != 0, "Native tail missing");
    }
}
