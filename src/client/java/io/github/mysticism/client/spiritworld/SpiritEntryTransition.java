package io.github.mysticism.client.spiritworld;

/** Screen policy only. Dimension transfer, chunk loading and teleport acknowledgement stay vanilla. */
public final class SpiritEntryTransition {
    private static final String SPIRIT = "mysticism:spirit";

    private SpiritEntryTransition() {}

    /**
     * A live source-to-spirit transfer already has a rendered source scene for the existing fade.
     * Do not treat a login, death respawn, same-world respawn or exit as seamless entry.
     * In particular, navigation CCA is not consulted: the replacement player has not received it yet.
     */
    public static boolean hideLoadingScreen(String sourceDimension, String destinationDimension, boolean alive) {
        return alive && sourceDimension != null && !sourceDimension.isEmpty()
                && !SPIRIT.equals(sourceDimension) && SPIRIT.equals(destinationDimension);
    }
}
