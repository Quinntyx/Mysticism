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
    interface Asset extends AutoCloseable {
        Path resolve() throws Exception;
        default Model load(Loader loader, BooleanSupplier cancelled) throws Exception {
            Path path = resolve();
            if (cancelled.getAsBoolean()) throw new CancellationException("Native v2 asset loading cancelled");
            return loader.load(path, cancelled);
        }
        @Override void close();
    }
    private final Asset asset;
    private final Loader loader;
    private final ReentrantLock work = new ReentrantLock();
    private volatile boolean closed;
    private Model model; // guarded by work, including JNI lifetime
    private boolean disposed;
    private Throwable disposalFailure;

    public EmbeddedEmbeddingService() {
        asset = new LocalNomicV2ModelAsset();
        loader = LlamaEmbeddingModel::new;
    }
    EmbeddedEmbeddingService(Asset asset, Loader loader) { this.asset = asset; this.loader = loader; }
    @Override public EmbeddingProfile profile() { return EmbeddingProfile.current(); }

    @Override public void checkReady() throws Exception {
        work.lockInterruptibly();
        try {
            requireOpen();
            if (model != null) return;
            Model candidate = asset.load((path, cancelled) -> track(loader.load(path, cancelled)),
                    () -> closed || Thread.currentThread().isInterrupted());
            try {
                requireOpen();
                if (candidate.dimensions() != EmbeddingSpace.NATIVE_DIMENSIONS)
                    throw new IllegalArgumentException("Nomic v2 model must have native 768 dimensions");
                infer(candidate, "readiness probe"); // actual model execution, not a file/executor flag
                requireOpen();
                model = candidate; candidate = null;
            } finally { if (candidate != null) candidate.close(); }
        } finally { work.unlock(); }
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
        } finally { work.unlock(); }
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
    /** Track even post-load verification failure cleanup performed inside the asset.
     * A failed candidate teardown must poison the lifecycle lease just like normal close. */
    private Model track(Model delegate) {
        if (delegate == null) throw new IllegalStateException("Native loader returned no model");
        return new Model() {
            public int dimensions() { return delegate.dimensions(); }
            public int tokens(String input) { return delegate.tokens(input); }
            public float[] embed(String input) { return delegate.embed(input); }
            public void close() {
                try { delegate.close(); }
                catch (RuntimeException | Error failure) { recordDisposalFailure(failure); throw failure; }
            }
        };
    }
    private void recordDisposalFailure(Throwable failure) {
        if (disposalFailure == null) disposalFailure = failure;
        else if (disposalFailure != failure) disposalFailure.addSuppressed(failure);
    }
    @Override public void requestStop() { closed = true; }
    @Override public void close() {
        requestStop();
        // The ENGINE never calls this on a server/deadline thread or while work is active.
        // Complete teardown here, not in another future: the outer process lease must be
        // retained through actual JNI close, including a hung or failed native teardown.
        work.lock();
        try {
            if (!disposed) {
                disposed = true;
                try { asset.close(); }
                catch (RuntimeException | Error failure) { recordDisposalFailure(failure); }
                Model previous = model; model = null;
                if (previous != null) try { previous.close(); }
                catch (RuntimeException | Error failure) { /* tracked close retained the cause */ }
            }
            if (disposalFailure instanceof RuntimeException failure) throw failure;
            if (disposalFailure instanceof Error failure) throw failure;
        } finally { work.unlock(); }
    }
}
