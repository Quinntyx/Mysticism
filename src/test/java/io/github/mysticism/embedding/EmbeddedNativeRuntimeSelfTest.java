package io.github.mysticism.embedding;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import net.ladenthin.llama.LlamaModel;

/** Exercises the real packaged JNI loader/ABI, without claiming a model forward pass. */
public final class EmbeddedNativeRuntimeSelfTest {
    public static void main(String[] args) throws Exception {
        Path base = Path.of("build/embedding-selftest").toAbsolutePath(); Files.createDirectories(base);
        System.setProperty("net.ladenthin.llama.tmpdir", Files.createTempDirectory(base, "jni-").toString());
        String info = LlamaModel.getLlamaCppBuildInfo();
        if (info == null || !info.startsWith("b10682-"))
            throw new AssertionError("Unexpected JNI runtime/build: " + info);
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
