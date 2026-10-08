package io.github.mysticism.navigation;

/** Builds the /spirit status text from a plain snapshot of real navigation state: current mode,
 * semantic readiness, live walk-request progress, retry cooldown, landing approach, captured
 * target and pending source discovery. Pure Java: no Minecraft types, so regressions can
 * exercise every branch without a server. Every field is real state; nothing is inferred. */
public final class NavigationReport {
    private boolean active, deep, semanticReady, capturePending;
    private String sourceDimension = "", landmarkId = "", sourcePosition = "";
    private boolean walkPending;
    private int walkTick, walkBudget, walkAlignTick, walkAlignBudget;
    private String walkSupportId = "", walkSupportDimension = "";
    private long walkCooldownTicks;
    private boolean landingApproach, hasTarget;
    private String targetDimension = "", targetLandmarkId = "", targetPosition = "";
    private double targetDistance = Double.NaN;

    private NavigationReport() {}

    public static NavigationReport snapshot() { return new NavigationReport(); }

    public NavigationReport mode(boolean active, boolean deep, boolean semanticReady) {
        this.active = active; this.deep = deep; this.semanticReady = semanticReady; return this;
    }
    public NavigationReport source(String dimension, String landmarkId, String position) {
        this.sourceDimension = dimension == null ? "" : dimension;
        this.landmarkId = landmarkId == null ? "" : landmarkId;
        this.sourcePosition = position == null ? "" : position;
        return this;
    }
    public NavigationReport walk(boolean pending, int tick, int budget, int alignTick, int alignBudget,
            String supportId, String supportDimension) {
        this.walkPending = pending; this.walkTick = tick; this.walkBudget = budget;
        this.walkAlignTick = alignTick; this.walkAlignBudget = alignBudget;
        this.walkSupportId = supportId == null ? "" : supportId;
        this.walkSupportDimension = supportDimension == null ? "" : supportDimension;
        return this;
    }
    public NavigationReport walkCooldown(long remainingTicks) { this.walkCooldownTicks = remainingTicks; return this; }
    public NavigationReport landing(boolean approach) { this.landingApproach = approach; return this; }
    public NavigationReport target(boolean present, String dimension, String landmarkId, String position, double distance) {
        this.hasTarget = present;
        this.targetDimension = dimension == null ? "" : dimension;
        this.targetLandmarkId = landmarkId == null ? "" : landmarkId;
        this.targetPosition = position == null ? "" : position;
        this.targetDistance = distance;
        return this;
    }
    public NavigationReport capture(boolean pending) { this.capturePending = pending; return this; }

    public String text() {
        StringBuilder text = new StringBuilder();
        if (!active) return "Spirit inactive; in the source world.";
        if (!deep) {
            text.append("Shallow spirit; source ").append(sourceDimension).append(' ')
                    .append(landmarkId.isEmpty() ? "(undiscovered)" : landmarkId)
                    .append(" at ").append(sourcePosition)
                    .append("; walking normally");
            if (!semanticReady) text.append("; semantic travel waits for source discovery");
        } else {
            text.append("Deep spirit; ");
            if (!semanticReady) text.append("free flight; semantic travel awaits real source discovery");
            else text.append("semantic travel ready");
            if (walkPending) {
                text.append("; walk request pending: validating current support (")
                        .append(walkTick).append('/').append(walkBudget).append("t)");
                if (!walkSupportId.isEmpty()) text.append(", support ").append(walkSupportId)
                        .append(" @ ").append(walkSupportDimension);
                if (walkAlignBudget > 0) text.append(", grid alignment ").append(walkAlignTick)
                        .append('/').append(walkAlignBudget);
            } else if (walkCooldownTicks > 0) {
                text.append("; walk retry available in ").append(walkCooldownTicks).append("t");
            }
            if (landingApproach) text.append("; landing approach active");
        }
        if (hasTarget) {
            text.append("; target ").append(targetDimension).append('/').append(targetLandmarkId)
                    .append(" at ").append(targetPosition);
            if (Double.isFinite(targetDistance)) text.append(String.format(java.util.Locale.ROOT, " (distance %.2f)", targetDistance));
        }
        if (capturePending) text.append("; source capture pending");
        return text.toString();
    }
}
