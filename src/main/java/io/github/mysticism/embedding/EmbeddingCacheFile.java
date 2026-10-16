package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Disposable descriptor cache, not world state or a model substitute. Only exact current-profile
 * native, finite, unit vectors survive. Disk data is staged completely before publication.
 * Call on startup workers / shutdown only, never from embed() or a server tick.
 */
final class EmbeddingCacheFile {
    private static final int MAGIC = 0x4d594543; // MYEC, separate from legacy embedding/index formats
    private static final int FORMAT = 1;
    static final int MAX_ENTRIES = 65536;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int DIGEST_BYTES = 32;
    private EmbeddingCacheFile() {}

    static boolean validDescriptor(String descriptor) {
        return descriptor != null && !descriptor.isBlank() && descriptor.length() <= 8192;
    }
    static void requireUnitVector(Vec384f vector) {
        EmbeddingSpace.requireCurrent(vector);
        // Re-normalizing stored input could hide incompatible scaling/corruption. Fresh inference
        // is normalized by the engine; cached floats must already meet that same contract.
        double squared = 0;
        for (float value : vector.data()) squared += (double)value * value;
        if (!Double.isFinite(squared) || Math.abs(squared - 1) > 1e-4)
            throw new IllegalArgumentException("Cached embedding is not a nonzero unit vector");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void header(DataOutputStream out) throws IOException {
        var profile = EmbeddingProfile.current();
        out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(EmbeddingSpace.SCHEMA);
        out.writeInt(EmbeddingSpace.DESCRIPTOR_VERSION);
        out.writeUTF(profile.model()); out.writeUTF(profile.revision()); out.writeInt(profile.dimensions());
        out.writeUTF(profile.semantics()); out.writeUTF(profile.fingerprint());
    }
    private static void readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readInt() != EmbeddingSpace.SCHEMA
                || in.readInt() != EmbeddingSpace.DESCRIPTOR_VERSION)
            throw new IOException("Obsolete embedding cache format/schema");
        var saved = new EmbeddingProfile(in.readUTF(), in.readUTF(), in.readInt(), in.readUTF(), in.readUTF());
        if (!EmbeddingProfile.current().equals(saved)) throw new IOException("Foreign embedding cache profile");
    }
    /** Access order is serialized oldest-first. Even an oversized valid cache retains its newest
     * capacity entries, but every entry and the checksum must validate before any can be reused. */
    static Map<String, Vec384f> read(Path path, int capacity) throws IOException {
        if (!Files.exists(path)) return Map.of();
        long size = Files.size(path);
        if (size < DIGEST_BYTES || size > MAX_BYTES) throw new IOException("Embedding cache size out of bounds");
        // Bound allocations even if another process replaces/grows the file during the read.
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes((int)MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES || bytes.length < DIGEST_BYTES) throw new IOException("Embedding cache size out of bounds");
        int payloadSize = bytes.length - DIGEST_BYTES;
        MessageDigest checksum = digest(); checksum.update(bytes, 0, payloadSize);
        if (!MessageDigest.isEqual(checksum.digest(), Arrays.copyOfRange(bytes, payloadSize, bytes.length)))
            throw new IOException("Embedding cache checksum mismatch");
        Map<String, Vec384f> staged = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadSize))) {
            readHeader(in);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Embedding cache entry count out of bounds");
            for (int entry = 0; entry < count; entry++) {
                String descriptor = in.readUTF();
                if (!validDescriptor(descriptor) || !seen.add(descriptor)) throw new IOException("Invalid/duplicate cached descriptor");
                if (in.readInt() != EmbeddingSpace.NATIVE_DIMENSIONS) throw new IOException("Cached vector is not native v2");
                float[] values = new float[EmbeddingSpace.NATIVE_DIMENSIONS];
                for (int i = 0; i < values.length; i++) values[i] = Float.intBitsToFloat(in.readInt());
                Vec384f vector = new Vec384f(values); requireUnitVector(vector);
                staged.put(descriptor, vector);
                while (staged.size() > capacity) staged.remove(staged.keySet().iterator().next());
            }
            if (in.available() != 0) throw new IOException("Trailing embedding cache payload");
        } catch (IllegalArgumentException error) { throw new IOException("Invalid cached embedding", error); }
        return staged;
    }
    /** Writes at most 64 MiB, retaining most-recent entries; an interrupted write cannot replace the
     * last complete cache. Cache corruption causes fresh inference, never partial activation. */
    static void write(Path path, Map<String, Vec384f> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) throw new IOException("Embedding cache entry count out of bounds");
        var encoded = new ArrayDeque<byte[]>();
        int headerSize;
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            header(out); out.writeInt(0); out.flush(); headerSize = bytes.size();
        }
        long total = headerSize + DIGEST_BYTES;
        for (var entry : entries.entrySet()) {
            if (!validDescriptor(entry.getKey())) throw new IOException("Invalid cached descriptor");
            try { requireUnitVector(entry.getValue()); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid cached embedding", error); }
            try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
                out.writeUTF(entry.getKey()); int[] bits = entry.getValue().toBits(); out.writeInt(bits.length);
                for (int value : bits) out.writeInt(value);
                out.flush(); byte[] record = bytes.toByteArray(); encoded.addLast(record); total += record.length;
            }
            // Bound transient encoded storage as well as the eventual file.
            while (total > MAX_BYTES) total -= encoded.removeFirst().length;
        }
        Path absolute = path.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        try {
            var checksum = digest();
            try (var file = Files.newOutputStream(temporary);
                 var digested = new DigestOutputStream(new BufferedOutputStream(file), checksum);
                 var out = new DataOutputStream(digested)) {
                header(out); out.writeInt(encoded.size());
                for (byte[] record : encoded) out.write(record);
                out.flush(); digested.on(false); out.write(checksum.digest());
            }
            // No non-atomic fallback: leaving the old cache is preferable to a torn replacement.
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
