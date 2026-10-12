package io.github.mysticism.navigation;

/** Continue already-shallow gait on the accepted mesh while initial/exact ownership catches up.
 * This policy grants no acquisition, semantic anchor, damage exception or source-world exit. */
public final class ShallowContinuity {
    private ShallowContinuity() {}
    public static boolean awaitMapping(boolean ownershipPending, boolean physicalSupport) {
        return ownershipPending && physicalSupport;
    }
    public static boolean grounded(boolean ownedSupport, boolean physicalSupport,
                                   boolean confirmedOwned, boolean ownershipPending) {
        return ownedSupport || physicalSupport && (!confirmedOwned || ownershipPending);
    }
}
