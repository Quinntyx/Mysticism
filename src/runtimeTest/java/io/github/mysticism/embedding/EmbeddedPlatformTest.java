package io.github.mysticism.embedding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Platform policy, pinned resource integrity, safe extraction, linker causes and worker preflight.
 * Tiny byte fixtures are never passed to JNI; no model forward pass or weights here.
 */
public final class EmbeddedPlatformTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static IOException rejects(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IOException error) { check(error.getMessage().contains(code), "Wrong diagnostic: " + error); return error; }
    }
    private static void platformPolicy() throws Exception {
        for (String arch : List.of("amd64", "x86_64", "x64", "AMD64")) {
            check(EmbeddedNativeRuntime.select("Linux", arch, 21, "64").id().equals("Linux/x86_64"), "x64 alias " + arch);
            check(EmbeddedNativeRuntime.select("Windows 11", arch, 21, "64").id().equals("Windows/x86_64"), "Windows alias");
        }
        for (String arch : List.of("aarch64", "arm64", "ARM64")) {
            check(EmbeddedNativeRuntime.select("Linux", arch, 21, "64").id().equals("Linux/aarch64"), "Linux ARM");
            check(EmbeddedNativeRuntime.select("Windows 10", arch, 21, "64").id().equals("Windows/aarch64"), "Windows ARM");
            for (String os : List.of("Mac OS X", "macOS", "Darwin"))
                check(EmbeddedNativeRuntime.select(os, arch, 21, "64").id().equals("Mac/aarch64"), "Apple ARM");
        }
        for (String os : List.of("FreeBSD", "SunOS", "Android", "Darwin"))
            rejects("UNSUPPORTED_PLATFORM", () -> EmbeddedNativeRuntime.select(os, "amd64", 21, "64"));
        rejects("UNSUPPORTED_PLATFORM", () -> EmbeddedNativeRuntime.select("Linux", "riscv64", 21, "64"));
        rejects("UNSUPPORTED_PLATFORM", () -> EmbeddedNativeRuntime.select("Windows 10", "x86", 21, "64"));
        rejects("JVM", () -> EmbeddedNativeRuntime.select("Linux", "amd64", 17, "64"));
        rejects("JVM", () -> EmbeddedNativeRuntime.select("Linux", "amd64", 21, "32"));
    }
    private static void bundledPayloads() throws Exception {
        for (var platform : EmbeddedNativeRuntime.PLATFORMS) {
            try (var input = EmbeddedPlatformTest.class.getResourceAsStream(platform.resource())) {
                check(input != null, "Missing bundled platform " + platform.id());
                byte[] data = input.readAllBytes();
                check(data.length == platform.bytes(), "Wrong bundled size " + platform.id());
                check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(platform.sha256()),
                        "Wrong pinned CPU payload " + platform.id());
            }
        }
    }
    private static void extractionAndCauses() throws Exception {
        Path root = Path.of("build/embedding-platform-test").toAbsolutePath(); Files.createDirectories(root);
        Path fixtureRoot = Files.createTempDirectory(root, "case-");
        Path sentinel = fixtureRoot.resolve("llama-user-library.so"); Files.writeString(sentinel, "do not delete");
        Path first = EmbeddedNativeRuntime.privateDirectory(fixtureRoot), second = EmbeddedNativeRuntime.privateDirectory(fixtureRoot);
        check(!first.equals(second), "Different JVM/attempts would share cleanup scope");
        byte[] data = "fixture bytes, not executable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var platform = new EmbeddedNativeRuntime.Platform("Linux", "x86_64", "libjllama.so", data.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        Path extracted = EmbeddedNativeRuntime.extract(platform, first, name -> new ByteArrayInputStream(data));
        check(extracted.isAbsolute() && Files.readString(extracted).equals(new String(data, java.nio.charset.StandardCharsets.UTF_8)), "Extraction failed");
        rejects("MISSING_RESOURCE", () -> EmbeddedNativeRuntime.extract(platform, second, name -> null));
        rejects("INTEGRITY", () -> EmbeddedNativeRuntime.extract(platform, second, name -> new ByteArrayInputStream(new byte[data.length])));
        check(!Files.exists(second.resolve(platform.filename())), "Corrupt unpublished extraction leaked");
        rejects("INTEGRITY", () -> EmbeddedNativeRuntime.extract(platform, second, name -> new ByteArrayInputStream(new byte[data.length + 1])));
        check(!Files.exists(second.resolve(platform.filename())), "Oversized unpublished extraction leaked");
        rejects("INTEGRITY", () -> EmbeddedNativeRuntime.extract(platform, second, name -> new ByteArrayInputStream(new byte[1])));
        check(!Files.exists(second.resolve(platform.filename())), "Short unpublished extraction leaked");
        try { EmbeddedNativeRuntime.extract(platform, first, name -> new ByteArrayInputStream(new byte[1])); throw new AssertionError("Existing file overwritten"); }
        catch (java.nio.file.FileAlreadyExistsException expected) { checks++; }
        check(Files.readString(extracted).equals(new String(data, java.nio.charset.StandardCharsets.UTF_8)), "Existing extraction changed");
        check(Files.readString(sentinel).equals("do not delete"), "Shared-root native file altered");
        AtomicInteger calls = new AtomicInteger();
        EmbeddedNativeRuntime.load(platform, extracted, absolute -> { calls.incrementAndGet(); check(absolute.equals(extracted.toString()), "Not absolute exact library"); });
        check(calls.get() == 1, "Linker not called");
        for (var bundled : EmbeddedNativeRuntime.PLATFORMS) {
            var cause = new UnsatisfiedLinkError("fixture missing dependency / noexec");
            IOException error = rejects("NATIVE_LINK", () -> EmbeddedNativeRuntime.load(bundled, extracted, absolute -> { throw cause; }));
            check(error.getCause() == cause, "OS linker cause discarded");
            check(error.getMessage().contains(extracted.toString()) && error.getMessage().contains(bundled.requirements()), "No platform/path guidance");
            check(error.getMessage().contains("mysticism.embedding.nativeDirectory") && error.getMessage().contains("No external embedding server"), "No actionable launch option");
        }
        var denied = new SecurityException("fixture policy denies native load");
        check(rejects("NATIVE_LINK", () -> EmbeddedNativeRuntime.load(platform, extracted, absolute -> { throw denied; })).getCause() == denied,
                "Native security failure cause discarded");
        Path badRoot = fixtureRoot.resolve("ordinary-file"); Files.writeString(badRoot, "not a directory");
        try { EmbeddedNativeRuntime.privateDirectory(badRoot); throw new AssertionError("File accepted as extraction directory"); }
        catch (IOException expected) { checks++; }
    }
    private static void workerPreflight() throws Exception {
        AtomicInteger assetReads = new AtomicInteger(), modelLoads = new AtomicInteger();
        IOException failure = new IOException("Embedded CPU runtime [UNSUPPORTED_PLATFORM]: fixture");
        Thread owner = Thread.currentThread();
        var asset = new EmbeddedEmbeddingService.Asset() {
            public Path resolve() { assetReads.incrementAndGet(); return Path.of("must-not-provision.gguf"); }
            public void close() {}
        };
        var loader = new EmbeddedEmbeddingService.Loader() {
            public void prepare() throws Exception { check(Thread.currentThread() != owner, "Preflight on game/lifecycle thread"); throw failure; }
            public EmbeddedEmbeddingService.Model load(Path path, java.util.function.BooleanSupplier cancelled) { modelLoads.incrementAndGet(); throw new AssertionError("Native model executed on unsupported platform"); }
        };
        try (var provider = new EmbeddedEmbeddingService(asset, loader)) {
            check(assetReads.get() == 0 && modelLoads.get() == 0, "Eager constructor");
            try (var engine = new AsyncEmbeddingEngine(provider, 1, 4, 4, Duration.ofSeconds(2))) {
                try { engine.readiness().get(3, TimeUnit.SECONDS); throw new AssertionError("False ready"); }
                catch (ExecutionException expected) { check(expected.getCause() == failure, "Actionable preflight cause lost"); }
                check(!engine.isReady() && assetReads.get() == 0 && modelLoads.get() == 0, "Platform failure provisions gigabyte weights");
                try { engine.embed("oak log").get(3, TimeUnit.SECONDS); throw new AssertionError("Fallback inference"); }
                catch (ExecutionException expected) {
                    check(expected.getCause() instanceof IllegalStateException && expected.getCause().getCause() == failure,
                            "Failed inference lost platform cause");
                    check(expected.getCause().getMessage().contains("UNSUPPORTED_PLATFORM"), "Embedding rejection hides platform action");
                }
            }
        }
    }
    private static void freshJvmFailures() throws Exception {
        Path root = Path.of("build/embedding-platform-test").toAbsolutePath(); Files.createDirectories(root);
        Path file = Files.createTempFile(root, "not-directory-", ".fixture");
        for (var fixture : List.of(
                List.of("-Dos.arch=not-a-supported-cpu", "UNSUPPORTED_PLATFORM"),
                List.of("-Dsun.arch.data.model=32", "JVM"),
                List.of("-Dmysticism.embedding.nativeDirectory=" + file, "EXTRACTION"),
                List.of("-Dmysticism.selftest.invalidNativeDirectory=true", "EXTRACTION"),
                List.of("-Dnet.ladenthin.llama.lib.path=" + root, "CONFIGURATION"),
                List.of("-Dnet.ladenthin.llama.backend=cuda", "CONFIGURATION"))) {
            var command = new java.util.ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-ea"); command.add(fixture.getFirst());
            command.add("-cp"); command.add(System.getProperty("java.class.path"));
            command.add(EmbeddedBootstrapProbe.class.getName()); command.add(fixture.getLast());
            Process child = new ProcessBuilder(command).inheritIO().start();
            try {
                check(child.waitFor(10, TimeUnit.SECONDS), "Fresh JVM preflight hung: " + fixture);
                check(child.exitValue() == 0, "Fresh JVM preflight failed: " + fixture);
            } finally { if (child.isAlive()) child.destroyForcibly(); }
        }
    }
    public static void main(String[] args) throws Exception {
        platformPolicy(); bundledPayloads(); extractionAndCauses(); workerPreflight(); freshJvmFailures();
        System.out.println("EmbeddedPlatformTest passed: " + checks + " checks; no model inference");
    }
}
