package io.github.mysticism.embedding;

import java.nio.file.Path;
import java.io.IOException;
import com.google.gson.JsonParser;
import java.util.function.BooleanSupplier;
import net.ladenthin.llama.LlamaModel;
import net.ladenthin.llama.args.PoolingType;
import net.ladenthin.llama.parameters.ModelParameters;

/** JNI is loaded only when a preparation worker creates this adapter. */
final class LlamaEmbeddingModel implements EmbeddedEmbeddingService.Model {
    private final LlamaModel model;
    LlamaEmbeddingModel(Path path, BooleanSupplier cancelled) throws IOException {
        EmbeddedNativeRuntime.prepare();
        int threads = Integer.getInteger("mysticism.embedding.nativeThreads", Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
        if (threads < 1 || threads > 32) throw new IllegalArgumentException("nativeThreads must be 1..32");
        model = new LlamaModel(parameters(path, threads), progress -> !cancelled.getAsBoolean());
    }
    static ModelParameters parameters(Path path, int threads) {
        return new ModelParameters().setModel(path.toString()).setOffline(true).setFit(false)
                .enableEmbedding().setPoolingType(PoolingType.MEAN)
                .setCtxSize(512).setBatchSize(512).setUbatchSize(512).setParallel(1)
                .setThreads(threads).setThreadsBatch(threads).setGpuLayers(0);
    }
    public int dimensions() { return model.getModelMeta().getNEmbd(); }
    public int tokens(String input) {
        // encode() omits BOS/EOS, whereas embedding tokenization adds them.
        // Count the exact embedding token sequence, not just the text-piece tokens.
        return decodeTokenCount(model.handleTokenize(input, true, false));
    }
    static int decodeTokenCount(String response) {
        if (response == null || response.length() > 262144) throw new IllegalArgumentException("Invalid tokenizer response");
        var tokens = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("tokens");
        if (tokens == null || tokens.isEmpty()) throw new IllegalArgumentException("Tokenizer returned no tokens");
        return tokens.size();
    }
    public float[] embed(String input) { return model.embed(input); }
    public void close() { model.close(); }
}
