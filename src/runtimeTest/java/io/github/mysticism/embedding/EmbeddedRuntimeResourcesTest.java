package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** Real file/jar/exploded-resource transport; fixture bytes are NOT model weights. */
public final class EmbeddedRuntimeResourcesTest {
    private static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IOException | RuntimeException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String manifest(byte[] model) throws Exception {
        return "bundleSchema=1\nprofileFingerprint=" + EmbeddingSpace.FINGERPRINT
                + "\nasset.model=model.gguf\nsha256.model=" + hash(model) + "\n";
    }
    static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "embedded-resource-test-");
        Path directory = Files.createDirectory(root.resolve("provisioned bundle with spaces"));
        byte[] model = "transport fixture, not a Nomic model".getBytes(StandardCharsets.UTF_8);
        String manifest = manifest(model);
        Files.write(directory.resolve("model.gguf"), model);
        Files.writeString(directory.resolve("bundle.properties"), manifest);
        var disk = EmbeddedRuntimeResources.resolveDirectory(directory);
        check(disk.require("model").equals(directory.resolve("model.gguf").toRealPath()), "Directory returns absolute original asset");
        check(disk.runtimeJars().isEmpty(), "Runtime may be on the game classpath");
        rejects(() -> EmbeddedRuntimeResources.resolveDirectory(Path.of("relative")), "Relative bundle must not depend on game cwd");
        rejects(() -> disk.require("tokenizer"), "Missing required role");
        rejects(() -> disk.assets().put("x", root), "Map must be immutable");

        Path exploded = Files.createDirectories(root.resolve("exploded/META-INF/mysticism/embedding"));
        Files.writeString(exploded.resolve("bundle.properties"), manifest);
        Files.write(exploded.resolve("model.gguf"), model);
        Path cache = Files.createDirectory(root.resolve("cache with spaces"));
        var resources = EmbeddedRuntimeResources.resolveResource(exploded.resolve("bundle.properties").toUri().toURL(), cache);
        check(resources.require("model").isAbsolute(), "Exploded output is usable as a native file path");
        check(Arrays.equals(Files.readAllBytes(resources.require("model")), model), "Exploded bytes preserved");
        Path archive = root.resolve("mod resources with spaces.jar");
        jar(archive, Map.of(EmbeddedRuntimeResources.RESOURCE, manifest.getBytes(StandardCharsets.ISO_8859_1),
                "META-INF/mysticism/embedding/model.gguf", model));
        URL url = new URL("jar:" + archive.toUri().toURL() + "!/" + EmbeddedRuntimeResources.RESOURCE);
        var bundled = EmbeddedRuntimeResources.resolveResource(url, cache);
        check(Arrays.equals(Files.readAllBytes(bundled.require("model")), model), "Jar resources extracted as native files");
        check(bundled.require("model").equals(resources.require("model")), "Content-addressed reuse across origins");
        Files.writeString(bundled.require("model"), "corrupted cache");
        check(Arrays.equals(Files.readAllBytes(EmbeddedRuntimeResources.resolveResource(url, cache).require("model")), model), "Corrupt cache is repaired from verified resource");
        rejects(() -> EmbeddedRuntimeResources.resolveResource(url, Path.of("relative")), "Cache must be absolute");
        rejects(() -> EmbeddedRuntimeResources.resolveResource(new URL("https://example.invalid/bundle.properties"), cache), "Remote manifest forbidden before any network IO");
        rejects(() -> EmbeddedRuntimeResources.resolveResource(new URL("jar:https://example.invalid/model.jar!/bundle.properties"), cache), "Remote jar forbidden");
        rejects(() -> EmbeddedRuntimeResources.resolveResource(new URL("file://example.invalid/bundle.properties"), cache), "Remote file URL forbidden before URL transport");
        rejects(() -> EmbeddedRuntimeResources.resolveResource(new URL("jar:file://example.invalid/model.jar!/bundle.properties"), cache), "Remote file jar forbidden before URL transport");

        List<String> badManifests = List.of(
                manifest.replace("bundleSchema=1", "bundleSchema=2"),
                manifest.replace(EmbeddingSpace.FINGERPRINT, "old-v1-profile"),
                manifest.replace("model.gguf", "../model.gguf"),
                manifest.replace("model.gguf", "/model.gguf"),
                manifest.replace("model.gguf", "http://example.invalid/model"),
                manifest.replace("model.gguf", "folder\\\\model.gguf"),
                manifest.replace("model.gguf", "folder/./model.gguf"),
                manifest.replace("model.gguf", "folder//model.gguf"),
                manifest.replace(hash(model), "not-a-digest"),
                manifest.replace(hash(model), "0".repeat(64)),
                manifest.replace("asset.model=", "asset.tokenizer="),
                manifest + "runtimeJars=absent\n",
                manifest + "runtimeJars=model\n",
                manifest + "profileFingerprint=duplicate\n");
        for (String bad : badManifests) {
            Files.writeString(directory.resolve("bundle.properties"), bad);
            rejects(() -> EmbeddedRuntimeResources.resolveDirectory(directory), "Invalid manifest must fail closed");
        }
        Files.writeString(directory.resolve("bundle.properties"), "x".repeat(65537));
        rejects(() -> EmbeddedRuntimeResources.resolveDirectory(directory), "Manifest bound");
        Files.writeString(directory.resolve("bundle.properties"), manifest);
        Files.writeString(root.resolve("outside.gguf"), "outside");
        Path linked = directory.resolve("link.gguf");
        Files.createSymbolicLink(linked, root.resolve("outside.gguf"));
        Files.writeString(directory.resolve("bundle.properties"), manifest.replace("model.gguf", "link.gguf"));
        rejects(() -> EmbeddedRuntimeResources.resolveDirectory(directory), "Symlink escape must not supply a model");

        // Multiple mod/classpath copies are an error, never last-one-wins model identity.
        String priorBundle = System.getProperty(EmbeddedRuntimeResources.BUNDLE_PROPERTY);
        String priorCache = System.getProperty(EmbeddedRuntimeResources.CACHE_PROPERTY);
        try {
            System.clearProperty(EmbeddedRuntimeResources.BUNDLE_PROPERTY);
            System.setProperty(EmbeddedRuntimeResources.CACHE_PROPERTY, cache.toString());
            try (URLClassLoader loader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, null)) {
                check(Arrays.equals(Files.readAllBytes(EmbeddedRuntimeResources.resolve(loader).require("model")), model), "Classloader discovery supports mod jars");
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                try (var provider = new EmbeddedEmbeddingProvider(loader)) {
                    rejects(() -> provider.getEmbedding("must not reach inference"), "Resource discovery alone is not readiness");
                    try {
                        provider.checkReady();
                        throw new AssertionError("Unapproved fixture weights must not become ready");
                    } catch (IOException expected) {
                        check(expected instanceof NomicV2ModelAssets.AssetException
                                        && expected.getMessage().contains("Incomplete or wrong native v2 GGUF")
                                        && expected.getMessage().contains("expected " + NomicV2ModelAssets.APPROVED_GGUF_BYTES),
                                "Built-in JNI adapter must verify approved weights, not require an external adapter/service");
                    }
                    check(Thread.currentThread().getContextClassLoader() == previous, "Failed built-in startup must restore caller classloader");
                    rejects(() -> provider.getEmbedding("must remain unavailable"), "Failed adapter startup cannot expose inference");
                }
            }
            Path second = root.resolve("another.jar"); Files.copy(archive, second);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{archive.toUri().toURL(), second.toUri().toURL()}, null)) {
                rejects(() -> EmbeddedRuntimeResources.resolve(loader), "Ambiguous bundle discovery");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
                check(EmbeddedRuntimeResources.resolveIfPresent(loader).isEmpty(), "Absent bundle permits the built-in pinned model path");
                rejects(() -> EmbeddedRuntimeResources.resolve(loader), "Required bundle resolution still reports absence");
                Files.writeString(directory.resolve("bundle.properties"), manifest);
                System.setProperty(EmbeddedRuntimeResources.BUNDLE_PROPERTY, directory.toString());
                check(EmbeddedRuntimeResources.resolve(loader).require("model").equals(directory.resolve("model.gguf")), "Explicit provisioned directory needs no classpath resources");
                System.setProperty(EmbeddedRuntimeResources.BUNDLE_PROPERTY, "relative");
                rejects(() -> EmbeddedRuntimeResources.resolveIfPresent(loader), "Invalid explicit bundle must never fall back to default model provisioning");
            }
        } finally {
            restore(EmbeddedRuntimeResources.BUNDLE_PROPERTY, priorBundle);
            restore(EmbeddedRuntimeResources.CACHE_PROPERTY, priorCache);
        }
        System.out.println("EmbeddedRuntimeResourcesTest: " + checks + " checks (resource fixtures, no inference)");
    }
    static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}
