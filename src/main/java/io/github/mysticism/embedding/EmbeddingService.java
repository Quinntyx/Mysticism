package io.github.mysticism.embedding;

import ai.djl.Application;
import ai.djl.Model;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ai.djl.util.Progress;
import io.github.mysticism.vector.Vec384f;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.ServiceLoader;
import ai.djl.repository.zoo.ZooProvider;

import java.nio.file.Path;
import java.nio.file.Paths;

public class EmbeddingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mysticism-EmbeddingService");

    private Predictor<String, float[]> predictor;
    private ZooModel<String, float[]> model;

    public EmbeddingService() {
        LOGGER.info("Starting EmbeddingService initialization...");

        try {
            String svc = "META-INF/services/ai.djl.repository.zoo.ZooProvider";
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> urls = cl.getResources(svc);
            while (urls.hasMoreElements()) {
                URL u = urls.nextElement();
                LOGGER.info("[DJL] Service file: {}", u);
                try (InputStream in = u.openStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    LOGGER.info("[DJL] Service contents:\n{}", text);
                }
            }

            Thread.currentThread().setContextClassLoader(
                    ai.djl.repository.zoo.ZooProvider.class.getClassLoader()
            );

            ServiceLoader<ZooProvider> loader = ServiceLoader.load(ZooProvider.class);
            LOGGER.info("ZooProvider iface loaded by: " + ZooProvider.class.getClassLoader());

            for (ZooProvider p : loader) {
                Class<?> c = p.getClass();
                CodeSource cs = c.getProtectionDomain().getCodeSource();
                LOGGER.info("Found provider: " + c.getName());
                LOGGER.info("  from JAR: " + (cs == null ? "unknown" : cs.getLocation()));
                LOGGER.info("  classloader: " + c.getClassLoader());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        long startTime = System.currentTimeMillis();

        try {
            LOGGER.info("Creating predictor for embedding model...");
            this.model = loadModel();

            if (this.model != null) {
                this.predictor = this.model.newPredictor();

                long duration = System.currentTimeMillis() - startTime;
                LOGGER.info("EmbeddingService initialized successfully in {}ms", duration);

                // Test the service
                LOGGER.info("Running initialization test...");
                try {
                    float[] testEmbedding = this.predictor.predict("test");
                    LOGGER.info("Initialization test passed - embedding dimension: {}", testEmbedding.length);
                } catch (Exception e) {
                    LOGGER.error("Initialization test failed!", e);
                }
            } else {
                LOGGER.error("Failed to initialize model - model is null");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize the embedding model!", e);
            this.predictor = null;
            this.model = null;
        }
    }

    @Nullable
    private ZooModel<String, float[]> loadModel() {
        // Create progress tracker
        Progress progressTracker = new LoggingProgress();

        // Try with PyTorch zoo URL for MiniLM-L12-v2
        try {
            LOGGER.info("Attempting to load sentence-transformers/all-MiniLM-L12-v2...");
            Criteria<String, float[]> criteria = Criteria.builder()
                    .optApplication(Application.NLP.TEXT_EMBEDDING)
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L12-v2")
                    .optEngine("PyTorch")
                    .optProgress(progressTracker)
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build();

            return criteria.loadModel();
        } catch (Exception e) {
            LOGGER.warn("HuggingFace Zoo MiniLM loading failed: {}", e.getMessage());
        }

        // Fallback to any available text embedding model
        try {
            LOGGER.info("Fallback: Attempting to load any available text embedding model...");
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optApplication(ai.djl.Application.NLP.TEXT_EMBEDDING)
                    .optEngine("PyTorch")
                    .optProgress(progressTracker)
                    .build();

            ZooModel<String, float[]> model = criteria.loadModel();
            LOGGER.warn("Loaded fallback text embedding model instead of MiniLM-L12-v2");
            return model;
        } catch (Exception e) {
            LOGGER.error("Fallback text embedding model failed: {}", e.getMessage());
        }

        LOGGER.error("All model loading attempts failed");
        return null;
    }

    /**
     * Generates an embedding for a given text.
     * @param text The item name (e.g., "Diamond Sword").
     * @return A float array representing the vector, or null if prediction fails.
     */
    public Vec384f getEmbedding(String text) {
        if (predictor == null) {
            LOGGER.error("Predictor is not initialized, cannot get embedding for: {}", text);
            return null;
        }

        try {
            LOGGER.debug("Generating embedding for: {}", text);
            float[] result = predictor.predict(text);
            LOGGER.debug("Generated embedding with {} dimensions", result.length);
            return new Vec384f(result);
        } catch (TranslateException e) {
            LOGGER.error("Failed to generate embedding for text: {}", text, e);
            return null;
        }
    }

    public void close() {
        if (predictor != null) {
            LOGGER.info("Closing EmbeddingService predictor...");
            predictor.close();
        }
        if (model != null) {
            LOGGER.info("Closing EmbeddingService model...");
            model.close();
            LOGGER.info("EmbeddingService closed");
        }
    }

    /**
     * Custom Progress implementation that logs progress to our logger
     */
    private static class LoggingProgress implements Progress {
        private String currentTask = "";
        private long totalBytes = 0;
        private long lastUpdateTime = 0;
        private long lastProgress = 0;
        private static final long UPDATE_INTERVAL_MS = 1000; // Update every second

        @Override
        public void reset(String task, long max) {
            this.currentTask = task != null ? task : "Model Download";
            this.totalBytes = max;
            this.lastProgress = 0;
            this.lastUpdateTime = System.currentTimeMillis();
            LOGGER.info("Starting: {} (Total: {})", currentTask, max > 0 ? max + " bytes" : "unknown size");
        }

        @Override
        public void reset(String task, long max, String message) {
            reset(task, max);
            if (message != null && !message.isEmpty()) {
                LOGGER.info("Additional info: {}", message);
            }
        }

        @Override
        public void start(long initialProgress) {
            this.lastProgress = initialProgress;
            if (initialProgress > 0) {
                LOGGER.info("Resuming from: {} bytes", initialProgress);
            }
        }

        @Override
        public void end() {
            LOGGER.info("Completed: {}", currentTask);
        }

        @Override
        public void increment(long increment) {
            update(lastProgress + increment);
        }

        @Override
        public void update(long progress) {
            update(progress, null);
        }

        @Override
        public void update(long progress, String message) {
            this.lastProgress = progress;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateTime >= UPDATE_INTERVAL_MS) {
                lastUpdateTime = currentTime;

                if (totalBytes > 0) {
                    double percentage = (progress * 100.0) / totalBytes;
                    LOGGER.info("{}: {}% ({} / {} bytes) {}",
                            currentTask, String.format("%.1f", percentage), progress, totalBytes,
                            message != null ? " - " + message : "");
                } else {
                    LOGGER.info("{}: {} bytes downloaded {}",
                            currentTask, progress,
                            message != null ? " - " + message : "");
                }
            }
        }
    }
}