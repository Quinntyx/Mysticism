package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;

/** The exact GGUF (weights AND tokenizer) in the approved Nomic v2 manifest.
 * Asset IO is worker-only. No Ollama process, moving tag, installer or alternative model.
 */
final class PinnedEmbeddingModel implements AutoCloseable {
    static final String MANIFEST = "ff9c2f10ef5e3722623a1b396e1e04efc27a93112c83e9b7b7b9ca1d05620965";
    static final String SHA256 = "a5db3381f2e514d3490a3a31fe70eb1a65e95016c85c6c2c23223b810806594f";
    static final long BYTES = 957680480L;
    static final URI SOURCE = URI.create("https://registry.ollama.ai/v2/library/nomic-embed-text-v2-moe/blobs/sha256:" + SHA256);
    private final Path target;
    private final URI source;
    private final String hash;
    private final long bytes, timeoutNanos;
    private final boolean download;
    private volatile boolean closed;
    private volatile HttpURLConnection connection;

    static void requireProfile() {
        if (!EmbeddingSpace.REVISION.equals(MANIFEST) || !EmbeddingSpace.MODEL.equals("nomic-embed-text-v2-moe:latest"))
            throw new IllegalArgumentException("Embedded inference requires the pinned Nomic v2 profile");
    }
    static PinnedEmbeddingModel configured() {
        requireProfile();
        String explicit = System.getProperty("mysticism.embedding.modelPath");
        if (explicit != null && (explicit.isBlank() || !Path.of(explicit).isAbsolute()))
            throw new IllegalArgumentException("mysticism.embedding.modelPath must be an absolute local path (not relative to Minecraft's run directory)");
        Path home = Path.of(System.getProperty("user.home"));
        Path existing = home.resolve(".ollama/models/blobs/sha256-" + SHA256);
        Path path = explicit != null ? Path.of(explicit) : Files.isRegularFile(existing) ? existing :
                home.resolve(".cache/mysticism/models/sha256-" + SHA256 + ".gguf");
        boolean download = explicit == null && Boolean.parseBoolean(System.getProperty("mysticism.embedding.download", "true"));
        return new PinnedEmbeddingModel(path, SOURCE, SHA256, BYTES, download, Duration.ofMinutes(10));
    }

    // Package-private asset injection for small local transport/integrity fixtures, never native inference.
    PinnedEmbeddingModel(Path target, URI source, String hash, long bytes, boolean download, Duration timeout) {
        if (!hash.matches("[a-f0-9]{64}") || bytes < 1 || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid pinned asset");
        this.target = target.toAbsolutePath(); this.source = source; this.hash = hash;
        this.bytes = bytes; this.download = download; timeoutNanos = timeout.toNanos();
    }

    Path resolve() throws Exception {
        long end = System.nanoTime() + timeoutNanos;
        check(end);
        if (Files.exists(target)) { verify(target, end); return target; }
        if (!download) throw new FileNotFoundException("Pinned Nomic v2 GGUF missing: " + target +
                "; provision this exact SHA256 or enable mysticism.embedding.download (no server is needed)");
        Files.createDirectories(target.getParent());
        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        // Serialize cache publication across JVMs. Never lock a server tick or lifecycle monitor.
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            while (lock == null) {
                check(end);
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { /* another worker */ }
                if (lock == null) Thread.sleep(100);
            }
            try {
                if (Files.exists(target)) { verify(target, end); return target; }
                Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".part");
                try {
                    fetch(temporary, end);
                    verify(temporary, end);
                    check(end);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    return target;
                } finally { Files.deleteIfExists(temporary); } // only our own unpublished asset
            } finally { lock.release(); }
        }
    }

    private void fetch(Path file, long end) throws Exception {
        URI uri = source;
        for (int redirects = 0; redirects <= 5; redirects++) {
            check(end);
            if (!uri.getScheme().equals("https") && !(source.getScheme().equals("http") && uri.getScheme().equals("http")))
                throw new IOException("Unsafe model redirect");
            HttpURLConnection current = (HttpURLConnection) uri.toURL().openConnection();
            current.setInstanceFollowRedirects(false);
            current.setConnectTimeout(10000); current.setReadTimeout(10000);
            connection = current;
            try {
                check(end);
                int status = current.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = current.getHeaderField("Location");
                    if (location == null) throw new IOException("Model redirect without Location");
                    uri = uri.resolve(location); continue;
                }
                if (status != 200) throw new IOException("Pinned model download HTTP " + status);
                long declared = current.getContentLengthLong();
                if (declared != -1 && declared != bytes) throw new IOException("Pinned model size mismatch");
                try (InputStream in = current.getInputStream(); OutputStream out = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[65536]; long total = 0;
                    for (int n; (n = in.read(buffer)) != -1;) {
                        check(end); total += n;
                        if (total > bytes) throw new IOException("Oversized pinned model download");
                        out.write(buffer, 0, n);
                    }
                    if (total != bytes) throw new IOException("Incomplete pinned model download");
                }
                return;
            } finally { current.disconnect(); connection = null; }
        }
        throw new IOException("Too many model redirects");
    }

    private void verify(Path file, long end) throws Exception {
        if (Files.size(file) != bytes) throw new IOException("Pinned Nomic v2 GGUF size mismatch: " + file);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) { check(end); digest.update(buffer, 0, n); }
        }
        check(end);
        if (!hash.equals(HexFormat.of().formatHex(digest.digest())))
            throw new IOException("Pinned Nomic v2 GGUF SHA256 mismatch (refusing changed weights/tokenizer): " + file);
    }
    private void check(long end) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Model provisioning cancelled");
        if (System.nanoTime() - end >= 0) throw new java.net.SocketTimeoutException("Pinned model provisioning deadline");
    }
    @Override public void close() {
        closed = true;
        HttpURLConnection current = connection;
        if (current != null) current.disconnect();
    }
}
