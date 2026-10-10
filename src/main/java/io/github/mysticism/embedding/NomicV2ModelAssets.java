package io.github.mysticism.embedding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mysticism.vector.EmbeddingSpace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Offline, read-only loading of the approved native v2 GGUF (weights AND tokenizer).
 * No HTTP, download, conversion, installation, model zoo, or embedding fallback.
 * Opening an asset is not inference readiness: a compatible in-process GGUF backend
 * must still load it and perform its own real readiness probe.
 */
public final class NomicV2ModelAssets {
    public static final String MODEL_PATH_PROPERTY = "mysticism.embedding.modelPath";
    public static final String OLLAMA_MODELS_PROPERTY = "mysticism.embedding.ollamaModels";
    public static final String APPROVED_REVISION = "ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965";
    public static final String APPROVED_GGUF_SHA256 = "a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f";
    public static final long APPROVED_GGUF_BYTES = 957_680_480L;
    private static final String MODEL_MEDIA_TYPE = "application/vnd.ollama.image.model";
    private static final Path MANIFEST = Path.of("manifests", "registry.ollama.ai", "library", "nomic-embed-text-v2-moe", "latest");
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final Pin APPROVED = new Pin(APPROVED_REVISION, APPROVED_GGUF_SHA256, APPROVED_GGUF_BYTES);

    private NomicV2ModelAssets() {}

    /** Explicit choices are authoritative: a missing explicit file never falls back to a cache. */
    public record Location(Path path, boolean ollamaCache) {
        public Location {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        }
        public static Location gguf(Path file) { return new Location(file, false); }
        public static Location cache(Path root) { return new Location(root, true); }
    }

    /** Immutable startup configuration; does not touch disk or initialize a native library. */
    public static Location configuredLocation() {
        return configuredLocation(System.getProperties(), System.getenv());
    }

    static Location configuredLocation(Properties properties, Map<String, String> environment) {
        String file = properties.getProperty(MODEL_PATH_PROPERTY);
        if (file != null) return Location.gguf(configuredPath(file, MODEL_PATH_PROPERTY));
        String cache = properties.getProperty(OLLAMA_MODELS_PROPERTY);
        if (cache != null) return Location.cache(configuredPath(cache, OLLAMA_MODELS_PROPERTY));
        cache = environment.get("OLLAMA_MODELS");
        if (cache != null) return Location.cache(configuredPath(cache, "OLLAMA_MODELS"));
        return Location.cache(configuredPath(properties.getProperty("user.home"), "user.home").resolve(".ollama/models"));
    }

