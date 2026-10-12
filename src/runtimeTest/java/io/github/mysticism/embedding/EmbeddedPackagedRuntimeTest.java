package io.github.mysticism.embedding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Executes bundled CPU ABI from the REAL production artifact in a fresh isolated JVM. */
public final class EmbeddedPackagedRuntimeTest {
    public static void main(String[] args) throws Exception {
        String artifact = System.getProperty("mysticism.selftest.productionJar");
        if (artifact == null) throw new AssertionError("Production-jar probe not wired to remapJar; do not skip packaging execution");
        Path jar = Path.of(artifact).toRealPath();
        Path root = Path.of("build/embedding-packaged-test").toAbsolutePath(); Files.createDirectories(root);
        Path isolated = Files.createTempDirectory(root, "case-");
        Path nested = Files.createDirectory(isolated.resolve("nested"));
        Path harness = Files.createDirectory(isolated.resolve("harness"));
        Path probe = harness.resolve(EmbeddedPackagedProbe.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(probe.getParent());
        try (var input = EmbeddedPackagedRuntimeTest.class.getResourceAsStream("EmbeddedPackagedProbe.class")) {
            if (input == null) throw new AssertionError("Missing probe bytecode");
            Files.copy(input, probe);
        }
        List<String> classpath = new ArrayList<>(List.of(harness.toString(), jar.toString()));
        // Do not reuse the Gradle/runtime test classpath: that would mask a missing nested dependency.
        try (var zip = new ZipFile(jar.toFile())) {
            for (String name : List.of("llama-5.1.0.jar", "jackson-databind-2.22.2.jar", "jackson-core-2.22.2.jar",
                    "jackson-annotations-2.22.jar", "jspecify-1.0.1.jar")) {
                var entry = zip.getEntry("META-INF/jars/" + name);
                if (entry == null) throw new AssertionError("Missing production native/Java dependency " + name);
                Path extracted = nested.resolve(name);
                try (var input = zip.getInputStream(entry)) { Files.copy(input, extracted); }
                classpath.add(extracted.toString());
            }
        }
        // SLF4J API is deliberately provided by Minecraft rather than duplicated in the mod.
        classpath.add(Path.of(org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-ea",
                "-Dmysticism.embedding.nativeDirectory=" + isolated.resolve("native"),
                "-cp", String.join(java.io.File.pathSeparator, classpath), EmbeddedPackagedProbe.class.getName(),
                jar.toString(), nested.toString()).inheritIO().start();
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("Isolated packaged JNI bootstrap hung");
            if (child.exitValue() != 0) throw new AssertionError("Isolated packaged JNI bootstrap failed: " + child.exitValue());
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        System.out.println("EmbeddedPackagedRuntimeTest passed: isolated remapped jar + five nested runtime jars; no weights");
    }
}
