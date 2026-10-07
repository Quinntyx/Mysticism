package io.github.mysticism.vector;

/** Immutable scored index entry; intentionally independent of model runtime libraries. */
public record IndexPair<K, V>(K key, V value) {
    public K getKey() { return key; }
    public V getValue() { return value; }
}
