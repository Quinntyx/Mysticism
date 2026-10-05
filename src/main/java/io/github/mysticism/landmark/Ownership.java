package io.github.mysticism.landmark;

import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.Optional;

/** Claims are separate from source identity. Winner: earliest claim tick, then UUID string.
 * Quotas/radius authorization belong to the activity agent before it supplies a claim.
 */
public record Ownership(List<Claim> claims) {
    public record Claim(UUID player, long claimTick) {
        public Claim { java.util.Objects.requireNonNull(player); if(claimTick<0) throw new IllegalArgumentException("claim tick"); }
    }
    private static final Comparator<Claim> ORDER=Comparator.comparingLong(Claim::claimTick).thenComparing(c->c.player().toString());
    public Ownership {
        claims=claims.stream().sorted(ORDER).filter(new java.util.function.Predicate<>() {
            private final java.util.Set<UUID> seen=new java.util.HashSet<>();
            public boolean test(Claim c) { return seen.add(c.player()); }
        }).toList();
    }
    public Optional<UUID> owner() { return claims.isEmpty()?Optional.empty():Optional.of(claims.getFirst().player()); }
    public Ownership merge(Ownership b) {
        return new Ownership(java.util.stream.Stream.concat(claims.stream(),b.claims.stream()).toList());
    }
}
