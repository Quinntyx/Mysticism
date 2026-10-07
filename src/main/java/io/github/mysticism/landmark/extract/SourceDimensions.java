package io.github.mysticism.landmark.extract;

/** One admission policy for live edits, discovery, queued work and persisted scheduling.
 * Generated projections are destinations, never semantic extraction sources. */
public final class SourceDimensions {
    private SourceDimensions() {}
    public static boolean isSource(String dimension) {
        return dimension != null && !dimension.isBlank() && !dimension.equals("mysticism:spirit");
    }
}
