package io.github.mysticism.embedding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh process with fake kernel CPU information, never fake native code or vectors. */
public final class EmbeddedCpuBootstrapProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("build/embedding-cpu-test").toAbsolutePath(); Files.createDirectories(root);
        Path attempt = Files.createTempDirectory(root, "case-").resolve("must-not-extract");
        System.setProperty(EmbeddedNativeRuntime.DIRECTORY, attempt.toString());
        String flags = "pni ssse3 sse4_1 sse4_2 xsave avx avx2 fma f16c bmi2 abm";
        String good = "processor: 0\nflags: " + flags + "\n";
        String fixture = switch (args[0]) {
            case "no-avx" -> "processor: 0\nflags: pni ssse3 sse4_1 sse4_2 xsave\n";
            case "no-fma" -> good.replace(" fma ", " ");
            case "mixed" -> good + "\nprocessor: 1\nflags: " + flags.replace("avx2", "") + "\n";
            case "unreadable" -> "";
            default -> throw new IllegalArgumentException("Unknown fixture");
        };
        String code = args[0].equals("unreadable") ? "CPU_FEATURES_UNAVAILABLE" : "UNSUPPORTED_CPU";
        EmbeddedNativeRuntime.CpuInfo cpuInfo = () -> {
            if (args[0].equals("unreadable")) throw new IOException("fixture /proc/cpuinfo denied");
            return fixture;
        };
        try { EmbeddedNativeRuntime.prepare(cpuInfo); throw new AssertionError("Unsupported CPU entered JNI"); }
        catch (IOException expected) {
            if (!expected.getMessage().contains("[" + code + "]")) throw new AssertionError("Wrong CPU diagnostic", expected);
            try { EmbeddedNativeRuntime.prepare(() -> { throw new AssertionError("Terminal failure retried CPU/native bootstrap"); }); throw new AssertionError("Failure revived"); }
            catch (IOException repeated) { if (repeated != expected) throw new AssertionError("Terminal cause lost", repeated); }
            if (EmbeddedNativeRuntime.loadedLibrary() != null || Files.exists(attempt) || System.getProperty("net.ladenthin.llama.lib.path") != null)
                throw new AssertionError("Unsupported CPU extracted/loaded JNI or changed native loader state");
            Path maps = Path.of("/proc/self/maps");
            if (Files.isRegularFile(maps) && Files.readAllLines(maps).stream().anyMatch(line -> line.contains("libjllama.so")))
                throw new AssertionError("JNI was mapped before CPU rejection");
            System.out.println("Fresh JVM CPU rejection before extraction/JNI: " + expected.getMessage());
        }
    }
}
