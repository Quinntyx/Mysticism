package io.github.mysticism.navigation;

/** Attachment and final acquisition have deliberately different gates. */
public final class DeepWalkAttachmentTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        gates();
        decisions();
        changingNormals();
        System.out.println("deep walk attachment checks: " + checks);
    }

    private static void expect(DeepWalkAttachment.Decision expected, boolean attached, boolean sameLocality,
            boolean moving, double projectedY, double sourceY, String why) {
        checks++;
        var actual = DeepWalkAttachment.evaluate(attached, sameLocality, moving, projectedY, sourceY);
        if (actual != expected) throw new AssertionError(why + ": expected " + expected + " got " + actual);
    }

    private static void gates() {
        check(DeepWalkAttachment.walkable(1), "flat projected floor is acquisition-compatible");
        check(DeepWalkAttachment.walkable(.99), "exact double acquisition boundary is walkable");
        check(!DeepWalkAttachment.walkable(Math.nextDown(.99)), "below the final boundary refuses acquisition");
        check(!DeepWalkAttachment.walkable(.7071), "tilted floor must align before acquisition");
        check(!DeepWalkAttachment.walkable(Double.NaN), "invalid normal refuses acquisition");
        check(!DeepWalkAttachment.walkable(Double.POSITIVE_INFINITY), "infinite normal refuses acquisition");
        check(DeepWalkAttachment.alignable(.7071, 1), "tilted source floor may BEGIN alignment");
        check(DeepWalkAttachment.alignable(Math.nextUp(.3), .99), "both attachment boundaries");
        check(!DeepWalkAttachment.alignable(.3, 1), "exact generic-ground threshold is not support");
        check(!DeepWalkAttachment.alignable(1, Math.nextDown(.99)), "non-floor source contact does not attach");
        check(!DeepWalkAttachment.alignable(1, 0), "source wall projected upward does not attach");
        check(!DeepWalkAttachment.alignable(.7, -1), "source ceiling does not attach");
        check(!DeepWalkAttachment.alignable(Double.NaN, 1), "invalid projected contact waits");
        check(!DeepWalkAttachment.alignable(.7, Double.NaN), "invalid source contact waits");
        check(!DeepWalkAttachment.alignable(.7, Double.POSITIVE_INFINITY), "infinite source contact waits");

        check(!DeepWalkAttachment.moving(0, 0, 0), "a settled player is not moving");
        check(!DeepWalkAttachment.moving(.001, .001, .001), "sub-epsilon idle drift is not movement");
        check(DeepWalkAttachment.moving(.01, 0, 0), "ordinary movement is movement");
        check(DeepWalkAttachment.moving(0, -.3, 0), "a falling player is moving");
        check(!DeepWalkAttachment.moving(Double.NaN, 0, 0), "non-finite deltas count as settled");
        double epsilon = Math.sqrt(DeepWalkAttachment.MOVING_EPSILON);
        check(!DeepWalkAttachment.moving(epsilon, 0, 0), "exactly the epsilon length is not movement");
        check(DeepWalkAttachment.moving(Math.nextUp(epsilon), 0, 0), "above the epsilon is movement");
    }

    private static void decisions() {
        expect(DeepWalkAttachment.Decision.WAIT, false, false, true, .8, 0, "source wall graze waits");
        expect(DeepWalkAttachment.Decision.WAIT, true, false, true, .95, .7, "unsupported graze never follows");
        expect(DeepWalkAttachment.Decision.WAIT, true, false, false, 1, 0, "unsupported graze never cancels");
        expect(DeepWalkAttachment.Decision.ATTACH, false, false, true, .5, 1, "tilted source floor attaches in motion");
        expect(DeepWalkAttachment.Decision.ATTACH, false, false, false, .8, 1, "tilted source floor attaches at rest");
        expect(DeepWalkAttachment.Decision.ATTACH, false, true, true, 1, 1, "stale identity cannot replace attachment");
        expect(DeepWalkAttachment.Decision.KEEP, true, true, true, .6, 1, "tilted attached floor keeps alignment");
        expect(DeepWalkAttachment.Decision.KEEP, true, true, false, 1, 1, "same settled locality keeps alignment");
        expect(DeepWalkAttachment.Decision.FOLLOW, true, false, true, .5, 1, "new tilted floor is followed in motion");
        expect(DeepWalkAttachment.Decision.CANCEL, true, false, false, .8, 1, "new source floor at rest cancels");
    }

    private static void changingNormals() {
        boolean attached = false;
        double[] normals = {.65, .8, .7, .98, .99, 1};
        for (double projectedY : normals) {
            expect(attached ? DeepWalkAttachment.Decision.KEEP : DeepWalkAttachment.Decision.ATTACH,
                    attached, attached, true, projectedY, 1, "source floor remains attached as projected normal changes");
            attached = true;
            check(DeepWalkAttachment.walkable(projectedY) == (projectedY >= .99),
                    "alignment eligibility never weakens the final projected acquisition gate");
        }
        expect(DeepWalkAttachment.Decision.WAIT, attached, false, true, .95, 0, "transient wall does not steal attachment");
        expect(DeepWalkAttachment.Decision.FOLLOW, attached, false, true, .85, 1, "next tilted source floor is followed");
        expect(DeepWalkAttachment.Decision.KEEP, attached, true, false, .9, 1, "settling during alignment keeps the floor");
    }
}
