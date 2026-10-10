package io.github.mysticism.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Small synthetic file-format fixtures, NOT weights, inference, or dummy embeddings. */
public final class NomicV2ModelAssetsTest {
    private static int checks;
    private static Path root;
    private static final String MEDIA_TYPE = "application/vnd.ollama.image.model";
    private static final Path MANIFEST = Path.of("manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest");
    private static final byte[] FIXTURE = fixture();

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static <T extends Throwable> T fails(Class<T> type, String text, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable e) {
            check(type.isInstance(e), "Expected " + type + ", got " + e);
            check(e.getMessage().contains(text), "Missing diagnostic '" + text + "' in " + e);
            return type.cast(e);
        }
        throw new AssertionError("Expected rejection: " + text);
    }
    private static byte[] fixture() {
        byte[] bytes = new byte[4096];
        bytes[0] = 'G'; bytes[1] = 'G'; bytes[2] = 'U'; bytes[3] = 'F'; bytes[4] = 3;
        for (int i = 8; i < bytes.length; i++) bytes[i] = (byte) (i * 17);
        return bytes;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Path directory(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }
    private static Path write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.write(path, bytes);
    }
    private static String layer(String digest, String size, String mediaType) {
        return "{\"mediaType\":\"" + mediaType + "\",\"digest\":\"sha256:" + digest
                + "\",\"size\":" + size + ",\"from\":\"/not/a/model/on/this/machine\"}";
    }
    private static String manifest(String schema, String layers) {
        return "{\"schemaVersion\":" + schema + ",\"layers\":[" + layers + "]}";
    }
    private static NomicV2ModelAssets.Pin cache(Path cache, String manifest, byte[] model) throws Exception {
        byte[] json = manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        write(cache.resolve(MANIFEST), json);
        String digest = sha(FIXTURE);
        if (model != null) write(cache.resolve("blobs/sha256-" + digest), model);
        return new NomicV2ModelAssets.Pin(sha(json), digest, FIXTURE.length);
    }
    private static NomicV2ModelAssets.Pin validCache(Path cache, byte[] model) throws Exception {
        return cache(cache, manifest("2", layer(sha(FIXTURE), "4096", MEDIA_TYPE)), model);
    }
    private static NomicV2ModelAssets.Pin explicitPin() throws Exception {
        return new NomicV2ModelAssets.Pin("0".repeat(64), sha(FIXTURE), FIXTURE.length);
    }
    private static void configuration() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user.home", root.resolve("home").toString());
        var selected = NomicV2ModelAssets.configuredLocation(properties, Map.of());
        check(selected.ollamaCache(), "default is local cache, not HTTP");
        check(selected.path().equals(root.resolve("home/.ollama/models")), "standard user cache");
        var env = Map.of("OLLAMA_MODELS", root.resolve("env cache with spaces").toString());
        selected = NomicV2ModelAssets.configuredLocation(properties, env);
        check(selected.path().equals(root.resolve("env cache with spaces")), "Ollama environment respected");
        properties.setProperty(NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY, root.resolve("property-cache").toString());
        selected = NomicV2ModelAssets.configuredLocation(properties, env);
        check(selected.path().equals(root.resolve("property-cache")), "property overrides environment");
        properties.setProperty(NomicV2ModelAssets.MODEL_PATH_PROPERTY, root.resolve("explicit.gguf").toString());
        selected = NomicV2ModelAssets.configuredLocation(properties, env);
        check(!selected.ollamaCache(), "explicit file overrides all caches");
        check(selected.path().equals(root.resolve("explicit.gguf")), "explicit file retained");
        properties.setProperty(NomicV2ModelAssets.MODEL_PATH_PROPERTY, " ");
        fails(IllegalArgumentException.class, "nonblank", () -> NomicV2ModelAssets.configuredLocation(properties, env));
        properties.remove(NomicV2ModelAssets.MODEL_PATH_PROPERTY);
        properties.setProperty(NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY, "\0invalid");
        fails(IllegalArgumentException.class, "Invalid local path", () -> NomicV2ModelAssets.configuredLocation(properties, env));
        properties.remove(NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY);
        fails(IllegalArgumentException.class, "nonblank", () -> NomicV2ModelAssets.configuredLocation(properties, Map.of("OLLAMA_MODELS", "")));
        properties.remove("user.home");
        fails(IllegalArgumentException.class, "user.home", () -> NomicV2ModelAssets.configuredLocation(properties, Map.of()));
    }

    private static void explicitAndCache() throws Exception {
        Path explicit = write(root.resolve("direct/model.gguf"), FIXTURE);
        var location = NomicV2ModelAssets.Location.gguf(explicit);
        var pin = explicitPin();
        var opened = NomicV2ModelAssets.open(location, pin);
        check(opened.isOpen(), "real read-only disk model opened");
        check(opened.path().equals(explicit.toRealPath()), "resolved absolute asset path");
        check(opened.bytes() == FIXTURE.length, "exact byte count");
        check(opened.ggufSha256().equals(sha(FIXTURE)), "whole-file identity");
        check(opened.manifestRevision().equals(pin.revision()), "revision retained");
        ByteBuffer header = ByteBuffer.allocate(8);
        check(opened.read(header, 0) == 8, "offset read");
        check(Arrays.equals(header.array(), Arrays.copyOf(FIXTURE, 8)), "header bytes from file, no conversion");
        ByteBuffer tail = ByteBuffer.allocate(31);
        check(opened.read(tail, FIXTURE.length - 17) == 17, "bounded EOF read");
        check(Arrays.equals(Arrays.copyOf(tail.array(), 17), Arrays.copyOfRange(FIXTURE, FIXTURE.length - 17, FIXTURE.length)), "tail retained");
        header.clear();
        check(opened.read(header, 0) == 8, "positional reads do not share cursor");
        fails(IllegalArgumentException.class, "Negative", () -> opened.read(ByteBuffer.allocate(1), -1));
        opened.close();
        opened.close();
        check(!opened.isOpen(), "closed, idempotent close");
        try { opened.read(ByteBuffer.allocate(1), 0); throw new AssertionError("closed file remained usable"); }
        catch (ClosedChannelException expected) { checks++; }
        check(Arrays.equals(Files.readAllBytes(explicit), FIXTURE), "opening never modifies asset bytes");
        var relative = NomicV2ModelAssets.Location.gguf(Path.of("build/../build/model-asset-regressions").resolve(root.getFileName()).resolve("direct/model.gguf"));
        check(relative.path().equals(location.path()), "relative paths normalized within caller working directory");

        // Exercise several digest buffer iterations and changes beyond the first chunk.
        byte[] larger = Arrays.copyOf(FIXTURE, 700_123);
        for (int i = FIXTURE.length; i < larger.length; i++) larger[i] = (byte) (i * 31);
        Path largePath = write(root.resolve("multi-chunk.gguf"), larger);
        var largePin = new NomicV2ModelAssets.Pin(pin.revision(), sha(larger), larger.length);
        try (var large = NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(largePath), largePin)) {
            check(large.bytes() == larger.length, "streamed complete multi-chunk model");
        }
        larger[larger.length - 1] ^= 1;
        write(largePath, larger);
        fails(NomicV2ModelAssets.AssetException.class, "SHA256 mismatch", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(largePath), largePin));

        Path cache = directory("complete-cache");
        var cachePin = validCache(cache, FIXTURE);
        try (var cached = NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(cache), cachePin)) {
            check(cached.path().equals(cache.resolve("blobs/sha256-" + sha(FIXTURE)).toRealPath()), "official cache layout; foreign from field ignored");
            check(cached.manifestRevision().equals(cachePin.revision()), "exact manifest bytes determine revision");
        }
        // Linux HF caches often use symlinks. Content identity, not symlink names, is authoritative.
        Path link = root.resolve("model-link.gguf");
        Files.createSymbolicLink(link, explicit);
        try (var linked = NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(link), pin)) {
            check(linked.path().equals(explicit.toRealPath()), "verified local symlink follows real asset");
        }
    }

    private static void missingAndCorrupt() throws Exception {
        var pin = explicitPin();
        var missing = NomicV2ModelAssets.Location.gguf(root.resolve("never-provisioned.gguf"));
        var error = fails(NomicV2ModelAssets.AssetException.class, "Missing native v2 GGUF", () -> NomicV2ModelAssets.open(missing, pin));
        check(error.getMessage().contains(missing.path().toString()), "missing location shown");
        check(error.getMessage().contains("No download, server connection or fallback"), "truthful offline remedy");
        check(!Files.exists(missing.path()), "missing weights not manufactured");
        fails(NomicV2ModelAssets.AssetException.class, "regular file", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(root), pin));
        Path unreadable = write(root.resolve("unreadable.gguf"), FIXTURE);
        if (Files.getFileStore(unreadable).supportsFileAttributeView("posix")) {
            var permissions = Files.getPosixFilePermissions(unreadable);
            try {
                Files.setPosixFilePermissions(unreadable, java.util.Set.of());
                if (!Files.isReadable(unreadable)) {
                    var denied = fails(java.nio.file.AccessDeniedException.class, unreadable.toString(), () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(unreadable), pin));
                    check(!denied.getMessage().contains("Missing"), "permission errors are NOT called missing assets");
                } else System.out.println("Permission regression skipped: process may bypass file permissions");
            } finally { Files.setPosixFilePermissions(unreadable, permissions); }
        } else System.out.println("Permission regression skipped: filesystem lacks POSIX attributes");
        Path partial = write(root.resolve("partial.gguf"), Arrays.copyOf(FIXTURE, 256));
        fails(NomicV2ModelAssets.AssetException.class, "Incomplete or wrong", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(partial), pin));
        byte[] corrupt = FIXTURE.clone(); corrupt[corrupt.length - 1] ^= 1;
        Path corruptPath = write(root.resolve("corrupt.gguf"), corrupt);
        fails(NomicV2ModelAssets.AssetException.class, "SHA256 mismatch", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(corruptPath), pin));
        byte[] wrongFormat = FIXTURE.clone(); wrongFormat[0] = 0;
        Path wrongPath = write(root.resolve("v1-or-safetensors.bin"), wrongFormat);
        fails(NomicV2ModelAssets.AssetException.class, "Unsupported model format", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(wrongPath), pin));
        // Public production API NEVER accepts the fixture pin, a model name alone, or a reduced model.
        fails(NomicV2ModelAssets.AssetException.class, "expected 957680480", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(corruptPath)));

        Path absentCache = directory("absent-cache");
        fails(NomicV2ModelAssets.AssetException.class, "Missing Nomic v2 cache manifest", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(absentCache), pin));
        Path absentBlob = directory("absent-blob");
        var cachePin = validCache(absentBlob, null);
        fails(NomicV2ModelAssets.AssetException.class, "Missing native v2 GGUF", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(absentBlob), cachePin));
        Path wrongRevision = directory("changed-tag");
        var correctPin = validCache(wrongRevision, FIXTURE);
        Files.writeString(wrongRevision.resolve(MANIFEST), Files.readString(wrongRevision.resolve(MANIFEST)) + " ");
        fails(NomicV2ModelAssets.AssetException.class, "revision mismatch", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(wrongRevision), correctPin));
        Path brokenCache = directory("corrupt-cache-model");
        var brokenPin = validCache(brokenCache, corrupt);
        fails(NomicV2ModelAssets.AssetException.class, "SHA256 mismatch", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(brokenCache), brokenPin));
        Path oversized = directory("oversized-manifest");
        write(oversized.resolve(MANIFEST), new byte[65537]);
        fails(NomicV2ModelAssets.AssetException.class, "Oversized", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(oversized), pin));
    }

    private static void malformedManifests() throws Exception {
        String layer = layer(sha(FIXTURE), "4096", MEDIA_TYPE);
        String[] malformed = {
                "not json", "[]", "{}", manifest("1", layer), manifest("\"2\"", layer),
                manifest("2", ""), manifest("2", layer + "," + layer),
                manifest("2", layer("../escape", "4096", MEDIA_TYPE)),
                manifest("2", layer(sha(FIXTURE), "4095", MEDIA_TYPE)),
                manifest("2", layer(sha(FIXTURE), "\"4096\"", MEDIA_TYPE)),
                manifest("2", layer(sha(FIXTURE), "4096.0", MEDIA_TYPE)),
                manifest("2", layer(sha(FIXTURE), "4096", "application/vnd.ollama.image.adapter"))
        };
        for (int i = 0; i < malformed.length; i++) {
            Path cache = directory("malformed-" + i);
            var pin = cache(cache, malformed[i], FIXTURE);
            fails(NomicV2ModelAssets.AssetException.class, "Unsupported or malformed", () -> NomicV2ModelAssets.open(NomicV2ModelAssets.Location.cache(cache), pin));
        }
    }

    private static final class Backend implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        final boolean closeThrows;
        Backend(boolean closeThrows) { this.closeThrows = closeThrows; }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            if (closeThrows) throw new IOException("fixture close failure");
        }
    }
    private static void backendHandoff() throws Exception {
        var pin = explicitPin();
        Path path = write(root.resolve("backend/model.gguf"), FIXTURE);
        var location = NomicV2ModelAssets.Location.gguf(path);
        NomicV2ModelAssets.OpenedModel[] asset = new NomicV2ModelAssets.OpenedModel[1];
        Backend backend = new Backend(false);
        var loaded = NomicV2ModelAssets.load(location, pin, model -> {
            asset[0] = model;
            check(model.isOpen(), "loader receives OPEN verified file");
            check(model.path().equals(path.toRealPath()), "native backend receives exact file path");
            return backend; // Resource-lifecycle fixture only, NOT an embedding provider.
        });
        check(loaded == backend, "backend resource returned without manufacturing an embedding");
        check(!asset[0].isOpen(), "temporary verified asset closed after successful native allocation");
        check(backend.closes.get() == 0, "successful backend not prematurely closed");
        loaded.close();
        check(backend.closes.get() == 1, "backend ownership transferred to caller");

        fails(IOException.class, "fixture constructor failure", () -> NomicV2ModelAssets.load(location, pin, model -> {
            asset[0] = model;
            throw new IOException("fixture constructor failure");
        }));
        check(!asset[0].isOpen(), "asset closes after backend constructor failure");
        fails(NullPointerException.class, "returned null", () -> NomicV2ModelAssets.load(location, pin, model -> null));

        Backend mutated = new Backend(true);
        var changed = fails(NomicV2ModelAssets.AssetException.class, "SHA256 mismatch", () -> NomicV2ModelAssets.load(location, pin, model -> {
            asset[0] = model;
            byte[] replacement = FIXTURE.clone(); replacement[100] ^= 1;
            Path incoming = write(root.resolve("backend/replacement.gguf"), replacement);
            Files.move(incoming, path, StandardCopyOption.REPLACE_EXISTING);
            return mutated;
        }));
        check(mutated.closes.get() == 1, "backend closed if asset replaced while loading");
        check(!asset[0].isOpen(), "original open inode closes after replacement");
        check(changed.getSuppressed().length == 1 && changed.getSuppressed()[0].getMessage().equals("fixture close failure"), "cleanup errors do not hide primary asset failure");

        Path cache = directory("backend-cache");
        var cachePin = validCache(cache, FIXTURE);
        Backend retagged = new Backend(false);
        fails(NomicV2ModelAssets.AssetException.class, "revision mismatch", () -> NomicV2ModelAssets.load(NomicV2ModelAssets.Location.cache(cache), cachePin, model -> {
            Files.writeString(cache.resolve(MANIFEST), Files.readString(cache.resolve(MANIFEST)) + " ");
            return retagged;
        }));
        check(retagged.closes.get() == 1, "backend closed if cache retagged while loading");
        AtomicInteger calls = new AtomicInteger();
        fails(NomicV2ModelAssets.AssetException.class, "Missing native v2 GGUF", () -> NomicV2ModelAssets.load(NomicV2ModelAssets.Location.gguf(root.resolve("missing-override.gguf")), pin, model -> {
            calls.incrementAndGet(); return new Backend(false);
        }));
        check(calls.get() == 0, "missing explicit override never invokes backend or consults cache");
    }

    /** Separate JVM: profile properties are a before-classloading contract. */
    public static final class ProfileProbe {
        public static void main(String[] args) throws Exception {
            try {
                NomicV2ModelAssets.open(NomicV2ModelAssets.Location.gguf(Path.of(args[0])));
                throw new AssertionError("unsupported profile accepted");
            } catch (NomicV2ModelAssets.AssetException expected) {
                if (!expected.getMessage().contains("approved fresh native768")) throw expected;
            }
        }
    }
    private static void rejectedProfiles() throws Exception {
        for (String property : new String[] {"mysticism.embedding.model=nomic-embed-text-v1.5:latest", "mysticism.embedding.model=unapproved-alias", "mysticism.embedding.revision=" + "1".repeat(64)}) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-ea", "-D" + property, "-cp", System.getProperty("java.class.path"),
                    ProfileProbe.class.getName(), root.resolve("absent.gguf").toString()).inheritIO().start();
            boolean finished = child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { child.destroyForcibly(); throw new AssertionError("profile probe exceeded 10 seconds"); }
            check(child.exitValue() == 0, "unsupported configured profile fails BEFORE asset loading: " + property);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!NomicV2ModelAssetsTest.class.desiredAssertionStatus()) throw new AssertionError("enable -ea");
        root = Files.createTempDirectory(Files.createDirectories(Path.of("build/model-asset-regressions").toAbsolutePath()), "case-");
        configuration(); explicitAndCache(); missingAndCorrupt(); malformedManifests(); backendHandoff(); rejectedProfiles();
        System.out.println("NomicV2ModelAssetsTest passed: " + checks + " checks (offline synthetic files; NO model inference)");
    }
}
