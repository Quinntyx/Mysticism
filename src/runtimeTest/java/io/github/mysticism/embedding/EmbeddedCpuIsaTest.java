package io.github.mysticism.embedding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** No JNI capability calls: those are compile-time constants in the pinned x64 payload. */
public final class EmbeddedCpuIsaTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static IOException rejects(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IOException error) { check(error.getMessage().contains("[" + code + "]"), "Wrong diagnostic: " + error); return error; }
    }
    static String processor(int id, List<String> flags) {
        return "processor\t: " + id + "\nmodel name\t: fixture, not a CPUID heuristic\nflags\t\t: " + String.join(" ", flags) + "\n";
    }
    public static void main(String[] args) throws Exception {
        var linux = EmbeddedNativeRuntime.select("Linux", "amd64", 21, "64");
        // Explicit fixture, independent of the production requirements list: changing that list
        // cannot silently turn the unsupported-CPU tests into self-fulfilling coverage.
        var supported = List.of("pni", "ssse3", "sse4_1", "sse4_2", "xsave", "avx", "avx2", "fma", "f16c", "bmi2", "abm");
        String good = processor(0, supported);
        EmbeddedNativeRuntime.validateCpu(linux, () -> good); checks++;
        EmbeddedNativeRuntime.validateCpu(linux, () -> good + "\n \t\n" + processor(1, supported)); checks++;
        EmbeddedNativeRuntime.validateCpu(linux, () -> good.replace("\n", "\r\n").replace(" ", "\t")); checks++;
        for (String required : supported) {
            List<String> without = supported.stream().filter(flag -> !flag.equals(required)).toList();
            IOException failure = rejects("UNSUPPORTED_CPU", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> processor(0, without)));
            check(failure.getMessage().contains("[" + required + "]"), "Missing feature not identified: " + required);
            check(failure.getMessage().contains("restart Minecraft") && failure.getMessage().contains("VM") && failure.getMessage().contains("No ISA override"),
                    "CPU failure has no usable hardware/VM action");
            // Every online processor must pass: never trust only the first or richest flags record.
            rejects("UNSUPPORTED_CPU", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> good + "\n" + processor(1, without)));
            rejects("UNSUPPORTED_CPU", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> processor(0, without) + "\n" + processor(1, supported)));
        }
        rejects("UNSUPPORTED_CPU", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> good.replace(" avx ", " not_avx avx512 ")));
        for (String malformed : List.of("", "\n\t\n", "model name: Intel AVX2 CPU", "processor: 0\n", "processor: 0\nflags: \n", "flags: avx2\n",
                good + "\nprocessor: 1\nmodel name: fixture", good + "flags: avx2\n", good + "processor: 0\n", good.replace("processor\t: 0", "processor: unknown"),
                "x".repeat(4 * 1024 * 1024 + 1)))
            rejects("CPU_FEATURES_UNAVAILABLE", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> malformed));
        rejects("CPU_FEATURES_UNAVAILABLE", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> null));
        IOException inaccessible = new IOException("fixture /proc not mounted");
        check(rejects("CPU_FEATURES_UNAVAILABLE", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> { throw inaccessible; })).getCause() == inaccessible,
                "Lost proc IO cause");
        var denied = new SecurityException("fixture proc access denied");
        check(rejects("CPU_FEATURES_UNAVAILABLE", () -> EmbeddedNativeRuntime.validateCpu(linux, () -> { throw denied; })).getCause() == denied,
                "Lost proc security cause");
        AtomicInteger reads = new AtomicInteger();
        for (var platform : EmbeddedNativeRuntime.PLATFORMS) {
            if (platform.id().equals(linux.id())) continue;
            EmbeddedNativeRuntime.validateCpu(platform, () -> { reads.incrementAndGet(); throw new AssertionError("Non-Linux-x64 attempted proc probe"); });
        }
        check(reads.get() == 0, "Changed another native platform's policy");
        // Exercise the REAL prepare path in fresh JVMs, with observable extraction/loader state.
        for (String fixture : List.of("no-avx", "no-fma", "mixed", "unreadable")) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-ea",
                    "-Dos.name=Linux", "-Dos.arch=amd64", "-cp", System.getProperty("java.class.path"), EmbeddedCpuBootstrapProbe.class.getName(), fixture).inheritIO().start();
            try {
                check(child.waitFor(10, TimeUnit.SECONDS), "CPU preflight hung: " + fixture);
                check(child.exitValue() == 0, "CPU preflight crashed/failed: " + fixture + "; exit=" + child.exitValue());
            } finally { if (child.isAlive()) child.destroyForcibly(); }
        }
        System.out.println("EmbeddedCpuIsaTest passed: " + checks + " checks; unsupported CPUs rejected before JNI/weights");
    }
}
