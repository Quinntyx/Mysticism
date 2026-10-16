package io.github.mysticism.embedding;

/** SPI for an administrator-provisioned, in-process native v2 runtime.
 * Register in META-INF/services/io.github.mysticism.embedding.EmbeddedRuntimeFactory.
 * Called on an embedding worker, never the Minecraft startup/tick thread. Implementations
 * must not install software, download weights, contact a service or change the profile.
 * All model/native/tokenizer files have absolute, verified paths, including jar resources.
 */
public interface EmbeddedRuntimeFactory {
    EmbeddingProvider open(EmbeddedRuntimeResources.Bundle bundle) throws Exception;
}
