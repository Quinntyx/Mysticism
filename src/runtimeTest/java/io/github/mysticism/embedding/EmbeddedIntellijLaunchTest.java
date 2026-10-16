package io.github.mysticism.embedding;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;

/** Launches child JVMs from an unrelated run cwd with a real SPI jar and resource bundle.
 * The diagnostic factory deliberately fails readiness and NEVER generates model vectors.
 * This proves resource/classloader/async launch wiring, not native inference or gameplay.
 */
public final class EmbeddedIntellijLaunchTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final String SOURCE = """
            package launchfixture;
            import io.github.mysticism.embedding.*;
            import io.github.mysticism.vector.*;
            import java.nio.file.*;
            public final class ProbeFactory implements EmbeddedRuntimeFactory {
                public ProbeFactory() {
                    if (Thread.currentThread().getContextClassLoader().getResource("runtime-marker.txt") == null)
                        throw new AssertionError("Factory initialization cannot see runtime dependency resources");
                }
                private static void record(String message) throws Exception {
                    if (Thread.currentThread().getContextClassLoader().getResource("runtime-marker.txt") == null)
                        throw new AssertionError("Runtime dependency resources missing from TCCL");
                    Files.writeString(Path.of(System.getProperty("fixture.marker")), message + "\\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                public EmbeddingProvider open(EmbeddedRuntimeResources.Bundle bundle) throws Exception {
                    if (!bundle.require("model").isAbsolute()) throw new AssertionError("Relative model path");
                    if (!Files.readString(bundle.require("model")).equals("launch fixture, NOT model weights"))
                        throw new AssertionError("Wrong model/resource origin");
                    if (!Thread.currentThread().getName().equals("Mysticism-EmbedWorker"))
                        throw new AssertionError("Resource/model startup ran on Minecraft caller thread");
                    record("open");
                    return new EmbeddingProvider() {
                        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
                        public void checkReady() throws Exception {
                            record("probe");
                            if (Boolean.getBoolean("fixture.cancel")) {
                                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                                while (!closed.get() && System.nanoTime() < end) {
                                    try { Thread.sleep(5); } catch (InterruptedException ignored) { /* native-like load */ }
                                }
                                if (!closed.get()) throw new AssertionError("Startup delegate was not cancelled");
                                record("cancelled");
                            }
                            throw new IllegalStateException("EXPECTED_RESOURCE_PROBE: native inference intentionally absent");
                        }
                        public Vec384f getEmbedding(String text) {
                            throw new AssertionError("Diagnostic fixture must never invent embeddings");
                        }
                        public void requestStop() { closed.set(true); }
                        public void close() {
                            try { record("close"); closed.set(true); } catch (Exception error) { throw new RuntimeException(error); }
                        }
                    };
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (Boolean.getBoolean("fixture.cancel")) cancelChild(Path.of(args[0]));
            else child(Path.of(args[0]));
            return;
        }
        String prior = System.getProperty(EmbeddingProviders.PROVIDER_PROPERTY);
        try {
            System.clearProperty(EmbeddingProviders.PROVIDER_PROPERTY);
            try (var provider = EmbeddingProviders.configured()) {
                check(provider instanceof EmbeddedEmbeddingProvider, "Default launch must not require an HTTP service");
                check(provider.profile().equals(EmbeddingProfile.current()), "Native v2 profile is retained");
            }
            System.setProperty(EmbeddingProviders.PROVIDER_PROPERTY, "http");
            try (var provider = EmbeddingProviders.configured()) {
                check(provider instanceof EmbeddingService, "HTTP is explicitly opt-in, not an embedded fallback");
            }
            System.setProperty(EmbeddingProviders.PROVIDER_PROPERTY, "unknown");
            EmbeddedRuntimeResourcesTest.rejects(EmbeddingProviders::configured, "Unknown provider must fail closed");
        } finally { EmbeddedRuntimeResourcesTest.restore(EmbeddingProviders.PROVIDER_PROPERTY, prior); }

        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "embedded-ide-test-");
        Path sources = Files.createDirectories(root.resolve("sources/launchfixture"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(sources.resolve("ProbeFactory.java"), SOURCE);
        var compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK 21 required for runtime jar fixture");
        int result = compiler.run(null, System.out, System.err, "--release", "21", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(), sources.resolve("ProbeFactory.java").toString());
        check(result == 0, "Diagnostic SPI fixture must compile");
        Path runtimeJar = root.resolve("native runtime fixture.jar");
        var entries = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        entries.put("META-INF/services/" + EmbeddedRuntimeFactory.class.getName(), "launchfixture.ProbeFactory\n".getBytes(StandardCharsets.UTF_8));
        entries.put("runtime-marker.txt", "runtime dependency resource".getBytes(StandardCharsets.UTF_8));
        EmbeddedRuntimeResourcesTest.jar(runtimeJar, entries);
        byte[] model = "launch fixture, NOT model weights".getBytes(StandardCharsets.UTF_8);
        byte[] jarBytes = Files.readAllBytes(runtimeJar);
        String manifest = EmbeddedRuntimeResourcesTest.manifest(model) + "asset.runtime=runtime.jar\nsha256.runtime="
                + EmbeddedRuntimeResourcesTest.hash(jarBytes) + "\nruntimeJars=runtime\n";
        Path bundle = Files.createDirectories(root.resolve("provisioned bundle"));
        Files.writeString(bundle.resolve("bundle.properties"), manifest);
        Files.write(bundle.resolve("model.gguf"), model);
        Files.write(bundle.resolve("runtime.jar"), jarBytes);
        Path archive = root.resolve("mod bundle.jar");
        Map<String, byte[]> assets = Map.of(EmbeddedRuntimeResources.RESOURCE, manifest.getBytes(StandardCharsets.ISO_8859_1),
                "META-INF/mysticism/embedding/model.gguf", model,
                "META-INF/mysticism/embedding/runtime.jar", jarBytes);
        EmbeddedRuntimeResourcesTest.jar(archive, assets);
        Path exploded = Files.createDirectories(root.resolve("IDE resources"));
        for (var entry : assets.entrySet()) {
            Path file = exploded.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
        }
        Path cwd = Files.createDirectories(root.resolve("unrelated Minecraft run directory"));
        Path cache = Files.createDirectories(root.resolve("native resource cache"));
        for (String mode : List.of("directory", "exploded", "jar", "directory-cancel")) {
            Path marker = root.resolve(mode + "-calls.txt");
            String classpath = System.getProperty("java.class.path");
            if (mode.equals("exploded")) classpath += java.io.File.pathSeparator + exploded;
            if (mode.equals("jar")) classpath += java.io.File.pathSeparator + archive;
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-Dmysticism.embedding.resourceCache=" + cache, "-Dfixture.marker=" + marker,
                    "-Dmysticism.embedding.endpoint=http://127.0.0.1:1/"));
            if (mode.startsWith("directory")) command.add("-Dmysticism.embedding.bundle=" + bundle);
            if (mode.endsWith("cancel")) command.add("-Dfixture.cancel=true");
            command.addAll(List.of("-cp", classpath, EmbeddedIntellijLaunchTest.class.getName(), marker.toString()));
            Process child = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Child launch timeout: " + mode);
            }
            check(child.exitValue() == 0, "Child launch regression failed: " + mode);
            String expected = mode.endsWith("cancel") ? "open\nprobe\ncancelled\nclose\n" : "open\nprobe\nclose\n";
            check(Files.readString(marker).equals(expected), "Startup failure/cancellation must close runtime exactly once: " + mode);
        }
        System.out.println("EmbeddedIntellijLaunchTest: directory/exploded/jar child launches passed from unrelated cwd; no inference/game launch");
    }
    private static void cancelChild(Path marker) throws Exception {
        EmbeddingHelper.initializeServer(1);
        var readiness = EmbeddingHelper.readiness();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(marker) || !Files.readString(marker).contains("probe\n")) && System.nanoTime() < end)
                Thread.sleep(5);
            check(Files.exists(marker) && Files.readString(marker).contains("probe\n"), "Startup did not enter delegate");
            EmbeddingHelper.shutdown();
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.readString(marker).contains("close\n") && System.nanoTime() < end) Thread.sleep(5);
            check(Files.readString(marker).equals("open\nprobe\ncancelled\nclose\n"), "Bridge shutdown did not promptly cancel native-like startup exactly once");
            check(readiness.isCompletedExceptionally(), "Cancelled startup revived readiness");
            check(!EmbeddingHelper.isReady(), "Shutdown provider remains ready");
        } finally { EmbeddingHelper.shutdown(); }
    }
    private static void child(Path marker) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            EmbeddingHelper.initializeServer(1);
            try {
                EmbeddingHelper.readiness().get(10, TimeUnit.SECONDS);
                throw new AssertionError("A resource-only fixture must not be presented as model readiness");
            } catch (ExecutionException expected) {
                check(expected.getCause().getMessage().contains("EXPECTED_RESOURCE_PROBE"), "Wrong failure: " + expected);
            }
            check(!EmbeddingHelper.isReady(), "No synthetic readiness after inference failure");
            // Failure delivery is intentionally independent of native disposal. Wait for
            // actual teardown, rather than assuming readiness failure ran close inline.
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(marker) || !Files.readString(marker).contains("close\n")) && System.nanoTime() < end) Thread.sleep(5);
            check(Files.readString(marker).equals("open\nprobe\nclose\n"), "Runtime factory invoked and cleaned up");
            check(Thread.currentThread().getContextClassLoader() == previous, "Runtime loader must not leak to Minecraft caller");
        } finally { EmbeddingHelper.shutdown(); }
    }
}
