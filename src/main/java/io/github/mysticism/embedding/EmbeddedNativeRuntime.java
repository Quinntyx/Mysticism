package io.github.mysticism.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.ladenthin.llama.LlamaModel;

/** Worker-only, process-wide bootstrap of the bundled CPU runtime, never an installed jllama.
 * Direct loading preserves the OS linker's cause (the upstream search loader discards it).
 * The upstream initializer is then pointed at this exact already-loaded library, with its
 * recursive cleanup confined to a separate empty private directory.
 */
final class EmbeddedNativeRuntime {
    static final String BUILD = "b10682-5ea1b124e";
    static final String DIRECTORY = "mysticism.embedding.nativeDirectory";
    record Platform(String os, String arch, String filename, long bytes, String sha256) {
        String resource() { return "/net/ladenthin/llama/" + os + "/" + arch + "/" + filename; }
        String id() { return os + "/" + arch; }
        String requirements() {
            return switch (os) {
                case "Linux" -> "Use a glibc desktop (not Alpine/musl) with libstdc++.so.6 and libgomp.so.1 available to the Minecraft JVM.";
                case "Windows" -> "Use the matching 64-bit JVM and Microsoft Visual C++ 2015-2022 runtime including VCOMP140.DLL (OpenMP).";
                default -> "Use a native Apple Silicon Java 21 JVM, not an Intel/Rosetta JVM.";
            };
        }
    }
    // Exact payloads in the released net.ladenthin:llama:5.1.0 jar, not optional GPU jars.
    static final List<Platform> PLATFORMS = List.of(
            new Platform("Linux", "x86_64", "libjllama.so", 19163952L, "d3747e2bbb2150c2ed8dfdd771ed0b0da5abce4c3fe28f031cafdaa3e1acb7a4"),
            new Platform("Linux", "aarch64", "libjllama.so", 17799928L, "1781a217759b649c09f0a4d460a41faedf23fc788f6e7227000dbe8827052dc1"),
            new Platform("Mac", "aarch64", "libjllama.dylib", 17868080L, "889cc12882ab14eb819456fbca3aa26291348ef8c11ce99b9a9c738a43f4fd21"),
            new Platform("Windows", "x86_64", "jllama.dll", 12889600L, "891e3b7891467cf37b47c308123cd24905d5134b32eac8136d2dafa7fff36a52"),
            new Platform("Windows", "aarch64", "jllama.dll", 14706176L, "ce8c90590a95adb84ad7086fe0f5334c6cf86b6acf66fbf59c8d4b53ba6b2464"));
    // This exact Linux x64 payload is NOT a baseline-x86 or dynamically dispatched build.
    // In particular its ggml_cpu_has_* methods return compile-time constants, not CPUID.
    // Linux's avx flag means the kernel has enabled XSAVE/YMM state, unlike raw CPUID.
    // abm is Linux's LZCNT flag; BMI2/LZCNT are also present in the pinned machine code.
    static final List<String> LINUX_X64_FLAGS = List.of(
            "pni", "ssse3", "sse4_1", "sse4_2", "xsave", "avx", "avx2", "fma", "f16c", "bmi2", "abm");
    private static final int MAX_CPU_INFO_BYTES = 4 * 1024 * 1024;
    private static String buildInfo;
    private static IOException terminalFailure;
    private static Path library;
    private EmbeddedNativeRuntime() {}

    static Platform select(String osName, String osArch, int javaFeature, String dataModel) throws IOException {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (javaFeature < 21 || !"64".equals(dataModel))
            throw new IOException("Embedded CPU runtime [JVM]: select a 64-bit Java 21+ Minecraft/IntelliJ runtime; Java=" + javaFeature + ", bits=" + dataModel);
        os = os.startsWith("windows") ? "Windows" : os.equals("linux") ? "Linux" :
                os.equals("mac os x") || os.equals("macos") || os.equals("darwin") ? "Mac" : osName;
        arch = switch (arch) { case "amd64", "x86_64", "x64" -> "x86_64"; case "arm64", "aarch64" -> "aarch64"; default -> arch; };
        for (Platform platform : PLATFORMS) if (platform.os.equals(os) && platform.arch.equals(arch)) return platform;
        throw new IOException("Embedded CPU runtime [UNSUPPORTED_PLATFORM]: os.name=" + osName + ", os.arch=" + osArch +
                "; bundled desktops: Linux x86_64/aarch64 (glibc), Windows x86_64/aarch64, macOS aarch64. " +
                "Intel macOS is not bundled; Apple Silicon must use an arm64 JVM rather than Rosetta. No server or alternate model fallback.");
    }

    static synchronized String prepare() throws IOException {
        return prepare(EmbeddedNativeRuntime::linuxCpuInfo);
    }

