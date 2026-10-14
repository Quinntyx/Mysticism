package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Resolves the same bundle from Loom/IDE exploded resources, a mod jar, or an explicit
 * local directory. Never searches user.dir, the source tree, Ollama or a model zoo.
 * This is resource transport/integrity, not proof of a model's inference semantics.
 */
public final class EmbeddedRuntimeResources {
    public static final String RESOURCE = "META-INF/mysticism/embedding/bundle.properties";
    public static final String BUNDLE_PROPERTY = "mysticism.embedding.bundle";
    public static final String CACHE_PROPERTY = "mysticism.embedding.resourceCache";
    private static final int MAX_MANIFEST_BYTES = 65536;

    public record Bundle(String origin, Map<String, Path> assets, List<Path> runtimeJars) {
        public Bundle {
            assets = Map.copyOf(assets);
            runtimeJars = List.copyOf(runtimeJars);
        }
        public Path require(String role) {
            Path file = assets.get(role);
            if (file == null) throw new IllegalArgumentException("Missing embedded asset role: " + role);
            return file;
        }
    }
    private EmbeddedRuntimeResources() {}

    public static Bundle resolve(ClassLoader loader) throws IOException {
        return resolveIfPresent(loader).orElseThrow(() -> new IOException("Embedded v2 runtime/model bundle not provisioned: " + RESOURCE
                + " (or configure an absolute " + BUNDLE_PROPERTY + "); no external service fallback"));
    }

    /** Only an absent bundle permits the bundled JNI provider's normal pinned-file path.
     * An explicitly configured or discovered broken/ambiguous bundle must still fail closed.
     * Call on the preparation worker, not during provider construction.
     */
    public static Optional<Bundle> resolveIfPresent(ClassLoader loader) throws IOException {
        String directory = System.getProperty(BUNDLE_PROPERTY);
        if (directory != null) return Optional.of(resolveDirectory(absolute(directory, BUNDLE_PROPERTY)));
        List<URL> manifests = Collections.list(loader.getResources(RESOURCE));
        if (manifests.isEmpty()) return Optional.empty();
        if (manifests.size() != 1) throw new IOException("Ambiguous embedded bundles: " + manifests);
        String cache = System.getProperty(CACHE_PROPERTY);
        Path cachePath = cache == null ? Path.of(System.getProperty("java.io.tmpdir"), "mysticism-embedding-resources")
                .toAbsolutePath() : absolute(cache, CACHE_PROPERTY);
        return Optional.of(resolveResource(manifests.getFirst(), cachePath));
    }

    public static Bundle resolveDirectory(Path directory) throws IOException {
        if (!directory.isAbsolute()) throw new IOException("Embedded bundle directory must be absolute");
        Path root = directory.toRealPath();
        Path manifest = confined(root, "bundle.properties");
        Properties properties;
        try (InputStream stream = Files.newInputStream(manifest)) { properties = readManifest(stream); }
        return resolve(properties, root.toString(), (name, digest) -> {
            Path file = confined(root, name);
            verify(file, digest);
            return file;
        });
    }

    /** URL-relative reads preserve the manifest's origin: another classpath jar cannot
     * shadow its model/tokenizer. jar: URLs are streams, not filesystem Paths.
     */
    public static Bundle resolveResource(URL manifest, Path cache) throws IOException {
        if (!cache.isAbsolute()) throw new IOException("Embedded resource cache must be absolute");
        requireLocal(manifest);
        Properties properties;
        try (InputStream stream = manifest.openStream()) { properties = readManifest(stream); }
        return resolve(properties, manifest.toExternalForm(), (name, digest) -> {
            URL resource = new URL(manifest, name);
            // Resource bundles are local only; never turn model loading into a network fetch.
            requireLocal(resource);
            return materialize(resource, digest, cache);
        });
    }

