package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual cache files + async engine lifecycle, no model loading/service/game bootstrap. */
public final class EmbeddingCacheReuseTest {
    private static int assertions;
    private static Path directory;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action) throws Exception {
        assertions++; try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected cache rejection");
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
    private static void failed(CompletableFuture<?> future) throws Exception {
        assertions++; try { get(future); } catch (ExecutionException | CancellationException expected) { return; }
        throw new AssertionError("Expected exceptional future");
    }
    private static Vec384f tail() {
        float[] values = new float[EmbeddingSpace.NATIVE_DIMENSIONS]; values[767] = 1; return new Vec384f(values);
    }
    private static class Provider implements EmbeddingProvider {
        final AtomicInteger calls = new AtomicInteger(), healthChecks = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        boolean failReady, failWork, holdWork;
        public EmbeddingProfile profile() { return EmbeddingProfile.current(); }
        public void checkReady() { healthChecks.incrementAndGet(); if (failReady) throw new IllegalStateException("Unhealthy model"); }
        public Vec384f getEmbedding(String descriptor) throws Exception {
            calls.incrementAndGet(); started.countDown();
            if (holdWork) release.await();
            if (failWork) throw new IllegalStateException("Inference failure");
            Vec384f value = tail().mul(3); returned.countDown(); return value;
        }
        public void close() { release.countDown(); }
    }
    private static AsyncEmbeddingEngine engine(Provider provider, int capacity, Path path) {
        return new AsyncEmbeddingEngine(provider, 1, 8, capacity, Duration.ofSeconds(2), path);
    }
    private static void restartAndEviction() throws Exception {
        Path file = directory.resolve("restart.bin"); Provider first = new Provider();
        try (var engine = engine(first, 2, file)) {
            get(engine.readiness());
            Vec384f result = get(engine.embed("a")); result.mul(0);
            get(engine.embed("b"));
            check(get(engine.embed("a")).data()[767] == 1, "Caller mutation must not poison cached native tail");
            get(engine.embed("c")); check(engine.cacheSize() == 2, "Runtime LRU capacity");
        }
        check(first.calls.get() == 3, "Only misses invoke provider");
        check(EmbeddingCacheFile.read(file, 2).keySet().equals(new LinkedHashSet<>(List.of("a", "c"))), "Disk LRU membership");
        Provider second = new Provider();
        try (var engine = engine(second, 2, file)) {
            get(engine.readiness()); check(second.healthChecks.get() == 1, "Cache never substitutes for provider readiness");
            check(engine.cacheSize() == 2, "Restored before exposing readiness");
            check(get(engine.embed("a")).data()[767] == 1 && get(engine.embed("c")).length() == 1, "Native tail survives restart without reduction");
            check(second.calls.get() == 0, "A healthy new provider reuses cached descriptors");
            get(engine.embed("b")); check(second.calls.get() == 1, "Evicted descriptor gets fresh inference");
            failed(engine.embed(" ")); check(second.calls.get() == 1, "Invalid descriptor never reaches provider");
        }
        // Persistence is opt-in for direct engine users; old constructor remains isolated.
        Provider isolated = new Provider();
        try (var engine = new AsyncEmbeddingEngine(isolated, 1, 8, 2, Duration.ofSeconds(2))) {
            get(engine.readiness()); get(engine.embed("a")); check(isolated.calls.get() == 1, "Default direct engine has no hidden disk cache");
        }
        var order = new LinkedHashMap<String, Vec384f>(); for (String id : List.of("old", "middle", "new")) order.put(id, tail());
        Path smaller = directory.resolve("smaller.bin"); EmbeddingCacheFile.write(smaller, order);
        check(new ArrayList<>(EmbeddingCacheFile.read(smaller, 2).keySet()).equals(List.of("middle", "new")), "Smaller capacity keeps newest validated entries");
        var loaded = EmbeddingCacheFile.read(smaller, 2); loaded.get("new").mul(0);
        check(EmbeddingCacheFile.read(smaller, 2).get("new").length() == 1, "Read snapshots do not mutate persisted vectors");
    }
    /** Explicit fixtures with recomputed checksums prove profile/shape checks, not just corruption detection. */
    private static void fixture(Path file, int format, int schema, int descriptors, EmbeddingProfile profile,
                                List<String> keys, int[] bits, boolean trailing) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d594543); out.writeInt(format); out.writeInt(schema); out.writeInt(descriptors);
            out.writeUTF(profile.model()); out.writeUTF(profile.revision()); out.writeInt(profile.dimensions());
            out.writeUTF(profile.semantics()); out.writeUTF(profile.fingerprint()); out.writeInt(keys.size());
            for (String key : keys) { out.writeUTF(key); out.writeInt(bits.length); for (int value : bits) out.writeInt(value); }
            if (trailing) out.writeByte(1);
        }
        byte[] payload = bytes.toByteArray();
        try (var out = Files.newOutputStream(file)) { out.write(payload); out.write(MessageDigest.getInstance("SHA-256").digest(payload)); }
    }
    private static void invalidation() throws Exception {
        Path file = directory.resolve("invalid.bin"); var current = EmbeddingProfile.current();
        var profiles = List.of(
                new EmbeddingProfile("nomic-embed-text-v1.5", current.revision(), 768, current.semantics(), current.fingerprint()),
                new EmbeddingProfile(current.model(), "other-revision", 768, current.semantics(), current.fingerprint()),
                new EmbeddingProfile(current.model(), current.revision(), 384, current.semantics(), current.fingerprint()),
                new EmbeddingProfile(current.model(), current.revision(), 768, "other-pooling/task/tokenizer", current.fingerprint()),
                new EmbeddingProfile(current.model(), current.revision(), 768, current.semantics(), "foreign-fingerprint"));
        for (var profile : profiles) {
            fixture(file, 1, EmbeddingSpace.SCHEMA, EmbeddingSpace.DESCRIPTOR_VERSION, profile, List.of("stale"), tail().toBits(), false);
            rejects(() -> EmbeddingCacheFile.read(file, 8));
            Provider provider = new Provider();
            try (var engine = engine(provider, 8, file)) {
                get(engine.readiness()); check(engine.cacheSize() == 0, "Foreign profile not partly restored");
                get(engine.embed("stale")); check(provider.calls.get() == 1, "Foreign-profile entry requires fresh inference");
            }
            check(EmbeddingCacheFile.read(file, 8).get("stale").data()[767] == 1, "Fresh current-profile cache replaces stale file");
        }
        for (int[] versions : List.of(new int[]{0, 2, 2}, new int[]{1, 1, 2}, new int[]{1, 2, 1})) {
            fixture(file, versions[0], versions[1], versions[2], current, List.of("old"), tail().toBits(), false);
            rejects(() -> EmbeddingCacheFile.read(file, 8));
        }
        for (int length : List.of(256, 384, 767, 769)) {
            fixture(file, 1, 2, 2, current, List.of("bad"), new int[length], false);
            rejects(() -> EmbeddingCacheFile.read(file, 8));
        }
        for (float value : List.of(0f, .5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            int[] bits = new int[768]; bits[767] = Float.floatToIntBits(value);
            fixture(file, 1, 2, 2, current, List.of("bad"), bits, false);
            rejects(() -> EmbeddingCacheFile.read(file, 8));
        }
        for (var keys : List.of(List.of("same", "same"), List.of(""), List.of(" "), List.of("x".repeat(8193)))) {
            fixture(file, 1, 2, 2, current, keys, tail().toBits(), false);
            rejects(() -> EmbeddingCacheFile.read(file, 8));
        }
        fixture(file, 1, 2, 2, current, List.of("valid"), tail().toBits(), true);
        rejects(() -> EmbeddingCacheFile.read(file, 8));
        EmbeddingCacheFile.write(file, Map.of("valid", tail())); byte[] bytes = Files.readAllBytes(file); bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes); rejects(() -> EmbeddingCacheFile.read(file, 8));
        for (int length : List.of(0, 31, 64, bytes.length - 1)) {
            Files.write(file, Arrays.copyOf(bytes, length)); rejects(() -> EmbeddingCacheFile.read(file, 8));
        }
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) { sparse.setLength(EmbeddingCacheFile.MAX_BYTES + 1); }
        rejects(() -> EmbeddingCacheFile.read(file, 8));
        EmbeddingCacheFile.write(file, Map.of("preserved", tail())); byte[] preserved = Files.readAllBytes(file);
        rejects(() -> EmbeddingCacheFile.write(file, Map.of("bad", Vec384f.ZERO())));
        check(Arrays.equals(preserved, Files.readAllBytes(file)), "Invalid replacement cannot alter last complete cache");
        rejects(() -> EmbeddingCacheFile.write(file, Map.of("bad", new Vec384f(tail().data(), "foreign"))));
        rejects(() -> EmbeddingCacheFile.write(file, Map.of(" ", tail())));
        // Corruption in a late entry must not leak an earlier valid entry into the engine cache.
        fixture(file, 1, 2, 2, current, List.of("valid", "valid"), tail().toBits(), false);
        Provider provider = new Provider();
        try (var engine = engine(provider, 8, file)) {
            get(engine.readiness()); check(engine.cacheSize() == 0, "All-or-nothing cache restoration");
            get(engine.embed("valid")); check(provider.calls.get() == 1, "No partial cache hit after late decode failure");
        }
    }
    private static void deadlines() throws Exception {
        Path file = directory.resolve("deadlines.bin"); EmbeddingCacheFile.write(file, Map.of("saved", tail()));
        byte[] original = Files.readAllBytes(file);
        Provider slowReady = new Provider() {
            @Override public void checkReady() {
                healthChecks.incrementAndGet(); started.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        };
        try (var engine = new AsyncEmbeddingEngine(slowReady, 1, 8, 8, Duration.ofMillis(100), file)) {
            check(slowReady.started.await(1, TimeUnit.SECONDS), "Gated readiness started");
            failed(engine.readiness()); failed(engine.embed("saved"));
            check(!engine.isReady() && engine.cacheSize() == 0, "Readiness timeout cannot certify cached vectors");
        }
        check(Arrays.equals(original, Files.readAllBytes(file)), "Expired readiness cannot overwrite good cache");
        Provider slow = new Provider(); slow.holdWork = true;
        try (var engine = new AsyncEmbeddingEngine(slow, 1, 8, 8, Duration.ofMillis(200), file)) {
            get(engine.readiness()); var expired = engine.embed("expired");
            check(slow.started.await(1, TimeUnit.SECONDS), "Gated inference started"); failed(expired);
            slow.release.countDown(); check(slow.returned.await(1, TimeUnit.SECONDS), "Late inference really returned a vector");
            check(engine.inflightSize() == 0 && engine.cacheSize() == 1, "Late success cannot revive/cache expired work");
        }
        check(EmbeddingCacheFile.read(file, 8).keySet().equals(Set.of("saved")), "Expired inference is never persisted");
    }
    private static void failureAndLifecycle() throws Exception {
        Path file = directory.resolve("health.bin"); EmbeddingCacheFile.write(file, Map.of("saved", tail()));
        byte[] original = Files.readAllBytes(file); Provider unhealthy = new Provider(); unhealthy.failReady = true;
        try (var engine = engine(unhealthy, 8, file)) {
            failed(engine.readiness()); failed(engine.embed("saved")); check(engine.cacheSize() == 0, "Unhealthy provider cannot advertise cached work");
        }
        check(Arrays.equals(original, Files.readAllBytes(file)), "Failed readiness must not erase a good cache");
        Provider broken = new Provider(); broken.failWork = true;
        try (var engine = engine(broken, 8, file)) {
            get(engine.readiness()); failed(engine.embed("failed"));
            broken.failWork = false; get(engine.embed("failed")); check(broken.calls.get() == 2, "Failures are not negatively cached");
        }
        Provider held = new Provider(); held.holdWork = true;
        var engine = engine(held, 8, file); get(engine.readiness()); var pending = engine.embed("shutdown");
        check(held.started.await(1, TimeUnit.SECONDS), "Work started before close"); engine.close(); failed(pending);
        check(!EmbeddingCacheFile.read(file, 8).containsKey("shutdown"), "Cancelled shutdown work is never persisted");
        check(engine.cacheSize() == 0 && engine.inflightSize() == 0, "Shutdown still clears process resources");
        byte[] saved = Files.readAllBytes(file); engine.close(); check(Arrays.equals(saved, Files.readAllBytes(file)), "Idempotent close");
        // Unwritable/corrupt cache IO is expendable and cannot take healthy inference down.
        Path badPath = directory.resolve("directory-as-cache"); Files.createDirectories(badPath);
        Provider healthy = new Provider();
        try (var usable = engine(healthy, 8, badPath)) { get(usable.readiness()); get(usable.embed("fresh")); }
        check(healthy.calls.get() == 1 && Files.isDirectory(badPath), "Failed load/save preserves inference and existing path");
        try (var files = Files.list(directory)) { check(files.noneMatch(path -> path.toString().endsWith(".tmp")), "Atomic writer cleans temporary cache files"); }
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("build", "embedding-cache-tests"));
        directory = Files.createTempDirectory(Path.of("build", "embedding-cache-tests"), "run-");
        restartAndEviction(); invalidation(); deadlines(); failureAndLifecycle();
        System.out.println("PASS embedding cache reuse: " + assertions + " assertions; fixtures " + directory);
    }
}
