package io.github.mysticism.embedding;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** File-format/ownership fixtures and the actual unavailable default provider, NOT a forward pass. */
public final class LocalNomicV2ModelAssetTest {
    private static int checks;
    private static Path root;
    private static final byte[] BYTES = new byte[700_123];
    private static final Path MANIFEST = Path.of("manifests/registry.ollama.ai/library/nomic-embed-text-v2-moe/latest");
    static {
        BYTES[0] = 'G'; BYTES[1] = 'G'; BYTES[2] = 'U'; BYTES[3] = 'F'; BYTES[4] = 3;
        for (int i = 8; i < BYTES.length; i++) BYTES[i] = (byte) (i * 23);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static <T extends Throwable> T fails(Class<T> type, String text, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable e) {
            check(type.isInstance(e), "Wrong failure: " + e);
            check(e.getMessage().contains(text), "Missing diagnostic '" + text + "': " + e);
            return type.cast(e);
        }
        throw new AssertionError("Expected failure: " + text);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Path write(Path path, byte[] bytes) throws Exception { Files.createDirectories(path.getParent()); return Files.write(path, bytes); }
    private static Properties settings(String name) {
        Properties p = new Properties(); p.setProperty("user.home", root.resolve(name).toString()); return p;
    }
    private static NomicV2ModelAssets.Pin pin() throws Exception { return new NomicV2ModelAssets.Pin("0".repeat(64), sha(BYTES), BYTES.length); }
    private static Path ollama(Properties p) { return Path.of(p.getProperty("user.home")).resolve(".ollama/models"); }
    private static Path app(Properties p) throws Exception { return Path.of(p.getProperty("user.home")).resolve(".cache/mysticism/models/sha256-" + sha(BYTES) + ".gguf"); }
    private static Path blob(Path cache) throws Exception { return cache.resolve("blobs/sha256-" + sha(BYTES)); }
    private static NomicV2ModelAssets.Pin manifest(Path cache) throws Exception {
        byte[] json = ("{\"schemaVersion\":2,\"layers\":[{\"mediaType\":\"application/vnd.ollama.image.model\",\"digest\":\"sha256:"
                + sha(BYTES) + "\",\"size\":" + BYTES.length + "}]}").getBytes(StandardCharsets.UTF_8);
        write(cache.resolve(MANIFEST), json); write(blob(cache), BYTES);
        return new NomicV2ModelAssets.Pin(sha(json), sha(BYTES), BYTES.length);
    }
    private static final class Model implements EmbeddedEmbeddingService.Model {
        final AtomicInteger closes = new AtomicInteger(), forwards = new AtomicInteger();
        public int dimensions() { return 768; }
        public int tokens(String input) { return 4; }
        public float[] embed(String input) { forwards.incrementAndGet(); float[] v = new float[768]; v[767] = 1; return v; }
        public void close() { closes.incrementAndGet(); }
    }
    private static void selectionAndLoad() throws Exception {
        Properties p = settings("empty");
        var missing = fails(NomicV2ModelAssets.AssetException.class, "Missing approved native v2", () -> LocalNomicV2ModelAsset.select(p, Map.of(), sha(BYTES)));
        check(missing.getMessage().contains(ollama(p).resolve(MANIFEST).toString())
                && missing.getMessage().contains(blob(ollama(p)).toString()) && missing.getMessage().contains(app(p).toString()), "Missing report omits searched defaults");
        check(missing.getMessage().contains("No download, server connection or fallback"), "Untruthful missing-asset handling");
        Path app = write(app(p), BYTES);
        var chosen = LocalNomicV2ModelAsset.select(p, Map.of(), sha(BYTES));
        check(!chosen.ollamaCache() && chosen.path().equals(app.toAbsolutePath()), "Verified application cache not supported");
        Model model = new Model(); AtomicInteger loads = new AtomicInteger();
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), pin());
             var provider = new EmbeddedEmbeddingService(asset, (path, cancelled) -> {
                 loads.incrementAndGet(); check(path.equals(app.toRealPath()), "Backend received wrong cache path"); return model;
             })) {
            check(loads.get() == 0 && model.forwards.get() == 0, "Construction loaded/inferred eagerly");
            provider.checkReady(); provider.checkReady();
            check(loads.get() == 1 && model.forwards.get() == 1, "Readiness not an actual single forward pass");
            check(provider.getEmbedding("oak").data()[767] == 1, "Fixture tail missing");
        }
        // Default content-addressed blob works even if the moving manifest is absent.
        Path blob = write(blob(ollama(p)), BYTES);
        chosen = LocalNomicV2ModelAsset.select(p, Map.of(), sha(BYTES));
        check(chosen.path().equals(blob.toAbsolutePath()), "Ollama blob should precede app cache");
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), pin())) { check(asset.resolve().equals(blob.toRealPath()), "Blob was not verified/opened"); }
        var cachePin = manifest(ollama(p));
        chosen = LocalNomicV2ModelAsset.select(p, Map.of(), sha(BYTES));
        check(chosen.ollamaCache(), "Existing manifest not verified");
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), cachePin)) { check(asset.resolve().equals(blob.toRealPath()), "Manifest cache not verified/opened"); }
        Files.writeString(ollama(p).resolve(MANIFEST), "changed tag");
        AtomicInteger nativeLoads = new AtomicInteger();
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), cachePin)) {
            fails(NomicV2ModelAssets.AssetException.class, "revision mismatch", () -> asset.load((path, cancelled) -> {
                nativeLoads.incrementAndGet(); return new Model();
            }, () -> false));
        }
        check(nativeLoads.get() == 0 && Files.exists(app), "Corrupt manifest fell back to good app cache/inference");

        Properties explicit = settings("explicit");
        write(app(explicit), BYTES);
        for (boolean env : new boolean[]{false, true}) {
            Path configuredCache = root.resolve(env ? "env-cache" : "property-cache");
            Properties s = new Properties(); s.putAll(explicit);
            if (!env) s.setProperty(NomicV2ModelAssets.OLLAMA_MODELS_PROPERTY, configuredCache.toString());
            Map<String, String> vars = env ? Map.of("OLLAMA_MODELS", configuredCache.toString()) : Map.of();
            try (var asset = new LocalNomicV2ModelAsset(s, vars, pin())) {
                fails(NomicV2ModelAssets.AssetException.class, "Missing Nomic v2 cache manifest", asset::resolve);
            }
            manifest(configuredCache);
            try (var asset = new LocalNomicV2ModelAsset(s, vars, manifest(configuredCache))) {
                check(asset.resolve().equals(blob(configuredCache).toRealPath()), "Configured cache root ignored");
            }
        }
        explicit.setProperty(NomicV2ModelAssets.MODEL_PATH_PROPERTY, root.resolve("explicit-missing.gguf").toString());
        try (var asset = new LocalNomicV2ModelAsset(explicit, Map.of("OLLAMA_MODELS", "broken cache"), pin())) {
            fails(NomicV2ModelAssets.AssetException.class, "Missing native v2 GGUF", asset::resolve);
        }
        Path explicitFile = app(explicit);
        explicit.setProperty(NomicV2ModelAssets.MODEL_PATH_PROPERTY, explicitFile.toString());
        explicit.remove("user.home");
        try (var asset = new LocalNomicV2ModelAsset(explicit, Map.of("OLLAMA_MODELS", ""), pin())) {
            check(asset.resolve().equals(explicitFile.toRealPath()), "Explicit file unnecessarily requires home/valid lower-priority cache settings");
        }
        Properties corrupt = settings("corrupt-default");
        write(app(corrupt), BYTES); write(blob(ollama(corrupt)), new byte[8]);
        try (var asset = new LocalNomicV2ModelAsset(corrupt, Map.of(), pin())) {
            fails(NomicV2ModelAssets.AssetException.class, "Incomplete or wrong", asset::resolve);
        }
    }
    private static void handoffFailureAndCancellation() throws Exception {
        Properties p = settings("handoff"); Path path = write(app(p), BYTES);
        Model changed = new Model();
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), pin());
             var provider = new EmbeddedEmbeddingService(asset, (file, cancelled) -> {
                 byte[] corrupt = BYTES.clone(); corrupt[corrupt.length - 1] ^= 1;
                 Files.move(write(root.resolve("replacement.gguf"), corrupt), path, StandardCopyOption.REPLACE_EXISTING);
                 return changed;
             })) {
            fails(NomicV2ModelAssets.AssetException.class, "SHA256 mismatch", provider::checkReady);
            check(changed.closes.get() == 1 && changed.forwards.get() == 0, "Changed weights published/probed or native allocation leaked");
            fails(IllegalStateException.class, "not prepared", () -> provider.getEmbedding("oak"));
        }
        write(path, BYTES);
        Model cancelled = new Model();
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), pin())) {
            fails(CancellationException.class, "cancelled", () -> asset.load((file, stopped) -> {
                asset.close(); check(stopped.getAsBoolean(), "Close not observed by native load"); return cancelled;
            }, () -> false));
        }
        check(cancelled.closes.get() == 1 && cancelled.forwards.get() == 0, "Cancelled load leaked/inferred");
        AtomicInteger allocations = new AtomicInteger(), polls = new AtomicInteger();
        try (var asset = new LocalNomicV2ModelAsset(p, Map.of(), pin())) {
            fails(CancellationException.class, "cancelled", () -> asset.load((file, stopped) -> {
                allocations.incrementAndGet(); return new Model();
            }, () -> polls.incrementAndGet() >= 4));
        }
        check(allocations.get() == 0, "Hash cancellation was ignored until native allocation");
    }
    private static void realMissingDefaultProvider() throws Exception {
        String[] names = {"mysticism.embedding.provider", NomicV2ModelAssets.MODEL_PATH_PROPERTY, "mysticism.embedding.endpoint"};
        String[] old = new String[names.length]; for (int i = 0; i < names.length; i++) old[i] = System.getProperty(names[i]);
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { httpCalls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); }); server.start();
        Path missing = root.resolve("actual-provider-missing.gguf");
        try {
            System.clearProperty(names[0]); System.setProperty(names[1], missing.toString());
            System.setProperty(names[2], "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            try (var provider = EmbeddingProviders.create();
                 var engine = new AsyncEmbeddingEngine(provider, 1, 4, 4, Duration.ofSeconds(3))) {
                check(provider instanceof EmbeddedEmbeddingProvider, "Default provider must use the embedded resource/runtime bridge, not HTTP");
                var failure = fails(ExecutionException.class, "Missing native v2 GGUF", () -> engine.readiness().get(3, TimeUnit.SECONDS));
                check(failure.getCause() instanceof NomicV2ModelAssets.AssetException, "Missing file disguised as native/server failure");
                check(!engine.isReady(), "Missing weights falsely ready");
                fails(ExecutionException.class, "", () -> engine.embed("oak").get(3, TimeUnit.SECONDS));
            }
            check(httpCalls.get() == 0 && !Files.exists(missing), "Missing weights contacted service or fabricated file");
        } finally {
            server.stop(0);
            for (int i = 0; i < names.length; i++) { if (old[i] == null) System.clearProperty(names[i]); else System.setProperty(names[i], old[i]); }
        }
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("build/model-asset-regressions").toAbsolutePath()), "integration-");
        selectionAndLoad(); handoffFailureAndCancellation(); realMissingDefaultProvider();
        System.out.println("LocalNomicV2ModelAssetTest passed: " + checks + " checks (fixture files/native ownership, actual unavailable default provider; NO real inference)");
    }
}
