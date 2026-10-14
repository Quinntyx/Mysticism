package io.github.mysticism.embedding;

import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonParser;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Small local HTTP/file fixtures prove integrity/publication, not model inference. */
public final class PinnedEmbeddingModelSelfTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; } throw new AssertionError("Expected integrity/provisioning failure");
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void provenance() throws Exception {
        try (var in = PinnedEmbeddingModelSelfTest.class.getResourceAsStream("/embedding/nomic-v2-manifest.json")) {
            check(in != null, "Missing provenance fixture");
            byte[] bytes = in.readAllBytes(); check(hash(bytes).equals(PinnedEmbeddingModel.MANIFEST), "Manifest identity changed");
            var layers = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("layers");
            var model = layers.get(0).getAsJsonObject();
            check(model.get("digest").getAsString().equals("sha256:" + PinnedEmbeddingModel.SHA256), "Wrong weight/tokenizer blob");
            check(model.get("size").getAsLong() == PinnedEmbeddingModel.BYTES, "Wrong pinned size");
            check(PinnedEmbeddingModel.SOURCE.toString().endsWith("/blobs/sha256:" + PinnedEmbeddingModel.SHA256), "Runtime uses moving model tag");
        }
    }
    public static void main(String[] args) throws Exception {
        provenance();
        Path base = Path.of("build/embedding-selftest"); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "asset-");
        byte[] bytes = "GGUF fixture ONLY (not executable weights)".getBytes(StandardCharsets.UTF_8);
        String sha = hash(bytes); AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write(new byte[bytes.length + 1]); }
        });
        server.createContext("/short", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write(new byte[bytes.length - 1]); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/model"); exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            Path target = directory.resolve("model.gguf");
            try (var first = new PinnedEmbeddingModel(target, root.resolve("redirect"), sha, bytes.length, true, Duration.ofSeconds(3))) {
                check(first.resolve().equals(target.toAbsolutePath()), "Wrong cache result");
                check(Arrays.equals(Files.readAllBytes(target), bytes), "Bad publication");
            }
            int count = requests.get();
            try (var cached = new PinnedEmbeddingModel(target, root.resolve("model"), sha, bytes.length, false, Duration.ofSeconds(3))) { cached.resolve(); }
            check(requests.get() == count, "Verified offline cache used HTTP");
            byte[] changed = bytes.clone(); changed[10] ^= 1; Files.write(target, changed);
            try (var changedAsset = new PinnedEmbeddingModel(target, root.resolve("model"), sha, bytes.length, true, Duration.ofSeconds(3))) { rejects(changedAsset::resolve); }
            check(requests.get() == count && Arrays.equals(Files.readAllBytes(target), changed), "Corrupt/user asset silently replaced");
            for (String endpoint : List.of("oversized", "short", "loop")) {
                Path failed = directory.resolve(endpoint + ".gguf");
                try (var asset = new PinnedEmbeddingModel(failed, root.resolve(endpoint), sha, bytes.length, true, Duration.ofSeconds(3))) { rejects(asset::resolve); }
                check(!Files.exists(failed), "Unverified partial model published");
            }
            Path wrongHash = directory.resolve("wrong-hash.gguf");
            try (var asset = new PinnedEmbeddingModel(wrongHash, root.resolve("model"), "0".repeat(64), bytes.length, true, Duration.ofSeconds(3))) { rejects(asset::resolve); }
            check(!Files.exists(wrongHash), "Wrong digest published");
            try (var paths = Files.list(directory)) { check(paths.noneMatch(path -> path.toString().endsWith(".part")), "Failed download leaked temporary asset"); }
            Path missing = directory.resolve("missing.gguf"); count = requests.get();
            try (var offline = new PinnedEmbeddingModel(missing, root.resolve("model"), sha, bytes.length, false, Duration.ofSeconds(3))) { rejects(offline::resolve); }
            check(requests.get() == count, "Offline opt-out ignored");
            try (var cancelled = new PinnedEmbeddingModel(missing, root.resolve("model"), sha, bytes.length, true, Duration.ofSeconds(3))) {
                cancelled.close(); rejects(cancelled::resolve);
            }
            check(requests.get() == count, "Closed asset downloaded");
            Path shared = directory.resolve("shared.gguf");
            try (var a = new PinnedEmbeddingModel(shared, root.resolve("model"), sha, bytes.length, true, Duration.ofSeconds(3));
                 var b = new PinnedEmbeddingModel(shared, root.resolve("model"), sha, bytes.length, true, Duration.ofSeconds(3))) {
                var one = CompletableFuture.supplyAsync(() -> { try { return a.resolve(); } catch (Exception e) { throw new CompletionException(e); } });
                var two = CompletableFuture.supplyAsync(() -> { try { return b.resolve(); } catch (Exception e) { throw new CompletionException(e); } });
                check(one.get(4, TimeUnit.SECONDS).equals(two.get(4, TimeUnit.SECONDS)), "Concurrent preparation disagrees");
                check(requests.get() == count + 1, "Cache lock allowed duplicate download");
            }
        } finally { server.stop(0); }
        System.out.println("PinnedEmbeddingModelSelfTest passed: " + checks + " checks (loopback/integrity fixtures, no weights)");
    }
}
