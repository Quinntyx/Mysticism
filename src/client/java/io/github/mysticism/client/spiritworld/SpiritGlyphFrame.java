package io.github.mysticism.client.spiritworld;

import io.github.mysticism.vector.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** A short-lived current observer view, not a persistent/frozen world frame. */
public final class SpiritGlyphFrame {
    public static final double SCALE = 96;
    private final Vec384f q;
    private final Basis384f basis;
    private final Vec3d head;
    private final Vec3d continuity;
    public SpiritGlyphFrame(Vec384f q, Basis384f basis, Vec3d head) { this(q, basis, head, Vec3d.ZERO); }
    public SpiritGlyphFrame(Vec384f q, Basis384f basis, Vec3d head, Vec3d continuity) {
        EmbeddingSpace.requireCurrent(q);
        this.q=q.clone(); this.basis=basis.clone(); this.head=head;
        this.continuity=continuity==null?Vec3d.ZERO:continuity;
    }
    public static SpiritGlyphFrame current(float tickDelta) {
        var player=MinecraftClient.getInstance().player;
        if (player==null || !ClientSpiritCache.observerReady()) return null;
        return new SpiritGlyphFrame(ClientSpiritCache.interpolatedPos(tickDelta),
                ClientSpiritCache.interpolatedBasis(tickDelta),
                player.getLerpedPos(tickDelta).add(0,player.getStandingEyeHeight(),0),
                ClientSpiritCache.interpolatedOffset(tickDelta));
    }
    public Vec3d project(Vec384f semanticPosition) {
        return Projection384f.projectToWorld(semanticPosition,q,basis,head.add(continuity),(float)SCALE);
    }
    public Vec3d head() { return head; }
    public Vec384f position() { return q.clone(); }
    public Basis384f basis() { return basis.clone(); }
    /** Same directions = normal size; mismatch shrinks smoothly, no unrelated rotation. */
    public float alignment(Basis384f other) {
        double dot=(basis.i.dot(other.i)+basis.j.dot(other.j)+basis.k.dot(other.k))/3.0;
        return (float)Math.max(0,Math.min(1,(dot+1)/2));
    }
}
