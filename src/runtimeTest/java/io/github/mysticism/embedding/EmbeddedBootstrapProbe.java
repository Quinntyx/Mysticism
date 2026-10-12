package io.github.mysticism.embedding;

import java.io.IOException;

/** Fresh JVM failure fixture, invoked by EmbeddedPlatformTest, not a skipped suite. */
public final class EmbeddedBootstrapProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected diagnostic code");
        if (Boolean.getBoolean("mysticism.selftest.invalidNativeDirectory"))
            System.setProperty(EmbeddedNativeRuntime.DIRECTORY, "\u0000invalid-native-directory");
        // Production construction and readiness must report the platform error before asset IO.
        System.setProperty("mysticism.embedding.modelPath", "\u0000invalid-model-path-must-not-be-parsed");
        try (var provider = new EmbeddedEmbeddingService()) {
            try { provider.checkReady(); throw new AssertionError("Bootstrap unexpectedly ready"); }
            catch (IOException expected) {
                if (!expected.getMessage().contains("[" + args[0] + "]")) throw new AssertionError("Wrong platform failure", expected);
                System.out.println("Fresh JVM preflight rejected before weight access: " + expected.getMessage());
                if (args[0].equals("EXTRACTION")) {
                    try { provider.checkReady(); throw new AssertionError("Failed bootstrap revived"); }
                    catch (IOException repeated) {
                        if (repeated != expected) throw new AssertionError("Lost terminal diagnostic", repeated);
                    }
                }
            }
        }
    }
}
