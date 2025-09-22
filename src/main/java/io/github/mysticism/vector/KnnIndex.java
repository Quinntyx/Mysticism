package io.github.mysticism.vector;

import ai.djl.util.Pair;

import java.util.List;
import java.util.function.BiConsumer;

public interface KnnIndex {
    /**
     * Update or insert a vector <code>v</code> under the id <code>id</code>. Must be threadsafe.
     *
     * @param id The string id to upsert under.
     * @param v The vector to place into the kNN Index.
     */
    void upsert(String id, Vec384f v);

    /**
     * Provide a delta update to a particular vector.
     *
     * @param id The string id to delta update.
     * @param delta The delta vector to add.
     */
    void deltaUpdate(String id, Vec384f delta);

    /**
     * Get a vector under the id <code>id</code> from the kNN Index.
     * <p/>
     * This is expected to return <code>null</code> if the id does not exist.
     * @param id The id to get.
     * @return A copy of the <code>Vec384f</code> under the given <code>id</code>.
     */
    Vec384f get(String id);

    /**
     * Return the number of vectors (keys) currently stored within the kNN Index.
     *
     * @return The number of vectors in the kNN index.
     */
    int size();

    /**
     * Find the <code>k</code> Nearest Neighbors to the provided query vector,
     * using the provided metric to evaluate closeness.
     * <p>
     * This may return less than <code>k</code> results if <code>KnnIndex#size() < k</code>,
     * but is otherwise expected to return exactly <code>k</code> elements.
     *
     * @param k The number of neighbors to return.
     * @param query The query vector.
     * @param metric The metric used to evaluate closeness.
     * @return A list of pairs containing up to the K nearest
     * keys with their associated scores according to the provided metric.
     */
    List<Pair<String, Float>> kNN(int k, Vec384f query, Metric metric);

    /**
     * Converge a list of vectors on a specific target, with a percentage-based factor.
     *
     * @param affectedKeys The affected vectors to converge.
     * @param target The target vector to converge towards.
     * @param factor The percentage to converge;
     *               a value of 1 means that all affectedKeys become equivalent to the target.
     */
    void converge(List<String> affectedKeys, Vec384f target, float factor);

    /**
     * Immutable forEach. Used for PersistentState.
     *
     * @param consumer The consumer used for iteration.
     */
    void forEach(BiConsumer<String, Vec384f> consumer);
}
