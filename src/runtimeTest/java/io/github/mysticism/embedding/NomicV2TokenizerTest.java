package io.github.mysticism.embedding;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Full vocabulary/normalizer reference parity, not a toy WordPiece fixture. */
public final class NomicV2TokenizerTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void rejectsAsset(byte[] asset) throws Exception {
        try { NomicV2Tokenizer.load(new ByteArrayInputStream(asset)); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Incompatible tokenizer asset accepted");
    }
    private static JsonObject fixtures() throws Exception {
        var stream = NomicV2TokenizerTest.class.getResourceAsStream("/nomic-v2-tokenizer-golden.json");
        if (stream == null) throw new AssertionError("Golden fixture resource not discovered");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
    private static void golden(NomicV2Tokenizer tokenizer) throws Exception {
        JsonObject fixture = fixtures();
        check(fixture.get("tokenizerSha256").getAsString().equals(NomicV2Tokenizer.TOKENIZER_SHA256), "Reference tokenizer pin");
        check(fixture.get("reference").getAsString().equals("huggingface/tokenizers 0.23.2"), "Reference algorithm/Unicode pin");
        var cases = fixture.getAsJsonArray("cases");
        check(cases.size() >= 1800, "Expected multilingual, Unicode 17 grapheme, random Unicode and context-boundary reference coverage");
        int index = 0;
        for (var element : cases) {
            var test = element.getAsJsonObject();
            String text = test.get("text").getAsString();
            var expected = test.getAsJsonArray("ids");
            long[] ids = new long[expected.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = expected.get(i).getAsLong();
            var result = tokenizer.encode(text);
            check(Arrays.equals(ids, result.inputIds()), "Reference IDs case " + index + " (" + test.get("text") + "): expected " + Arrays.toString(ids) + ", got " + Arrays.toString(result.inputIds()));
            check(Arrays.stream(result.attentionMask()).allMatch(x -> x == 1), "Unpadded mask includes BOS/EOS and added tokens");
            check(Arrays.stream(result.tokenTypeIds()).allMatch(x -> x == 0), "Single-sequence type IDs must be zero");
            index++;
        }
        check(Arrays.equals(tokenizer.encodeDocument("oak log").inputIds(), tokenizer.encode("search_document: oak log").inputIds()), "Document prefix added exactly once to raw descriptor");
        check(!Arrays.equals(tokenizer.encodeDocument("oak log").inputIds(), tokenizer.encode("clustering: oak log").inputIds()), "No invented v1 clustering prefix");
        check(!Arrays.equals(tokenizer.encode("HELLO").inputIds(), tokenizer.encode("hello").inputIds()), "No BERT/v1 lowercasing");
    }
    private static void graphemeBreaks() throws Exception {
        var stream = NomicV2TokenizerTest.class.getResourceAsStream("/nomic-v2-grapheme-break-test.txt");
        if (stream == null) throw new AssertionError("Unicode 17 conformance fixture not discovered");
        var graphemes = new UnicodeGraphemes();
        int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String sample = line.split("#", 2)[0].strip();
                if (sample.isEmpty()) continue;
                var text = new StringBuilder();
                var expected = new ArrayList<Integer>();
                for (String part : sample.split("\\s+")) {
                    if (part.equals("÷")) expected.add(text.length());
                    else if (!part.equals("×")) text.appendCodePoint(Integer.parseInt(part, 16));
                }
                String input = text.toString();
                var actual = new ArrayList<Integer>();
                actual.add(0);
                for (int at = 0; at < input.length();) {
                    int next = graphemes.nextBoundary(input, at);
                    check(next > at, "Grapheme iterator makes progress");
                    actual.add(next); at = next;
                }
                check(actual.equals(expected), "Unicode 17 grapheme case " + count + ": " + sample
                        + "; expected " + expected + ", got " + actual);
                count++;
            }
        }
        check(count == 766, "Complete Unicode 17 grapheme conformance suite");
        System.out.println("PASS Unicode 17 grapheme boundaries: " + count + " official cases");
    }
    private static void tensorsAndLimits(NomicV2Tokenizer tokenizer) {
        var shortRow = tokenizer.encodeDocument("oak");
        var longRow = tokenizer.encodeDocument("oak log forest moss river");
        var batch = tokenizer.encodeDocuments(List.of("oak", "oak log forest moss river"));
        check(batch.size() == 2, "Batch cardinality");
        check(batch.get(0).inputIds().length == longRow.inputIds().length, "Pad to actual batch width");
        check(Arrays.equals(batch.get(1).inputIds(), longRow.inputIds()), "Longest batch row retained");
        long[] ids = batch.get(0).inputIds(), mask = batch.get(0).attentionMask();
        for (int i = 0; i < ids.length; i++) {
            check(ids[i] == (i < shortRow.inputIds().length ? shortRow.inputIds()[i] : 1), "XLM-R padding ID is 1, not BERT 0");
            check(mask[i] == (i < shortRow.inputIds().length ? 1 : 0), "Padding excluded from attention/mean pooling");
        }
        check(shortRow.inputIds()[0] == 0 && shortRow.inputIds()[shortRow.inputIds().length - 1] == 2, "XLM-R BOS/EOS, not [CLS]/[SEP]");
        check(tokenizer.encode("oak ".repeat(255)).inputIds().length == 512, "Exactly 512 tokens accepted including BOS/EOS");
        check(tokenizer.encodeDocument("oak ".repeat(253)).inputIds().length == 512, "Task prefix consumes context budget");
        rejects(() -> tokenizer.encode("oak ".repeat(256)), "Overflow must fail without clipping");
        rejects(() -> tokenizer.encodeDocument("oak ".repeat(254)), "Prefixed overflow must fail without clipping");
        rejects(() -> tokenizer.encodeDocuments(List.of("oak", "oak ".repeat(254))), "Batch must not partially succeed/truncate");
        rejects(() -> tokenizer.encodeDocuments(List.of()), "Empty batch rejected");
        rejects(() -> tokenizer.encodeDocuments(java.util.Collections.nCopies(129, "oak")), "Bound batch allocation");
        rejects(() -> tokenizer.encodeDocument(null), "Null descriptor");
        rejects(() -> tokenizer.encodeDocument("  \n\t"), "Blank descriptor");
        rejects(() -> tokenizer.encodeDocument("\u00a0\u0085\u2007\u202f"), "Unicode WhitespaceSplit blank descriptor");
        rejects(() -> tokenizer.encodeDocument("a".repeat(8193)), "Descriptor allocation bound");
        rejects(() -> tokenizer.encode("\ud800"), "Unpaired high surrogate must not be replaced by '?' silently");
        rejects(() -> tokenizer.encode("\udc00"), "Unpaired low surrogate");
        rejects(() -> tokenizer.encode("a".repeat(16385)), "Prepared input allocation bound");
        rejects(() -> new NomicV2Tokenizer.Encoding(new long[2], new long[1], new long[2]), "Invalid tensor shape");
        long[] external = {0, 2}, ones = {1, 1}, zeros = {0, 0};
        var copied = new NomicV2Tokenizer.Encoding(external, ones, zeros);
        external[0] = 99; ones[0] = 99; zeros[0] = 99;
        copied.inputIds()[0] = 100; copied.attentionMask()[0] = 100; copied.tokenTypeIds()[0] = 100;
        check(copied.inputIds()[0] == 0 && copied.attentionMask()[0] == 1 && copied.tokenTypeIds()[0] == 0, "Tensor inputs and accessors isolated");
        batch.get(0).inputIds()[0] = 100;
        check(batch.get(0).inputIds()[0] == 0 && shortRow.inputIds()[0] == 0, "Batch padding did not alias source tensors");
    }
    private static void concurrent(NomicV2Tokenizer tokenizer) throws Exception {
        long[] expected = tokenizer.encodeDocument("oak 🌲 CAFÉ 水 <mask> moss").inputIds();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> (Callable<Boolean>) () -> {
                for (int j = 0; j < 16; j++) if (!Arrays.equals(expected, tokenizer.encodeDocument("oak 🌲 CAFÉ 水 <mask> moss").inputIds())) return false;
                return true;
            }).toList();
            for (var future : executor.invokeAll(tasks, 10, TimeUnit.SECONDS)) check(future.get(), "Concurrent deterministic tokenization");
        }
    }
    public static void main(String[] args) throws Exception {
        check(NomicV2TokenizerTest.class.desiredAssertionStatus(), "Runner must enable assertions");
        rejectsAsset("{\"model\":{\"type\":\"WordPiece\"}}".getBytes(StandardCharsets.UTF_8));
        rejectsAsset(new byte[0]);
        rejectsAsset(new byte[18 * 1024 * 1024 + 1]);
        var tokenizer = NomicV2Tokenizer.bundled();
        graphemeBreaks(); golden(tokenizer); tensorsAndLimits(tokenizer); concurrent(tokenizer);
        System.out.println("PASS Nomic v2 tokenizer: " + checks + " checks; full pinned vocabulary, " + fixtures().getAsJsonArray("cases").size() + " HF reference cases; inference/gameplay NOT RUN");
    }
}
