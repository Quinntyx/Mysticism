package io.github.mysticism.navigation;

/** Every branch of the deep walk attachment policy, with boundary values for both gates. */
public final class DeepWalkAttachmentTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        gates();
        decisions();
        System.out.println("deep walk attachment checks: " + checks);
    }

    private static void expect(DeepWalkAttachment.Decision expected, boolean attached, boolean sameLocality,
            boolean moving, double normalY, String why) {
        checks++;
        var actual = DeepWalkAttachment.evaluate(attached, sameLocality, moving, normalY);
        if (actual != expected) throw new AssertionError(why + ": expected " + expected + " got " + actual);
    }

    private static void gates() {
        check(DeepWalkAttachment.walkable(1f), "flat floor contact is acquisition-compatible");
        check(DeepWalkAttachment.walkable(.99f), "the acquisition boundary itself is still walkable");
        check(!DeepWalkAttachment.walkable(.9899f), "just below the boundary refuses attachment");
        check(!DeepWalkAttachment.walkable(.7071f), "a 45 degree slope can never anchor a walk request");
        check(!DeepWalkAttachment.walkable(-1f), "an overhang/ceiling normal can never anchor a walk request");

        check(!DeepWalkAttachment.moving(0, 0, 0), "a settled player is not moving");
        check(!DeepWalkAttachment.moving(.001, .001, .001), "sub-epsilon idle drift is not movement");
        check(DeepWalkAttachment.moving(.01, 0, 0), "ordinary descent/hover movement is movement");
        check(DeepWalkAttachment.moving(0, -.3, 0), "a falling player is moving");
        check(!DeepWalkAttachment.moving(Double.NaN, 0, 0), "non-finite deltas count as settled, never as movement");
        double epsilon = Math.sqrt(DeepWalkAttachment.MOVING_EPSILON);
        check(!DeepWalkAttachment.moving(epsilon, 0, 0), "exactly the epsilon length is not movement (strict threshold)");
        check(DeepWalkAttachment.moving(Math.nextUp(epsilon), 0, 0), "just above the epsilon length is movement");
    }

    private static void decisions() {
        // No acquisition-compatible contact: the pending request is never touched.
        expect(DeepWalkAttachment.Decision.WAIT, false, false, false, .98f, "unattached: sub-acquisition contact waits");
        expect(DeepWalkAttachment.Decision.WAIT, false, false, true, .5f, "unattached moving player on a slope waits");
        expect(DeepWalkAttachment.Decision.WAIT, true, true, false, .9f, "attached locality contact gone steep waits, never cancels");
        expect(DeepWalkAttachment.Decision.WAIT, true, false, true, .3f, "moving between localities over steep terrain waits");

        // First acquisition-compatible contact attaches, moving or settled.
        expect(DeepWalkAttachment.Decision.ATTACH, false, false, true, 1f, "first walkable contact while descending attaches");
        expect(DeepWalkAttachment.Decision.ATTACH, false, false, false, 1f, "first walkable contact while settled attaches");
        expect(DeepWalkAttachment.Decision.ATTACH, false, true, true, .995f, "stale identity with no attachment still attaches");

        // Same locality keeps the request running regardless of motion.
        expect(DeepWalkAttachment.Decision.KEEP, true, true, true, 1f, "attached and still over the same locality keeps going");
        expect(DeepWalkAttachment.Decision.KEEP, true, true, false, 1f, "settled on the attached locality keeps going");

        // Locality changed while moving: the request follows the player through the projection.
        expect(DeepWalkAttachment.Decision.FOLLOW, true, false, true, 1f, "new walkable locality while moving is followed");
        expect(DeepWalkAttachment.Decision.FOLLOW, true, false, true, .99f, "boundary-walkable new locality while moving is followed");

        // Locality changed under a settled player: terrain changed underfoot, cancel and re-request.
        expect(DeepWalkAttachment.Decision.CANCEL, true, false, false, 1f, "locality changed under a settled player cancels");
        expect(DeepWalkAttachment.Decision.CANCEL, true, false, false, .99f, "boundary-walkable changed locality when settled cancels");
    }
}
