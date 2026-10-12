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
                        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
                        public void checkReady() throws Exception {
                            record("probe");
                            throw new IllegalStateException("EXPECTED_RESOURCE_PROBE: native inference intentionally absent");
                        }
                        public Vec384f getEmbedding(String text) {
                            throw new AssertionError("Diagnostic fixture must never invent embeddings");
                        }
                        public void close() {
                            try { record("close"); } catch (Exception error) { throw new RuntimeException(error); }
                        }
                    };
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) { child(Path.of(args[0])); return; }
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
        for (String mode : List.of("directory", "exploded", "jar")) {
            Path marker = root.resolve(mode + "-calls.txt");
            String classpath = System.getProperty("java.class.path");
            if (mode.equals("exploded")) classpath += java.io.File.pathSeparator + exploded;
            if (mode.equals("jar")) classpath += java.io.File.pathSeparator + archive;
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-Dmysticism.embedding.resourceCache=" + cache, "-Dfixture.marker=" + marker,
                    "-Dmysticism.embedding.endpoint=http://127.0.0.1:1/"));
            if (mode.equals("directory")) command.add("-Dmysticism.embedding.bundle=" + bundle);
            command.addAll(List.of("-cp", classpath, EmbeddedIntellijLaunchTest.class.getName(), marker.toString()));
            Process child = new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start();
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Child launch timeout: " + mode);
            }
            check(child.exitValue() == 0, "Child launch regression failed: " + mode);
            check(Files.readString(marker).equals("open\nprobe\nclose\n"), "Startup failure must close runtime exactly once: " + mode);
        }
        System.out.println("EmbeddedIntellijLaunchTest: directory/exploded/jar child launches passed from unrelated cwd; no inference/game launch");
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
            check(Files.readString(marker).equals("open\nprobe\nclose\n"), "Runtime factory invoked and cleaned up");
            check(Thread.currentThread().getContextClassLoader() == previous, "Runtime loader must not leak to Minecraft caller");
        } finally { EmbeddingHelper.shutdown(); }
    }
}
