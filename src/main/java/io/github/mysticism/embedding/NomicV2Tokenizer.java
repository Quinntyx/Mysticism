package io.github.mysticism.embedding;

import com.google.gson.stream.JsonReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * In-process tokenizer for the pinned Nomic v2-MoE XLM-R Unigram model. No BERT
 * lowercasing, WordPiece, v1 task prefixes, vocabulary ID shifts or truncation.
 * Immutable after loading; inference workers may share one instance.
 */
public final class NomicV2Tokenizer {
    public static final String TOKENIZER_SHA256 = "3a56def25aa40facc030ea8b0b87f3688e4b3c39eb8b45d5702b3a1300fe2a20";
    public static final int MAX_TOKENS = 512;
    public static final int BOS_ID = 0, PAD_ID = 1, EOS_ID = 2, UNK_ID = 3;
    public static final String DOCUMENT_PREFIX = "search_document: ";
    private static final int MAX_JSON_BYTES = 18 * 1024 * 1024;
    private static final String RESOURCE = "/assets/mysticism/embedding/nomic-v2-tokenizer.json.gz";
    private static final Pattern WORD = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAILING_SPACE = Pattern.compile("\\s+$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String[] SPECIAL = {"<s>", "<pad>", "</s>", "<unk>", "<mask>"};
    private static final int[] SPECIAL_IDS = {BOS_ID, PAD_ID, EOS_ID, UNK_ID, 250001};

    // Packed UTF-16 trie. Terminal paths always end at a Unicode scalar boundary.
    private final char[] label;
    private final int[] child, sibling, token;
    private final int[] root = new int[65536];
    private final double[] scores;
    private final double unknownScore;
    private final CharMap normalizer;

    private record Vocabulary(String[] pieces, double[] scores, byte[] charsmap) {}

    // Read only the needed fields instead of retaining a 250,002-entry Gson DOM.
    // The full raw-file SHA pins all skipped normalizer/pretokenizer/postprocessor settings.
    private static Vocabulary vocabulary(byte[] bytes) throws IOException {
        String[] pieces = new String[250002];
        double[] scores = new double[pieces.length];
        byte[] charsmap = null;
        int count = 0;
        String type = null;
        int unk = -1;
        boolean fallback = true;
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "normalizer" -> {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            if (reader.nextName().equals("precompiled_charsmap")) charsmap = Base64.getDecoder().decode(reader.nextString());
                            else reader.skipValue();
                        }
                        reader.endObject();
                    }
                    case "model" -> {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            switch (reader.nextName()) {
                                case "type" -> type = reader.nextString();
                                case "unk_id" -> unk = reader.nextInt();
                                case "byte_fallback" -> fallback = reader.nextBoolean();
                                case "vocab" -> {
                                    reader.beginArray();
                                    while (reader.hasNext()) {
                                        if (count == pieces.length) throw new IOException("Oversized v2 vocabulary");
                                        reader.beginArray(); pieces[count] = reader.nextString(); scores[count++] = reader.nextDouble(); reader.endArray();
                                    }
                                    reader.endArray();
                                }
                                default -> reader.skipValue();
                            }
                        }
                        reader.endObject();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
        }
        if (!"Unigram".equals(type) || unk != UNK_ID || fallback || count != pieces.length || charsmap == null)
            throw new IOException("Expected the native v2-MoE Unigram vocabulary");
        return new Vocabulary(pieces, scores, charsmap);
    }

    private NomicV2Tokenizer(Vocabulary vocabulary) throws IOException {
        normalizer = new CharMap(vocabulary.charsmap);
        scores = vocabulary.scores;
        String[] pieces = vocabulary.pieces;
        int longestPiece = 0;
        double min = Double.POSITIVE_INFINITY;
        for (int id = 0; id < pieces.length; id++) {
            min = Math.min(min, scores[id]);
            longestPiece = Math.max(longestPiece, pieces[id].length());
        }
        unknownScore = min - 10.0;
        // Sorting and sharing prefixes avoids a Java object/hash table per trie node.
        Integer[] order = new Integer[pieces.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> pieces[a].compareTo(pieces[b]));
        int nodes = 1;
        String previous = "";
        for (int id : order) {
            String piece = pieces[id];
            int common = 0;
            while (common < Math.min(previous.length(), piece.length()) && previous.charAt(common) == piece.charAt(common)) common++;
            nodes += piece.length() - common;
            previous = piece;
        }
        label = new char[nodes]; child = new int[nodes]; sibling = new int[nodes]; token = new int[nodes];
        Arrays.fill(token, -1);
        int[] path = new int[longestPiece + 1];
        int[] lastChild = new int[nodes];
        nodes = 1; previous = "";
        for (int id : order) {
            String piece = pieces[id];
            int common = 0;
            while (common < Math.min(previous.length(), piece.length()) && previous.charAt(common) == piece.charAt(common)) common++;
            for (int i = common; i < piece.length(); i++) {
                int parent = path[i], node = nodes++;
                label[node] = piece.charAt(i);
                if (child[parent] == 0) child[parent] = node;
                else sibling[lastChild[parent]] = node;
                lastChild[parent] = node;
                path[i + 1] = node;
                if (parent == 0) root[label[node]] = node;
            }
            token[path[piece.length()]] = id;
            previous = piece;
        }
    }

    /** Loads a provisioned tokenizer.json only if it is the selected model's exact asset. */
    public static NomicV2Tokenizer load(Path tokenizerJson) throws IOException {
        try (InputStream input = Files.newInputStream(tokenizerJson)) { return load(input); }
    }

    /** Does not close the caller's stream. Rejects incompatible v1/other tokenizer assets. */
    public static NomicV2Tokenizer load(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) throw new IOException("Oversized tokenizer.json");
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!TOKENIZER_SHA256.equals(actual)) throw new IOException("Nomic v2 tokenizer SHA256 mismatch: " + actual);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return new NomicV2Tokenizer(vocabulary(bytes));
    }

    /** Bundled model data, not model weights or a native/software runtime. No network access. */
    public static NomicV2Tokenizer bundled() throws IOException {
        InputStream resource = NomicV2Tokenizer.class.getResourceAsStream(RESOURCE);
        if (resource == null) throw new IOException("Missing bundled v2 tokenizer");
        try (InputStream input = new GZIPInputStream(resource)) { return load(input); }
    }

    /** Shared raw-descriptor preparation for local and explicitly selected HTTP providers. */
    public static String prepareDocument(String descriptor) {
        if (descriptor == null || descriptor.length() > 8192 || !WORD.matcher(descriptor).find())
            throw new IllegalArgumentException("Descriptor must be nonempty and at most 8192 characters");
        requireScalars(descriptor);
        return DOCUMENT_PREFIX + descriptor;
    }

    /** Raw descriptor in, prefixed document tokens out. Includes BOS/EOS in the 512-token limit. */
    public Encoding encodeDocument(String descriptor) {
        return encode(prepareDocument(descriptor));
    }

    /** Tokenizes an already prepared model input; does NOT add a task prefix. */
    public Encoding encode(String text) {
        if (text == null || text.length() > 16384) throw new IllegalArgumentException("Invalid/oversized model input");
        requireScalars(text);
        List<Integer> ids = new ArrayList<>(); ids.add(BOS_ID);
        // Added tokens are extracted before normalization (including <mask>'s lstrip).
        int start = 0, at = 0;
        while (at < text.length()) {
            int special = -1;
            for (int i = 0; i < SPECIAL.length; i++) if (text.startsWith(SPECIAL[i], at)) { special = i; break; }
            if (special < 0) { at += Character.charCount(text.codePointAt(at)); continue; }
            String preceding = text.substring(start, at);
            if (special == 4) preceding = TRAILING_SPACE.matcher(preceding).replaceFirst("");
            ordinary(preceding, ids);
            ids.add(SPECIAL_IDS[special]);
            at += SPECIAL[special].length(); start = at;
        }
        ordinary(text.substring(start), ids); ids.add(EOS_ID);
        if (ids.size() > MAX_TOKENS) throw new IllegalArgumentException("Nomic v2 context overflow: " + ids.size() + " tokens (limit 512); refusing truncation");
        long[] values = new long[ids.size()], mask = new long[ids.size()];
        for (int i = 0; i < values.length; i++) values[i] = ids.get(i);
        Arrays.fill(mask, 1);
        return new Encoding(values, mask, new long[values.length]);
    }

    /** Pads a batch to its actual longest row using XLM-R pad=1 and mask=0, never clips input. */
    public List<Encoding> encodeDocuments(List<String> descriptors) {
        if (descriptors == null || descriptors.isEmpty() || descriptors.size() > 128)
            throw new IllegalArgumentException("Expected 1..128 descriptors");
        List<Encoding> rows = descriptors.stream().map(this::encodeDocument).toList();
        int length = rows.stream().mapToInt(row -> row.inputIds.length).max().orElseThrow();
        List<Encoding> padded = new ArrayList<>(rows.size());
        for (Encoding row : rows) {
            long[] ids = Arrays.copyOf(row.inputIds, length);
            Arrays.fill(ids, row.inputIds.length, length, PAD_ID);
            padded.add(new Encoding(ids, Arrays.copyOf(row.attentionMask, length), new long[length]));
        }
        return List.copyOf(padded);
    }

    /** Defensive copies protect inference tensors when one tokenizer is shared by workers. */
    public record Encoding(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
        public Encoding {
            if (inputIds == null || attentionMask == null || tokenTypeIds == null
                    || inputIds.length != attentionMask.length || inputIds.length != tokenTypeIds.length)
                throw new IllegalArgumentException("Mismatched tokenizer tensor shapes");
            inputIds = inputIds.clone(); attentionMask = attentionMask.clone(); tokenTypeIds = tokenTypeIds.clone();
        }
        @Override public long[] inputIds() { return inputIds.clone(); }
        @Override public long[] attentionMask() { return attentionMask.clone(); }
        @Override public long[] tokenTypeIds() { return tokenTypeIds.clone(); }
    }

    private void ordinary(String text, List<Integer> ids) {
        var words = WORD.matcher(normalizer.normalize(text));
        while (words.find()) {
            String word = words.group();
            if (word.charAt(0) != '\u2581') word = "\u2581" + word;
            int start = 0;
            for (int i = 1; i < word.length(); i++) if (word.charAt(i) == '\u2581') {
                unigram(word.substring(start, i), ids); start = i;
            }
            unigram(word.substring(start), ids);
        }
    }

    private void unigram(String word, List<Integer> ids) {
        int length = word.length();
        double[] best = new double[length + 1];
        int[] from = new int[length + 1], chosen = new int[length + 1];
        Arrays.fill(from, -1);
        for (int start = 0; start < length; start += Character.charCount(word.codePointAt(start))) {
            int scalarEnd = start + Character.charCount(word.codePointAt(start));
            boolean single = false;
            int node = root[word.charAt(start)];
            for (int end = start + 1; node != 0; end++) {
                int id = token[node];
                if (id >= 0) {
                    update(end, start, id, best[start] + scores[id], from, chosen, best);
                    if (end == scalarEnd) single = true;
                }
                if (end == length) break;
                char next = word.charAt(end);
                node = child[node];
                while (node != 0 && label[node] < next) node = sibling[node];
                if (node != 0 && label[node] != next) node = 0;
            }
            if (!single) update(scalarEnd, start, UNK_ID, best[start] + unknownScore, from, chosen, best);
        }
        List<Integer> reverse = new ArrayList<>();
        for (int end = length; end > 0; end = from[end]) reverse.add(chosen[end]);
        boolean unknown = false;
        for (int i = reverse.size() - 1; i >= 0; i--) {
            int id = reverse.get(i);
            if (id != UNK_ID || !unknown) ids.add(id); // Fuse adjacent unknown scalars within this pretoken only.
            unknown = id == UNK_ID;
        }
    }

    private static void update(int end, int start, int id, double score, int[] from, int[] chosen, double[] best) {
        // Exact upstream tie policy: first path wins, not longest-piece/greedy selection.
        if (from[end] < 0 || score > best[end]) { from[end] = start; chosen[end] = id; best[end] = score; }
    }

    private static void requireScalars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
        }
    }

    /** Reads the model's SentencePiece Darts rewrite table; java.text.Normalizer is NOT equivalent. */
    private static final class CharMap {
        private final int[] units;
        private final byte[] replacements;
        private final UnicodeGraphemes graphemes;
        CharMap(byte[] bytes) throws IOException {
            graphemes = new UnicodeGraphemes();
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int size = buffer.getInt();
            if (size <= 0 || size % 4 != 0 || size > buffer.remaining()) throw new IllegalArgumentException("Invalid charsmap");
            units = new int[size / 4]; for (int i = 0; i < units.length; i++) units[i] = buffer.getInt();
            replacements = new byte[buffer.remaining()]; buffer.get(replacements);
        }
        String normalize(String text) {
            StringBuilder result = new StringBuilder();
            for (int start = 0; start < text.length();) {
                int end = graphemes.nextBoundary(text, start);
                String grapheme = text.substring(start, end);
                start = end;
                String replacement = grapheme.getBytes(StandardCharsets.UTF_8).length < 6 ? transform(grapheme) : null;
                if (replacement != null) { result.append(replacement); continue; }
                for (int i = 0; i < grapheme.length();) {
                    int scalarEnd = i + Character.charCount(grapheme.codePointAt(i));
                    String scalar = grapheme.substring(i, scalarEnd);
                    replacement = transform(scalar);
                    result.append(replacement == null ? scalar : replacement); i = scalarEnd;
                }
            }
            return result.toString();
        }
        String transform(String text) {
            int pos = offset(units[0]);
            for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                int key = b & 255;
                if (key == 0) break;
                pos ^= key;
                if (pos < 0 || pos >= units.length) return null;
                int unit = units[pos];
                if ((unit & 0x800000ff) != key) return null;
                pos ^= offset(unit);
                if ((unit & 256) != 0) {
                    int start = units[pos] & 0x7fffffff, end = start;
                    while (end < replacements.length && replacements[end] != 0) end++;
                    return new String(replacements, start, end - start, StandardCharsets.UTF_8);
                }
            }
            return null;
        }
        private static int offset(int unit) { return (unit >>> 10) << ((unit & 512) >>> 6); }
    }
}