    // Package-private reader seam for fresh-JVM regressions, not a user-configurable ISA bypass.
    @FunctionalInterface interface CpuInfo { String read() throws IOException; }
    static synchronized String prepare(CpuInfo cpuInfo) throws IOException {
        if (terminalFailure != null) throw terminalFailure;
        if (buildInfo != null) return buildInfo;
        interrupted();
        Platform platform = select(System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"),
                Runtime.version().feature(), System.getProperty("sun.arch.data.model", "unknown"));
        // These would let the upstream loader silently replace our pinned CPU ABI.
        for (String property : List.of("net.ladenthin.llama.lib.path", "net.ladenthin.llama.osinfo.architecture"))
            if (System.getProperty(property) != null)
                throw failure("CONFIGURATION", platform, "Remove -D" + property + "; the bundled CPU runtime owns native selection.", null);
        String backend = System.getProperty("net.ladenthin.llama.backend", "cpu");
        if (!backend.equals("cpu") && !backend.equals("default"))
            throw failure("CONFIGURATION", platform, "Remove -Dnet.ladenthin.llama.backend=" + backend + "; this backend requires the bundled CPU runtime.", null);
        Path root = null;
        try {
            // Must precede extraction, loader properties, System.load/JNI_OnLoad and asset IO.
            // A SIGILL cannot be caught as a Java LinkageError after entering the library.
            validateCpu(platform, cpuInfo);
            String configured = System.getProperty(DIRECTORY, System.getProperty("net.ladenthin.llama.tmpdir"));
            root = configured == null ? Path.of(System.getProperty("user.home"), ".cache", "mysticism", "native") : Path.of(configured);
            Path directory = privateDirectory(root);
            library = extract(platform, directory, EmbeddedNativeRuntime.class::getResourceAsStream);
            interrupted();
            // Register before loading; mapped DLLs must remain until JVM exit, never lifecycle close.
            library.toFile().deleteOnExit();
            Path scratch = Files.createDirectory(directory.resolve("loader-scratch"));
            scratch.toFile().deleteOnExit();
            // JNI_OnLoad resolves LlamaModel, whose static initializer invokes LlamaLoader.
            // Configure BEFORE System.load, not merely before getLlamaCppBuildInfo, or that
            // recursive initialization can extract/load a second library into system temp.
            System.setProperty("net.ladenthin.llama.lib.path", directory.toString());
            System.setProperty("net.ladenthin.llama.tmpdir", scratch.toString());
            System.setProperty("net.ladenthin.llama.backend", "cpu");
            load(platform, library, System::load);
            String actual = LlamaModel.getLlamaCppBuildInfo();
            if (!BUILD.equals(actual)) throw failure("ABI", platform, "Expected JNI build " + BUILD + ", got " + actual + "; restart with the pinned mod/library, not an installed jllama.", null);
            buildInfo = actual;
            return actual;
        } catch (CancellationException cancelled) { throw cancelled;
        } catch (IOException error) {
            // Once a library is mapped / LlamaModel initialized, retries cannot safely replace it.
            // Retain an actionable error rather than a later NoClassDefFoundError.
            terminalFailure = error.getMessage().startsWith("Embedded CPU runtime") ? error :
                    failure("EXTRACTION", platform, "Cannot provision a private native directory. Set -D" + DIRECTORY +
                            " to a writable local filesystem that permits native execution; root=" + root + "; " + error.getMessage(), error);
            throw terminalFailure;
        } catch (java.nio.file.InvalidPathException | SecurityException error) {
            terminalFailure = failure("EXTRACTION", platform, "Invalid or inaccessible native directory/property. Set -D" + DIRECTORY +
                    " to a writable execution-enabled local directory allowed by the JVM security policy; " + error.getMessage(), error);
            throw terminalFailure;
        } catch (LinkageError | RuntimeException error) {
            terminalFailure = failure("INITIALIZATION", platform, "JNI initialization failed; restart Minecraft after fixing the runtime. " + error, error);
            throw terminalFailure;
        }
    }

