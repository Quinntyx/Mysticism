package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.net.URLClassLoader;
import java.util.ServiceLoader;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;

/** Lazy bridge to a local runtime bundle or the normal bundled JNI runtime. Resource
 * IO and factory/model code run on the engine worker. Stop only signals cancellation;
 * close finishes teardown after worker drain, before the outer process lease is released.
 */
public final class EmbeddedEmbeddingProvider implements EmbeddingProvider {
    private final ClassLoader parent;
    private final ReentrantLock work = new ReentrantLock();
    private volatile boolean stopped;
    private volatile Handle handle;
    private boolean disposed;
    private Throwable closeFailure;

    public EmbeddedEmbeddingProvider() { this(EmbeddedEmbeddingProvider.class.getClassLoader()); }
    public EmbeddedEmbeddingProvider(ClassLoader parent) { this.parent = parent; }
    @Override public EmbeddingProfile profile() { return EmbeddingProfile.current(); }

    @Override public void checkReady() throws Exception {
        work.lockInterruptibly();
        try {
            requireRunning();
            if (handle != null) throw new IllegalStateException("Embedded readiness already started");
            var bundle = EmbeddedRuntimeResources.resolveIfPresent(parent).orElse(null);
            var jars = bundle == null ? java.util.List.<java.nio.file.Path>of() : bundle.runtimeJars();
            var urls = new java.net.URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            URLClassLoader loader = new URLClassLoader(urls, parent);
            try {
                EmbeddingProvider delegate = withLoader(loader, () -> {
                    if (bundle == null) return new EmbeddedEmbeddingService();
                    var factories = ServiceLoader.load(EmbeddedRuntimeFactory.class, loader).iterator();
                    if (!factories.hasNext()) return EmbeddedEmbeddingService.fromBundle(bundle);
                    EmbeddedRuntimeFactory found = factories.next();
                    if (factories.hasNext()) throw new IllegalStateException("Ambiguous in-process EmbeddedRuntimeFactory in bundle " + bundle.origin());
                    return found.open(bundle);
                });
                if (delegate == null) throw new IllegalStateException("Embedded runtime returned no provider");
                handle = new Handle(delegate, loader); // engine owns failed startup resources too
            } catch (Throwable failure) {
                try { loader.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
                throw failure;
            }
            requireRunning();
            if (!profile().equals(withLoader(loader, handle.provider::profile))) throw new IllegalArgumentException("Embedded runtime profile mismatch");
            withLoader(loader, () -> { handle.provider.checkReady(); return null; });
            requireRunning();
        } finally { work.unlock(); }
    }

    @Override public Vec384f getEmbedding(String descriptor) throws Exception {
        work.lockInterruptibly();
        try {
            requireRunning();
            Handle current = handle;
            if (current == null) throw new IllegalStateException("Embedded runtime unavailable");
            Vec384f vector = withLoader(current.loader, () -> current.provider.getEmbedding(descriptor));
            EmbeddingSpace.requireCurrent(vector);
            if (vector.length() == 0) throw new EmbeddingModelException("Embedded runtime returned zero embedding");
            requireRunning();
            return vector;
        } finally { work.unlock(); }
    }

    @Override public void requestStop() {
        stopped = true;
        Handle current = handle;
        if (current != null) current.provider.requestStop();
    }
    private void requireRunning() {
        if (stopped || Thread.currentThread().isInterrupted()) throw new CancellationException("Embedded provider stopped");
    }
    @Override public void close() {
        requestStop();
        work.lock();
        try {
            if (!disposed) {
                disposed = true;
                Handle current = handle;
                if (current != null) {
                    try { withLoader(current.loader, () -> { current.provider.close(); return null; }); }
                    catch (Throwable failure) { closeFailure = failure; }
                    try { current.loader.close(); }
                    catch (Throwable failure) {
                        if (closeFailure == null) closeFailure = failure;
                        else closeFailure.addSuppressed(failure);
                    }
                    handle = null;
                }
            }
            if (closeFailure instanceof Error failure) throw failure;
            if (closeFailure != null) throw new IllegalStateException("Closing embedded runtime", closeFailure);
        } finally { work.unlock(); }
    }

    private interface Operation<T> { T run() throws Exception; }
    private static <T> T withLoader(ClassLoader loader, Operation<T> operation) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try { return operation.run(); }
        finally { thread.setContextClassLoader(previous); }
    }
    private record Handle(EmbeddingProvider provider, URLClassLoader loader) {}
}
