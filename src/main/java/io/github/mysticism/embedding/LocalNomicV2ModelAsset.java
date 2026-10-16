package io.github.mysticism.embedding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Read-only runtime asset selection. No IO/path parsing/JNI in the constructor,
 * and no automatic network provisioning or fallback to a service/another model.
 */
final class LocalNomicV2ModelAsset implements EmbeddedEmbeddingService.Asset {
    private final Properties properties;
    private final Map<String, String> environment;
    private final NomicV2ModelAssets.Pin fixturePin;
    private NomicV2ModelAssets.Location selected; // preparation worker owns selection
    private volatile boolean closed;

    LocalNomicV2ModelAsset() { this(null, null, null); }

    // Small file-format fixtures only; production cannot override the approved pin.
    LocalNomicV2ModelAsset(Properties properties, Map<String, String> environment, NomicV2ModelAssets.Pin fixturePin) {
        this.properties = properties;
        this.environment = environment;
        this.fixturePin = fixturePin;
    }

    private NomicV2ModelAssets.Location location() throws IOException {
        requireOpen();
        if (fixturePin == null) NomicV2ModelAssets.requireApprovedProfile();
        if (selected == null) {
            Properties settings = new Properties();
            settings.putAll(properties == null ? System.getProperties() : properties);
            selected = select(settings, environment == null ? System.getenv() : environment,
                    fixturePin == null ? NomicV2ModelAssets.APPROVED_GGUF_SHA256 : fixturePin.ggufSha256());
        }
        return selected;
    }

    static NomicV2ModelAssets.Location select(Properties settings, Map<String, String> environment, String digest) throws IOException {
        var configured = NomicV2ModelAssets.configuredLocation(settings, environment);
        // Explicit choices are authoritative even when missing, unreadable or corrupt.
        if (settings.containsKey(NomicV2ModelAssets.MODEL_PATH_PROPERTY)
                || settings.containsKey(NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY)
                || environment.containsKey("OLLAMA_MODELS")) return configured;

        Path manifest = configured.path().resolve("manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest");
        if (present(manifest)) return configured;
        // A copied immutable, content-addressed blob is also supported without a moving tag.
        // Its complete GGUF digest/size still establish identity before AND after native loading.
        Path blob = configured.path().resolve("blobs/sha256-" + digest);
        if (present(blob)) return NomicV2ModelAssets.Location.gguf(blob);
        Path cache = Path.of(settings.getProperty("user.home")).resolve(".cache/mysticism/models/sha256-" + digest + ".gguf");
        if (present(cache)) return NomicV2ModelAssets.Location.gguf(cache);
        throw new NomicV2ModelAssets.AssetException("Missing approved native v2 weights/tokenizer. Checked "
                + manifest + ", " + blob + ", " + cache + ". Supply the exact GGUF with -D"
                + NomicV2ModelAssets.MODEL_PATH_PROPERTY + "=<file> or an existing cache with -D"
                + NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY + "=<models-directory>. Expected SHA256="
                + digest + " (" + NomicV2ModelAssets.APPROVED_GGUF_BYTES + " bytes)."
                + " No download, server connection or fallback was attempted.");
    }

    private static boolean present(Path path) throws IOException {
        // Files.exists/isRegularFile swallow access errors. Only genuine absence permits
        // selecting a lower-priority DEFAULT cache; do not disguise permission failures.
        try { Files.readAttributes(path, BasicFileAttributes.class); return true; }
        catch (NoSuchFileException absent) { return false; }
    }

    @Override public Path resolve() throws Exception {
        var location = location();
        try (var opened = fixturePin == null ? NomicV2ModelAssets.open(location) : NomicV2ModelAssets.open(location, fixturePin)) {
            requireOpen();
            return opened.path();
        }
    }

    @Override public EmbeddedEmbeddingService.Model load(EmbeddedEmbeddingService.Loader loader, BooleanSupplier cancelled) throws Exception {
        var location = location();
        BooleanSupplier stopped = () -> closed || cancelled.getAsBoolean();
        NomicV2ModelAssets.ModelLoader<EmbeddedEmbeddingService.Model> allocate = verified -> loader.load(verified.path(), stopped);
        return fixturePin == null ? NomicV2ModelAssets.load(location, allocate, stopped)
                : NomicV2ModelAssets.load(location, fixturePin, allocate, stopped);
    }

    private void requireOpen() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Native v2 asset loading cancelled");
    }
    @Override public void close() { closed = true; }
}
