package io.github.mysticism.embedding;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** The actual built-in adapter is selected from an unrelated Minecraft cwd.
 * No executable weights: validates path resolution and fail-closed integrity, not forward inference.
 */
public final class EmbeddedBuiltinLaunchTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length == 1) { child(args[0]); return; }
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "embedded-builtin-launch-");
        Path cwd = Files.createDirectories(root.resolve("unrelated Minecraft run directory"));
        Path model = root.resolve("model with spaces.gguf");
        Files.writeString(model, "NOT executable weights");
        Path bundle = Files.createDirectories(root.resolve("resource bundle"));
        byte[] bytes = Files.readAllBytes(model);
        Files.write(bundle.resolve("model.gguf"), bytes);
        Files.writeString(bundle.resolve("bundle.properties"), EmbeddedRuntimeResourcesTest.manifest(bytes));
        for (String mode : List.of("missing", "corrupt", "relative", "bundle", "invalid-bundle")) {
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-Dmysticism.embedding.download=false", "-Dmysticism.embedding.endpoint=not-an-http-url"));
            command.add("-Dmysticism.embedding.modelPath=" + switch (mode) {
                case "relative" -> "model with spaces.gguf";
                case "missing" -> root.resolve("missing.gguf").toString();
                default -> model.toString();
            });
            if (mode.endsWith("bundle")) command.add("-Dmysticism.embedding.bundle=" +
                    (mode.equals("bundle") ? bundle : root.resolve("missing bundle")));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), EmbeddedBuiltinLaunchTest.class.getName(), mode));
            Process child = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("Child timeout: " + mode); }
            check(child.exitValue() == 0, "Built-in launch failed: " + mode);
        }
        check(!Files.exists(root.resolve("missing.gguf")), "Missing explicit weights triggered provisioning");
        check(Files.readString(model).equals("NOT executable weights"), "Corrupt/user model was overwritten");
        System.out.println("EmbeddedBuiltinLaunchTest passed: 5 isolated built-in launches from unrelated cwd; NO model forward pass");
    }
    private static void child(String mode) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var provider = EmbeddingProviders.configured();
             var engine = new AsyncEmbeddingEngine(provider, 1, 4, 4, Duration.ofSeconds(2), Duration.ofSeconds(5))) {
            check(provider instanceof EmbeddedEmbeddingProvider, "Built-in launch requires a service");
            try { engine.readiness().get(10, TimeUnit.SECONDS); throw new AssertionError("Invalid weights became ready"); }
            catch (ExecutionException expected) {
                String message = expected.getCause().getMessage();
                String required = switch (mode) {
                    case "relative" -> "must be an absolute local path";
                    case "corrupt", "bundle" -> "Pinned Nomic v2 GGUF size mismatch";
                    case "missing" -> "Pinned Nomic v2 GGUF missing";
                    default -> "missing bundle";
                };
                check(message != null && message.contains(required), "Wrong built-in failure for " + mode + ": " + expected);
            }
            check(!engine.isReady() && engine.cacheSize() == 0, "Invalid native startup produced readiness/vectors");
            check(Thread.currentThread().getContextClassLoader() == previous, "Worker loader leaked to launch caller");
        }
    }
}
