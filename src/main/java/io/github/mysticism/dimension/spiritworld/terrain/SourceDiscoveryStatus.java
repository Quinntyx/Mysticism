package io.github.mysticism.dimension.spiritworld.terrain;

import java.util.Objects;

/** Observer-local diagnostic snapshot. READY is exact source proof, not semantic/flight permission. */
public record SourceDiscoveryStatus(Phase phase, String detail) {
    public enum Phase { NO_SOURCE, WAITING_EMBEDDINGS, DISCOVERING, VERIFYING_OWNERSHIP, READY, UNOWNED, FAILED }
    public SourceDiscoveryStatus { Objects.requireNonNull(phase); detail = Objects.requireNonNull(detail); }
    public String description() {
        return switch (phase) {
            case NO_SOURCE -> "no retained source window";
            case WAITING_EMBEDDINGS -> "waiting for real embedding/index readiness; inspect /myst embed status";
            case DISCOVERING -> "generated-source discovery queued/running (no unexplored terrain generation)";
            case VERIFYING_OWNERSHIP -> "checking exact source-cell ownership";
            case READY -> "exact retained source-cell ownership verified";
            case UNOWNED -> "no landmark owns the retained source cell; /spirit discover retries discovery";
            case FAILED -> "source discovery failed: " + detail + "; inspect /myst embed status, then /spirit discover to retry";
        };
    }
}
