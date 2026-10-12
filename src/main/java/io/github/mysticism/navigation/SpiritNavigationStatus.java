package io.github.mysticism.navigation;

import io.github.mysticism.component.SpiritNavigation;
import io.github.mysticism.dimension.spiritworld.terrain.SourceDiscoveryStatus;
import io.github.mysticism.dimension.spiritworld.terrain.SpiritTerrainService.SourcePosition;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Formatting over real controller/terrain snapshots. Diagnostics never invent travel or exit authority. */
public final class SpiritNavigationStatus {
    public enum WalkPhase { NONE, WAITING_COORDINATE, WAITING_SUPPORT, ALIGNING }
    private SpiritNavigationStatus() {}
    public static List<String> describe(SpiritNavigation nav, boolean inSpirit, SourceDiscoveryStatus discovery,
                                       Optional<SourcePosition> retained, WalkPhase walk, int remainingTicks,
                                       String lastTransition, boolean conceptTargetAvailable) {
        if (!inSpirit) return List.of("Spirit: outside; /spirit enter starts at your current source position.",
                target(nav, conceptTargetAvailable));
        var lines = new ArrayList<String>();
        lines.add("Spirit: " + (!nav.active() ? "controller inactive; movement setup pending"
                : nav.deep() ? "deep flight" : "shallow walking/jumping"));
        lines.add("Semantic travel: " + (!nav.semanticReady() ? "unavailable; physical movement is still allowed"
                : "ready (" + switch (nav.anchorOrigin()) {
                    case SOURCE -> "source coordinate";
                    case CONCEPT -> "explicit concept initialization; not landing proof";
                    case RETAINED -> "restored coordinate; original provenance unavailable";
                    case NONE -> "coordinate provenance unavailable";
                } + ")"));
        lines.add("Source discovery: " + discovery.description());
        if (!nav.semanticReady() && discovery.phase() == SourceDiscoveryStatus.Phase.READY)
            lines.add("Source ownership is ready, but coordinate initialization has not been accepted. /spirit discover retries a compatible binding; if it stays unavailable, return to the source world and /spirit enter again.");
        retained.ifPresent(source -> lines.add((nav.deep() ? "Retained source window (NOT current flight location): " : "Source window: ")
                + location(source.dimension(), source.landmarkId(), source.position())));
        lines.add(target(nav, conceptTargetAvailable));
        if (walk != WalkPhase.NONE) lines.add("Walk request: " + switch (walk) {
            case WAITING_COORDINATE -> "waiting for a real semantic coordinate";
            case WAITING_SUPPORT -> "waiting for current source-owned floor/body proof";
            case ALIGNING -> "validating source-grid alignment and collision continuity";
            case NONE -> throw new AssertionError("unreachable");
        } + "; flight remains active; " + Math.max(0, remainingTicks) / 20.0 + "s remaining");
        if (nav.landingApproach() && walk == WalkPhase.NONE)
            lines.add("Captured landing: aligning the saved destination grid; flight remains active until terrain/body proof succeeds");
        if (!lastTransition.isBlank()) lines.add("Last transition: " + lastTransition);
        if (!nav.semanticReady() && discovery.phase() == SourceDiscoveryStatus.Phase.NO_SOURCE)
            lines.add("No source to discover here. Enter from a generated source-world location, or /latent set pos item <ready-item> sets a concept coordinate (not a landing).");
        else if (nav.deep() && walk == WalkPhase.NONE)
            lines.add("To walk: toggle flight off while on source-owned terrain. To exit: acquire shallow support first, then /spirit leave.");
        else if (nav.active() && !nav.deep())
            lines.add("Double-jump enters deep flight. /spirit leave validates the current exact source position; pending discovery is not exit permission.");
        return List.copyOf(lines);
    }
    private static String target(SpiritNavigation nav, boolean conceptTargetAvailable) {
        if (nav.hasShallowTarget()) return "Target: captured source feet "
                + location(nav.targetDimension(), nav.targetLandmarkId(), nav.targetPosition()) + " (snapshot, not live following)";
        return conceptTargetAvailable ? "Target: concept coordinate; no captured shallow destination"
                : "Target: unset; /spirit capture or /latent target item <ready-item> captures a destination";
    }
    private static String location(String dimension, String id, Vec3d feet) {
        return dimension + " " + (id.isEmpty() ? "[ownership unconfirmed]" : id) + " feet "
                + feet.x + " " + feet.y + " " + feet.z;
    }
}