    private static String linuxCpuInfo() throws IOException {
        try (InputStream input = Files.newInputStream(Path.of("/proc/cpuinfo"))) {
            // proc files report size zero; bound the actual stream, not Files.size().
            byte[] bytes = input.readNBytes(MAX_CPU_INFO_BYTES + 1);
            if (bytes.length > MAX_CPU_INFO_BYTES) throw new IOException("/proc/cpuinfo exceeds the 4 MiB safety bound");
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    static void validateCpu(Platform platform, CpuInfo cpuInfo) throws IOException {
        if (!platform.os.equals("Linux") || !platform.arch.equals("x86_64")) return;
        String info;
        try { info = cpuInfo.read(); }
        catch (IOException | SecurityException error) {
            throw cpuInfoFailure(platform, "Cannot read /proc/cpuinfo: " + error.getMessage(), error);
        }
        if (info == null || info.isBlank() || info.length() > MAX_CPU_INFO_BYTES)
            throw cpuInfoFailure(platform, "Empty or oversized /proc/cpuinfo", null);
        int processors = 0;
        // Do not use \\R\\R: regex backtracking can split a single CRLF into two separators.
        for (String stanza : info.split("\\r?\\n[ \\t]*\\r?\\n")) {
            if (stanza.isBlank()) continue;
            String processor = null;
            Set<String> flags = null;
            for (String line : stanza.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).strip(), value = line.substring(colon + 1).strip();
                if (key.equals("processor")) {
                    if (processor != null || !value.matches("[0-9]+"))
                        throw cpuInfoFailure(platform, "Malformed/duplicate processor record", null);
                    processor = value;
                } else if (key.equals("flags")) {
                    if (flags != null || value.isEmpty()) throw cpuInfoFailure(platform, "Empty/duplicate CPU flags record", null);
                    flags = Set.copyOf(List.of(value.split("\\s+")));
                }
            }
            if (processor == null || flags == null || flags.isEmpty())
                throw cpuInfoFailure(platform, "Incomplete processor/flags record", null);
            processors++;
            Set<String> available = flags;
            var missing = LINUX_X64_FLAGS.stream().filter(flag -> !available.contains(flag)).toList();
            if (!missing.isEmpty()) throw failure("UNSUPPORTED_CPU", platform,
                    "Pinned llama-5.1.0 CPU payload requires OS-enabled SSE3/SSSE3/SSE4, AVX/AVX2, FMA, F16C, BMI2 and LZCNT (XSAVE/YMM state). " +
                    "Processor " + processor + " lacks Linux flags " + missing + ". Use a CPU/VM exposing these features on every online CPU " +
                    "(check VM CPUID masks and kernel AVX support), then restart Minecraft. No ISA override, alternate model or external-server fallback.", null);
        }
        if (processors == 0) throw cpuInfoFailure(platform, "No online processor records", null);
    }

    private static IOException cpuInfoFailure(Platform platform, String detail, Throwable cause) {
        return failure("CPU_FEATURES_UNAVAILABLE", platform, detail + ". Native execution is refused before JNI because this pinned x86 payload requires AVX2/FMA/F16C/BMI2/LZCNT. " +
                "Run on a Linux desktop with readable kernel /proc/cpuinfo and OS-enabled CPU flags; fix the proc mount/security policy or VM CPU configuration and restart Minecraft. " +
                "Do not bypass the check or rely on the library's compile-time capability report.", cause);
    }

    static Path privateDirectory(Path root) throws IOException {
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root.toAbsolutePath(), "mysticism-cpu-");
        directory.toFile().deleteOnExit();
        return directory;
    }
    @FunctionalInterface interface Resources { InputStream open(String resource) throws IOException; }
    @FunctionalInterface interface Linker { void load(String absolutePath); }

    static Path extract(Platform platform, Path directory, Resources resources) throws IOException {
        Path target = directory.resolve(platform.filename);
        boolean created = false, verified = false;
        try (InputStream input = resources.open(platform.resource())) {
            if (input == null) throw failure("MISSING_RESOURCE", platform, "Missing " + platform.resource() +
                    "; use the remapped production jar with its nested llama-5.1.0.jar, or refresh the IntelliJ Gradle runtime classpath.", null);
            MessageDigest hash;
            try { hash = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            try (OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                created = true;
                byte[] buffer = new byte[65536]; long total = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    interrupted(); total += n;
                    if (total > platform.bytes) throw failure("INTEGRITY", platform, "Oversized bundled CPU payload; replace the damaged/mismatched llama-5.1.0.jar.", null);
                    hash.update(buffer, 0, n); output.write(buffer, 0, n);
                }
                if (total != platform.bytes || !platform.sha256.equals(HexFormat.of().formatHex(hash.digest())))
                    throw failure("INTEGRITY", platform, "Bundled CPU payload length/SHA256 mismatch; replace the damaged/mismatched llama-5.1.0.jar.", null);
            }
            verified = true;
        } finally {
            if (created && !verified) Files.deleteIfExists(target); // only our unpublished extraction
        }
        return target.toAbsolutePath();
    }
    static void load(Platform platform, Path path, Linker linker) throws IOException {
        try { linker.load(path.toAbsolutePath().toString()); }
        catch (LinkageError | SecurityException error) {
            throw failure("NATIVE_LINK", platform, "Cannot load " + path + ": " + error.getMessage() + ". " + platform.requirements() +
                    " If blocked by noexec/permissions, set -D" + DIRECTORY +
                    " to a writable execution-enabled local directory in the IntelliJ Minecraft VM options. Restart Minecraft after fixing it. No external embedding server is required.", error);
        }
    }
    private static IOException failure(String code, Platform platform, String action, Throwable cause) {
        return new IOException("Embedded CPU runtime [" + code + "] " + platform.id() + ": " + action, cause);
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Embedded CPU bootstrap cancelled");
    }
    static synchronized Path loadedLibrary() { return library; }
}
