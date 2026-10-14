package io.github.mysticism.embedding;

import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Vec384f;
import java.net.URLClassLoader;
import java.util.ServiceLoader;

/** Lazy bridge to a local runtime bundle. Resource hashing, jar loading and model startup
 * happen in checkReady on the async engine's worker. No implicit HTTP fallback.
 */
public final class EmbeddedEmbeddingProvider implements EmbeddingProvider {
    private final ClassLoader parent;
    private boolean starting, closed;
    private int active;
    private Handle handle, preparing;

    public EmbeddedEmbeddingProvider() { this(EmbeddedEmbeddingProvider.class.getClassLoader()); }
    public EmbeddedEmbeddingProvider(ClassLoader parent) { this.parent = parent; }
    @Override public EmbeddingProfile profile() { return EmbeddingProfile.current(); }

    @Override public void checkReady() throws Exception {
        synchronized (this) {
            if (closed) throw new IllegalStateException("Embedded provider closed");
            if (starting) throw new IllegalStateException("Embedded readiness already started");
            starting = true;
        }
        Handle loaded = null;
        URLClassLoader loader = null;
        try {
            var bundle = EmbeddedRuntimeResources.resolveIfPresent(parent).orElse(null);
            var jars = bundle == null ? java.util.List.<java.nio.file.Path>of() : bundle.runtimeJars();
            var urls = new java.net.URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            loader = new URLClassLoader(urls, parent);
            URLClassLoader runtimeLoader = loader;
            EmbeddingProvider delegate = withLoader(runtimeLoader, () -> {
                // The normal launch already has the pinned JNI runtime on Loom's classpath.
                // A resource bundle needs no separately provisioned adapter for this model.
                if (bundle == null) return new EmbeddedEmbeddingService();
                var factories = ServiceLoader.load(EmbeddedRuntimeFactory.class, runtimeLoader).iterator();
                if (!factories.hasNext()) return EmbeddedEmbeddingService.fromBundle(bundle);
                EmbeddedRuntimeFactory found = factories.next();
                if (factories.hasNext()) throw new IllegalStateException("Ambiguous in-process EmbeddedRuntimeFactory in bundle " + bundle.origin());
                return found.open(bundle);
            });
            if (delegate == null) throw new IllegalStateException("Embedded runtime returned no provider");
            loaded = new Handle(delegate, loader);
            loader = null; // Handle now owns classloader on every success/failure path.
            synchronized (this) {
                if (closed) throw new IllegalStateException("Embedded provider closed during startup");
                preparing = loaded;
            }
            if (!profile().equals(withLoader(runtimeLoader, delegate::profile))) throw new IllegalArgumentException("Embedded runtime profile mismatch");
            // TCCL also exposes engine/service/native resources to dependencies of the runtime.
            withLoader(runtimeLoader, () -> { delegate.checkReady(); return null; });
            // Runtime must perform real native v2 inference, not just file discovery.
            synchronized (this) {
                if (closed) throw new IllegalStateException("Embedded provider closed during startup");
                handle = loaded;
                preparing = null;
                loaded = null;
            }
        } finally {
            synchronized (this) { if (preparing == loaded) preparing = null; }
            if (loaded != null) loaded.close();
            if (loader != null) loader.close();
        }
    }

    @Override public Vec384f getEmbedding(String descriptor) throws Exception {
        Handle current;
        synchronized (this) {
            if (closed || handle == null) throw new IllegalStateException("Embedded runtime unavailable");
            current = handle; active++;
        }
        try {
            Vec384f vector = withLoader(current.loader, () -> current.provider.getEmbedding(descriptor));
            EmbeddingSpace.requireCurrent(vector);
            if (vector.length() == 0) throw new IllegalArgumentException("Embedded runtime returned zero embedding");
            return vector;
        } finally {
            Handle dispose = null;
            synchronized (this) {
                active--;
                if (closed && active == 0) { dispose = handle; handle = null; }
            }
            if (dispose != null) dispose.close();
        }
    }

    @Override public void close() {
        Handle dispose = null, cancel;
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancel = preparing;
            // Do not unload native resources while an inference is still using them.
            if (active == 0) { dispose = handle; handle = null; }
        }
        // Cancel loading/download promptly, but leave the startup worker owning its
        // classloader until readiness unwinds. The built-in JNI delegate defers native disposal.
        if (cancel != null) cancel.cancel();
        if (dispose != null) dispose.close();
    }

    private interface Operation<T> { T run() throws Exception; }
    private static <T> T withLoader(ClassLoader loader, Operation<T> operation) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try { return operation.run(); }
        finally { thread.setContextClassLoader(previous); }
    }

    private record Handle(EmbeddingProvider provider, URLClassLoader loader,
                          java.util.concurrent.atomic.AtomicBoolean cancelled) {
        Handle(EmbeddingProvider provider, URLClassLoader loader) {
            this(provider, loader, new java.util.concurrent.atomic.AtomicBoolean());
        }
        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            try { withLoader(loader, () -> { provider.close(); return null; }); }
            catch (Exception error) { throw new IllegalStateException("Closing embedded runtime", error); }
        }
        void close() {
            RuntimeException failure = null;
            try { cancel(); }
            catch (Exception error) { failure = new IllegalStateException("Closing embedded runtime", error); }
            finally {
                try { loader.close(); }
                catch (java.io.IOException error) {
                    if (failure != null) failure.addSuppressed(error);
                    else failure = new IllegalStateException("Closing embedded runtime classloader", error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