    private interface AssetResolver { Path resolve(String name, String digest) throws IOException; }
    private static Bundle resolve(Properties properties, String origin, AssetResolver resolver) throws IOException {
        if (!"1".equals(properties.getProperty("bundleSchema"))) throw new IOException("Unsupported embedded bundle schema");
        if (EmbeddingSpace.DIMENSIONS != 768 || EmbeddingSpace.NATIVE_DIMENSIONS != 768 || EmbeddingSpace.SCHEMA != 2
                || !EmbeddingSpace.FINGERPRINT.equals(properties.getProperty("profileFingerprint")))
            throw new IOException("Embedded bundle does not match the current native v2 768-dimensional profile");
        var names = new TreeMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("asset.")) continue;
            String role = key.substring(6);
            if (!role.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IOException("Invalid embedded asset role: " + role);
            String name = properties.getProperty(key);
            validateName(name);
            names.put(role, name);
        }
        if (!names.containsKey("model") || names.size() > 64) throw new IOException("Expected a model and at most 64 embedded assets");
        var jarRoles = new LinkedHashSet<String>();
        String jars = properties.getProperty("runtimeJars", "");
        if (!jars.isBlank()) for (String role : jars.split(",", -1)) {
            role = role.trim();
            if (!names.containsKey(role) || !names.get(role).endsWith(".jar") || !jarRoles.add(role))
                throw new IOException("Invalid/duplicate runtime jar role: " + role);
        }
        var files = new LinkedHashMap<String, Path>();
        // Validate the complete manifest before copying any potentially large asset.
        for (String role : names.keySet()) {
            String digest = properties.getProperty("sha256." + role, "");
            if (!digest.matches("[a-f0-9]{64}")) throw new IOException("Missing/invalid SHA256 for embedded asset: " + role);
        }
        for (var asset : names.entrySet())
            files.put(asset.getKey(), resolver.resolve(asset.getValue(), properties.getProperty("sha256." + asset.getKey())));
        return new Bundle(origin, files, jarRoles.stream().map(files::get).toList());
    }

    private static Properties readManifest(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Oversized embedded bundle manifest");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate embedded manifest key: " + key);
                return super.put(key, value);
            }
        };
        try { properties.load(new java.io.ByteArrayInputStream(bytes)); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid embedded manifest", error); }
        return properties;
    }
    private static Path absolute(String value, String setting) throws IOException {
        Path path = Path.of(value);
        if (value.isBlank() || !path.isAbsolute()) throw new IOException(setting + " must be an absolute local path (not relative to Minecraft's run directory)");
        return path.normalize();
    }
    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_./-]+") || name.startsWith("/"))
            throw new IOException("Invalid embedded asset path: " + name);
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe embedded asset path: " + name);
    }
    private static Path confined(Path root, String name) throws IOException {
        validateName(name);
        Path file = root.resolve(name).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("Embedded asset escapes bundle or is not a regular file: " + name);
        return file;
    }
    private static void requireLocal(URL resource) throws IOException {
        // file://host/... can make URL.openStream use a remote transport on the JDK.
        if (resource.getProtocol().equals("file") && resource.getHost().isEmpty()) return;
        if (resource.getProtocol().equals("jar")) {
            URL archive = ((java.net.JarURLConnection) resource.openConnection()).getJarFileURL();
            if (archive.getProtocol().equals("file") && archive.getHost().isEmpty()) return;
        }
        throw new IOException("Embedded assets must use local file/jar resources: " + resource);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private static String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Embedded resource hashing interrupted");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void verify(Path path, String expected) throws IOException {
        if (!expected.equals(digest(path))) throw new IOException("Embedded asset SHA256 mismatch: " + path);
    }
    private static Path materialize(URL resource, String expected, Path cache) throws IOException {
        Files.createDirectories(cache);
        // Retain suffix/name for native loaders which inspect file extensions; hashes isolate bundles.
        String name = resource.getPath().substring(resource.getPath().lastIndexOf('/') + 1);
        Path target = cache.resolve(expected + "-" + name);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && expected.equals(digest(target))) return target;
        Path temporary = Files.createTempFile(cache, "embedding-", ".part");
        try {
            MessageDigest hash = sha256();
            try (InputStream input = resource.openStream(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Embedded resource extraction interrupted");
                    hash.update(buffer, 0, read); output.write(buffer, 0, read);
                }
            }
            if (!expected.equals(HexFormat.of().formatHex(hash.digest()))) throw new IOException("Embedded asset SHA256 mismatch: " + resource);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }
}