    private static Path configuredPath(String value, String setting) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(setting + " must be a nonblank local path");
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid local path in " + setting, e);
        }
    }

    /** Blocking disk IO. Call from the embedding initialization worker, never a server tick. */
    public static OpenedModel openConfigured() throws IOException {
        return open(configuredLocation());
    }

    /**
     * Opens and SHA256-verifies the actual complete model, not a filename or a cache flag.
     * The default cache is Ollama's on-disk cache; Ollama itself need not be running.
     * Arbitrary ONNX exports, HF safetensors, v1 models and unapproved GGUFs are not supported.
     */
    public static OpenedModel open(Location location) throws IOException {
        requireApprovedProfile();
        return open(location, APPROVED);
    }

    private static void requireApprovedProfile() throws AssetException {
        if (!(EmbeddingSpace.MODEL.equals("nomic-embed-text-v2-moe") || EmbeddingSpace.MODEL.equals("nomic-embed-text-v2-moe:latest"))
                || !EmbeddingSpace.REVISION.equals(APPROVED_REVISION)
                || EmbeddingSpace.DIMENSIONS != 768 || EmbeddingSpace.NATIVE_DIMENSIONS != 768
                || EmbeddingSpace.SCHEMA != 2) {
            throw new AssetException("Local GGUF loader requires the approved fresh native768 Nomic v2-MoE profile/revision; refusing another model, revision or reduced space"
                    + " (model=" + EmbeddingSpace.MODEL + ", revision=" + EmbeddingSpace.REVISION
                    + ", nativeDimensions=" + EmbeddingSpace.NATIVE_DIMENSIONS + ", dimensions=" + EmbeddingSpace.DIMENSIONS + ", schema=" + EmbeddingSpace.SCHEMA + ")");
        }
    }

    // Package-private pin substitution is only for small file-format regression fixtures.
    // Production entrypoints always use APPROVED and requireApprovedProfile().
    record Pin(String revision, String ggufSha256, long bytes) {}

    static OpenedModel open(Location location, Pin pin) throws IOException {
        Objects.requireNonNull(location, "location");
        Path model = location.path();
        if (location.ollamaCache()) {
            Path manifest = location.path().resolve(MANIFEST);
            byte[] bytes = readManifest(manifest);
            String manifestDigest = digest(bytes);
            if (!manifestDigest.equals(pin.revision())) {
                throw new AssetException("Cached Nomic v2 manifest revision mismatch at " + manifest + ": expected " + pin.revision()
                        + ", got " + manifestDigest + "; refusing changed tags or mixed embeddings");
            }
            verifyManifest(bytes, pin, manifest);
            // Never trust a manifest's `from` field or a supplied digest as a path.
            model = location.path().resolve("blobs").resolve("sha256-" + pin.ggufSha256());
        }
        return openGguf(model, pin);
    }

    private static byte[] readManifest(Path path) throws IOException {
        requireRegularFile("Nomic v2 cache manifest", path);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new AssetException("Oversized Nomic v2 cache manifest at " + path);
            return bytes;
        }
    }

    private static void verifyManifest(byte[] bytes, Pin pin, Path path) throws AssetException {
        try {
            JsonObject manifest = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(manifest.get("schemaVersion")) != 2) throw new IllegalArgumentException("schemaVersion");
            JsonArray layers = manifest.getAsJsonArray("layers");
            if (layers == null || layers.isEmpty() || layers.size() > 32) throw new IllegalArgumentException("layers");
            int models = 0;
            for (JsonElement element : layers) {
                JsonObject layer = element.getAsJsonObject();
                if (!MODEL_MEDIA_TYPE.equals(string(layer.get("mediaType")))) continue;
                models++;
                if (!string(layer.get("digest")).equals("sha256:" + pin.ggufSha256())
                        || integer(layer.get("size")) != pin.bytes()) {
                    throw new IllegalArgumentException("model layer identity/size");
                }
            }
            if (models != 1) throw new IllegalArgumentException("exactly one model layer required");
        } catch (RuntimeException e) {
            throw new AssetException("Unsupported or malformed Nomic v2 cache manifest at " + path, e);
        }
    }

    private static OpenedModel openGguf(Path path, Pin pin) throws IOException {
        requireRegularFile("native v2 GGUF (including tokenizer)", path);
        Path realPath = path.toRealPath();
        FileChannel channel = FileChannel.open(realPath, StandardOpenOption.READ);
        try {
            if (channel.size() != pin.bytes()) {
                throw new AssetException("Incomplete or wrong native v2 GGUF at " + path + ": expected " + pin.bytes() + " bytes, got " + channel.size());
            }
            ByteBuffer header = ByteBuffer.allocate(8);
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) throw new AssetException("Truncated GGUF header at " + path);
            }
            // The checksum, not a claimed filename/architecture, establishes identity.
            // Version/backend compatibility still belongs to the actual native loader.
            byte[] h = header.array();
            if (h[0] != 'G' || h[1] != 'G' || h[2] != 'U' || h[3] != 'F') {
                throw new AssetException("Unsupported model format at " + path + "; expected the approved GGUF artifact");
            }
            String ggufDigest = digest(channel, pin.bytes());
            if (!ggufDigest.equals(pin.ggufSha256())) {
                throw new AssetException("Native v2 GGUF SHA256 mismatch at " + path + ": expected " + pin.ggufSha256()
                        + ", got " + ggufDigest + "; refusing corrupt, replaced or incompatible weights/tokenizer");
            }
            if (channel.size() != pin.bytes()) throw new AssetException("Native v2 GGUF changed during verification at " + path);
            return new OpenedModel(realPath, channel, pin);
        } catch (Throwable e) {
            try { channel.close(); } catch (Throwable closing) { e.addSuppressed(closing); }
            throw e;
        }
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected manifest string");
        return value.getAsString();
    }
    private static long integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.toString().matches("[0-9]+")) throw new IllegalArgumentException("Expected manifest integer");
        return Long.parseLong(value.toString());
    }
    private static void requireRegularFile(String asset, Path path) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(path, BasicFileAttributes.class); }
        catch (NoSuchFileException e) { throw missing(asset, path); }
        if (!attributes.isRegularFile()) throw new AssetException("Expected " + asset + " to be a regular file, not " + path);
    }

    private static AssetException missing(String asset, Path path) {
        return new AssetException("Missing " + asset + " at " + path
                + ". Supply the already-provisioned approved GGUF with -D" + MODEL_PATH_PROPERTY
                + "=<file>, or its existing Ollama cache with -D" + OLLAMA_MODELS_PROPERTY
                + "=<models-directory>. No download, server connection or fallback was attempted.");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static String digest(FileChannel channel, long expectedBytes) throws IOException {
        MessageDigest hash = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
        long offset = 0;
        while (offset < expectedBytes) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), expectedBytes - offset));
            int count = channel.read(buffer, offset);
            if (count < 0) throw new AssetException("Native v2 GGUF truncated during verification");
            if (count == 0) throw new AssetException("Unable to make progress reading native v2 GGUF");
            offset += count;
            buffer.flip();
            hash.update(buffer);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    /**
     * Complete native loading synchronously here; do not retain the temporary OpenedModel
     * or its channel. The returned backend owns its native model. No runtime is selected here.
     */
    @FunctionalInterface
    public interface ModelLoader<T extends AutoCloseable> {
        T load(OpenedModel model) throws Exception;
    }

    /**
     * Hands verified assets to an in-process backend and re-verifies before returning it.
     * On failure, a returned backend is closed; a backend whose constructor fails must
     * clean up its own partial allocations. Assets are always closed. A non-null
     * backend is NOT declared inference-ready here. Its provider must probe real inference.
     */
    public static <T extends AutoCloseable> T loadConfigured(ModelLoader<T> loader) throws Exception {
        return load(configuredLocation(), loader);
    }

    public static <T extends AutoCloseable> T load(Location location, ModelLoader<T> loader) throws Exception {
        requireApprovedProfile();
        return load(location, APPROVED, loader);
    }

    static <T extends AutoCloseable> T load(Location location, Pin pin, ModelLoader<T> loader) throws Exception {
        Objects.requireNonNull(loader, "loader");
        T backend = null;
        try (OpenedModel model = open(location, pin)) {
            backend = Objects.requireNonNull(loader.load(model), "Model loader returned null, not a loaded backend");
            // Re-open the selected cache AND model: also catches replacements, not just
            // changes to the original open inode. Keep provisioned assets immutable.
            try (OpenedModel verified = open(location, pin)) {
                if (!verified.path().equals(model.path())) throw new AssetException("Native v2 asset path changed during backend loading");
            }
        } catch (Throwable e) {
            if (backend != null) try { backend.close(); } catch (Throwable closing) { e.addSuppressed(closing); }
            throw e;
        }
        return backend;
    }

    /** A verified read-only disk model. No tensor or vector is manufactured by this class. */
    public static final class OpenedModel implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final Pin pin;
        private OpenedModel(Path path, FileChannel channel, Pin pin) {
            this.path = path; this.channel = channel; this.pin = pin;
        }
        public Path path() { return path; }
        public long bytes() { return pin.bytes(); }
        public String manifestRevision() { return pin.revision(); }
        public String ggufSha256() { return pin.ggufSha256(); }
        public boolean isOpen() { return channel.isOpen(); }
        public int read(ByteBuffer destination, long offset) throws IOException {
            if (offset < 0) throw new IllegalArgumentException("Negative model offset");
            return channel.read(destination, offset);
        }
        @Override public void close() throws IOException { channel.close(); }
    }

    public static final class AssetException extends IOException {
        public AssetException(String message) { super(message); }
        public AssetException(String message, Throwable cause) { super(message, cause); }
    }
}
