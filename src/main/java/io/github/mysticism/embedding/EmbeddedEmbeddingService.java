package io.github.mysticism.embedding;

import io.github.mysticism.vector.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Local CPU inference with the pinned GGUF's own tokenizer, mean pooling and native 768 output.
 * Construction does no IO or native loading. The async engine owns preparation/inference threads.
 */
public final class EmbeddedEmbeddingService implements EmbeddingProvider {
    interface Model extends AutoCloseable {
        int dimensions();
        int tokens(String input);
        /** One mean-pooled native sentence vector, not CLS/token rows or a reduced vector. */
        float[] embed(String input);
        @Override void close();
    }
    interface Loader { Model load(Path path, BooleanSupplier cancelled) throws Exception; }
    interface Asset extends AutoCloseable { Path resolve() throws Exception; @Override void close(); }
    private final Asset asset;
    private final Loader loader;
    private final ReentrantLock work = new ReentrantLock();
    private volatile boolean closed;
    private Model model; // guarded by work, including JNI lifetime

    public EmbeddedEmbeddingService() {
        asset = new Asset() {
            private PinnedEmbeddingModel pinned;
            private boolean stopped;
            public Path resolve() throws Exception {
                PinnedEmbeddingModel selected;
                synchronized (this) {
                    if (stopped) throw new CancellationException("Model provisioning cancelled");
                    // Even home/cache metadata checks belong on the preparation worker.
                    if (pinned == null) pinned = PinnedEmbeddingModel.configured();
                    selected = pinned;
                }
                return selected.resolve();
            }
            public void close() {
                PinnedEmbeddingModel selected;
                synchronized (this) { stopped = true; selected = pinned; }
                if (selected != null) selected.close();
            }
        };
        loader = LlamaEmbeddingModel::new;
    }
    EmbeddedEmbeddingService(Asset asset, Loader loader) { this.asset = asset; this.loader = loader; }
    @Override public EmbeddingProfile profile() { return EmbeddingProfile.current(); }

    @Override public void checkReady() throws Exception {
        work.lockInterruptibly();
        try {
            requireOpen();
            if (model != null) return;
            Path path = asset.resolve();
            requireOpen();
            Model candidate = loader.load(path, () -> closed || Thread.currentThread().isInterrupted());
            try {
                requireOpen();
                if (candidate.dimensions() != EmbeddingSpace.NATIVE_DIMENSIONS)
                    throw new IllegalArgumentException("Nomic v2 model must have native 768 dimensions");
                infer(candidate, "readiness probe"); // actual model execution, not a file/executor flag
                requireOpen();
                model = candidate; candidate = null;
            } finally { if (candidate != null) candidate.close(); }
        } finally { releaseIfClosed(); work.unlock(); }
    }
    @Override public Vec384f getEmbedding(String descriptor) throws Exception {
        validateDescriptor(descriptor);
        work.lockInterruptibly();
        try {
            requireOpen();
            if (model == null) throw new EmbeddingModelException("Embedded inference not prepared");
            Vec384f vector = infer(model, descriptor);
            requireOpen();
            return vector;
        } finally { releaseIfClosed(); work.unlock(); }
    }
    private static Vec384f infer(Model model, String descriptor) {
        validateDescriptor(descriptor);
        String input = "search_document: " + descriptor;
        // Use the actual GGUF tokenizer (including special tokens), never a character heuristic.
        int tokens;
        try { tokens = model.tokens(input); }
        catch (RuntimeException | LinkageError failure) {
            throw modelFailure("tokenization", failure);
        }
        if (tokens < 1) throw new EmbeddingModelException("Embedded native v2 tokenizer returned no tokens");
        // A valid tokenizer reporting input overflow is a descriptor error, not model loss.
        if (tokens > NativeEmbeddingOutput.MAX_TOKENS)
            throw new IllegalArgumentException("Nomic v2 input must have 1..512 tokens; truncation is forbidden");
        try { return decodeEmbedding(model.embed(input)); }
        catch (RuntimeException | LinkageError failure) {
            throw modelFailure("inference/output validation", failure);
        }
    }
    private static EmbeddingModelException modelFailure(String operation, Throwable failure) {
        if (failure instanceof EmbeddingModelException modelFailure) return modelFailure;
        return new EmbeddingModelException("Embedded native v2 " + operation + " failed: "
                + EmbeddingReadiness.describe(failure), failure);
    }
    private static void validateDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.length() > 8192)
            throw new IllegalArgumentException("Descriptor must be nonempty and at most 8192 characters");
        for (int i = 0; i < descriptor.length(); i++) {
            char value = descriptor.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i == descriptor.length() || !Character.isLowSurrogate(descriptor.charAt(i)))
                    throw new IllegalArgumentException("Descriptor contains unpaired UTF-16 surrogate");
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("Descriptor contains unpaired UTF-16 surrogate");
            }
        }
    }
    static Vec384f decodeEmbedding(float[] values) {
        // llama.cpp performs MEAN pooling. Treat its result as a sentence tensor: never
        // pool again, select CLS, resize, layer-normalize (v1), or cast before stable L2.
        return NativeEmbeddingOutput.fromTensor(values, new long[]{1, EmbeddingSpace.NATIVE_DIMENSIONS}, null);
    }
    private void requireOpen() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Embedded inference closed/cancelled");
    }
    private void releaseIfClosed() {
        if (closed && model != null) { Model previous = model; model = null; previous.close(); }
    }
    @Override public void close() {
        closed = true;
        // Native loading/execution may not honour interrupts immediately. Do not wait for it
        // (or deallocate underneath JNI) on a Minecraft lifecycle/tick thread.
        CompletableFuture.runAsync(asset::close);
        if (work.tryLock()) {
            Model previous;
            try { previous = model; model = null; } finally { work.unlock(); }
            if (previous != null) CompletableFuture.runAsync(previous::close);
        }
        // Otherwise the in-progress worker owns release in its finally block.
    }
}
