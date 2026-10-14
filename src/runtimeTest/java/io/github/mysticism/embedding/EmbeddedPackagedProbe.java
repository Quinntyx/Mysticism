package io.github.mysticism.embedding;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs with ONLY a copied probe class, remapped mod, extracted nested jars and MC's SLF4J API. */
public final class EmbeddedPackagedProbe {
    public static void main(String[] args) throws Exception {
        Path expectedJar = Path.of(args[0]).toRealPath();
        for (Class<?> bundled : new Class<?>[] {EmbeddedNativeRuntime.class, net.ladenthin.llama.LlamaModel.class,
                com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.core.JsonFactory.class,
                com.fasterxml.jackson.annotation.JsonProperty.class}) {
            Path origin = Path.of(bundled.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (bundled == EmbeddedNativeRuntime.class ? !origin.equals(expectedJar) : !origin.getParent().equals(Path.of(args[1]).toRealPath()))
                throw new AssertionError("Unpackaged classpath leaked into probe: " + bundled + " from " + origin);
        }
        String actual = EmbeddedNativeRuntime.prepare();
        if (!actual.equals(EmbeddedNativeRuntime.BUILD)) throw new AssertionError("Packaged JNI build mismatch");
        Path loaded = EmbeddedNativeRuntime.loadedLibrary();
        if (!Files.isRegularFile(loaded) || !loaded.startsWith(Path.of(System.getProperty(EmbeddedNativeRuntime.DIRECTORY))))
            throw new AssertionError("Packaged native extraction escaped private directory");
        if (System.getProperty("os.name").equals("Linux")) {
            var mappings = Files.readAllLines(Path.of("/proc/self/maps")).stream().filter(line -> line.contains("libjllama.so")).toList();
            if (mappings.isEmpty() || mappings.stream().anyMatch(line -> !line.contains(loaded.toString())))
                throw new AssertionError("Unverified packaged native mapping: " + mappings);
        }
        System.out.println("Isolated remapped-jar CPU JNI bootstrap passed: " + actual + "; NO game launch or model inference");
    }
}
