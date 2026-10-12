package io.github.mysticism.embedding;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import net.ladenthin.llama.LlamaModel;

/** Exercises the real packaged JNI loader/ABI, without claiming a model forward pass. */
public final class EmbeddedNativeRuntimeSelfTest {
    public static void main(String[] args) throws Exception {
        Path base = Path.of("build/embedding-selftest/native").toAbsolutePath(); Files.createDirectories(base);
        System.setProperty(EmbeddedNativeRuntime.DIRECTORY, base.toString());
        String info = EmbeddedNativeRuntime.prepare();
        if (info == null || !info.equals(EmbeddedNativeRuntime.BUILD) || !info.equals(LlamaModel.getLlamaCppBuildInfo()))
            throw new AssertionError("Unexpected JNI runtime/build: " + info);
        Path loaded = EmbeddedNativeRuntime.loadedLibrary();
        if (loaded == null || !loaded.startsWith(base) || !Files.isRegularFile(loaded))
            throw new AssertionError("JNI did not load the private bundled CPU library: " + loaded);
        // JNI_OnLoad initializes LlamaModel recursively. Check the real mapped libraries,
        // not just the file we intended to load: a late property setup loads a second /tmp JNI.
        if (System.getProperty("os.name").equals("Linux")) {
            var maps = Files.readAllLines(Path.of("/proc/self/maps")).stream().filter(line -> line.contains("libjllama.so")).toList();
            if (maps.isEmpty() || maps.stream().anyMatch(line -> !line.contains(loaded.toString())))
                throw new AssertionError("JNI escaped private pinned extraction: " + maps);
        }
        if (!System.getProperty("net.ladenthin.llama.tmpdir").equals(loaded.getParent().resolve("loader-scratch").toString()))
            throw new AssertionError("Upstream cleanup is not confined to private scratch");
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return EmbeddedNativeRuntime.prepare(); } catch (Exception error) { throw new RuntimeException(error); }
        });
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return EmbeddedNativeRuntime.prepare(); } catch (Exception error) { throw new RuntimeException(error); }
        });
        if (!info.equals(first.get()) || !info.equals(second.get()) || !loaded.equals(EmbeddedNativeRuntime.loadedLibrary()))
            throw new AssertionError("Concurrent/repeated bootstrap replaced mapped JNI library");
        String[] argv = LlamaEmbeddingModel.parameters(Path.of("fixture.gguf"), 2).toArray();
        String flags = Arrays.toString(argv);
        var arguments = Arrays.asList(argv);
        for (String flag : new String[]{"--offline", "--embedding"})
            if (!arguments.contains(flag)) throw new AssertionError("Missing inference constraint " + flag + ": " + flags);
        for (var entry : Map.of("--pooling", "mean", "--ctx-size", "512", "--batch-size", "512", "--ubatch-size", "512",
                "--parallel", "1", "--gpu-layers", "0", "--fit", "off", "--threads", "2", "--threads-batch", "2").entrySet()) {
            int index = arguments.indexOf(entry.getKey());
            if (index < 0 || index + 1 >= arguments.size() || !arguments.get(index + 1).equals(entry.getValue()))
                throw new AssertionError("Wrong inference constraint " + entry + ": " + flags);
        }
        System.out.println("EmbeddedNativeRuntimeSelfTest passed: real JNI build/ABI " + info + "; NO model inference performed");
    }
}
