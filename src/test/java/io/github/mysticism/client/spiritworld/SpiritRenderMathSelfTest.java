package io.github.mysticism.client.spiritworld;

import io.github.mysticism.vector.Basis384f;
import io.github.mysticism.vector.EmbeddingSpace;
import io.github.mysticism.vector.Projection384f;
import io.github.mysticism.vector.Vec384f;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashSet;
import java.util.Set;

/** Actual settings/projection API checks plus CPU reference for GLSL integration (not a GPU test). */
public final class SpiritRenderMathSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double tolerance) {
        check(Math.abs(actual - expected) <= tolerance, actual + " != " + expected);
    }
    private static Vec384f axis(int index) {
        float[] data = new float[EmbeddingSpace.DIMENSIONS]; data[index] = 1; return new Vec384f(data);
    }
    private static double density(double x, double y, double z) {
        double frequency = Math.PI * 2 / SpiritRenderSettings.DENSITY_PERIOD;
        return 1 + .5 * (.5 + .5 * Math.sin(frequency * (83 * x + 29 * y))
                * Math.sin(frequency * (61 * z - 17 * y)));
    }
    private static double integrate(double length, int steps, Vec3d camera, Vec3d ray) {
        double ds = Math.min(length, SpiritRenderSettings.OPAQUE_RADIUS) / steps;
        double transmission = 1, scatter = 0;
        for (int i = 0; i < steps && transmission > .001; i++) {
            Vec3d position = camera.add(ray.multiply((i + .5) * ds));
            double medium = density(position.x, position.y, position.z);
            check(medium >= 1 && medium <= 1.5, "extinction floor/ceiling");
            double segment = Math.exp(-SpiritRenderSettings.MIN_EXTINCTION * medium * ds);
            scatter += transmission * (1 - segment);
            transmission *= segment;
        }
        near(transmission + scatter, 1, 1e-12);
        return transmission;
    }
    private static void settingsAndMedium() {
        near(SpiritRenderSettings.OPAQUE_RADIUS, 64, 0);
        check(SpiritRenderSettings.MIN_LOADING_RADIUS >= 80, "terrain loading contract");
        near(SpiritRenderSettings.HORIZONS.hidden(), 64, 0);
        near(SpiritRenderSettings.HORIZONS.prefetch(), 80, 0);
        near(SpiritRenderSettings.saturation(-1), 0, 0);
        near(SpiritRenderSettings.saturation(0), 0, 0);
        near(SpiritRenderSettings.saturation(1.25), .5, 0);
        near(SpiritRenderSettings.saturation(2.5), 1, 0);
        near(SpiritRenderSettings.saturation(100), 1, 0);
        float previous = 0;
        for (int i = 0; i <= 300; i++) {
            float saturation = SpiritRenderSettings.saturation(i / 100.0);
            check(saturation >= previous && saturation <= 1, "monotonic time fade");
            previous = saturation;
        }
        for (double x : new double[]{-30_000_000, -8192, -4096.125, -1, -1e-9, -0.00001, 0, 4095.99999, 4095.875, 8192, 30_000_000}) {
            float wrapped = SpiritRenderSettings.wrap(x);
            check(wrapped >= 0 && wrapped < SpiritRenderSettings.DENSITY_PERIOD, "negative/world-border wrapping");
            near(density(x, 11, -5), density(wrapped, 11, -5), 1e-5); // bounded float-coordinate rounding
        }
        for (var quality : SpiritRenderSettings.Quality.values()) {
            check(quality.fogSteps >= 8 && quality.fogSteps <= 64, "bounded ray samples");
            check(quality.kernelRadius >= 1 && quality.kernelRadius <= 3, "bounded coherent taps");
            int taps = (quality.kernelRadius * 2 + 1) * (quality.kernelRadius * 2 + 1);
            check(taps <= 49, "unique painterly neighborhood cap");
            for (Vec3d camera : new Vec3d[]{Vec3d.ZERO, new Vec3d(-4096, 57, 4095), new Vec3d(333, -777, 2099)}) {
                Vec3d ray = new Vec3d(.3, -.2, .5).normalize();
                near(integrate(0, quality.fogSteps, camera, ray), 1, 0);
                double near = integrate(32, quality.fogSteps, camera, ray);
                double far = integrate(64, quality.fogSteps, camera, ray);
                check(far <= SpiritRenderSettings.MAX_TRANSMITTANCE + 1e-12, "near opaque at 64");
                check(near > far, "integrated path grows opacity");
                near(integrate(80, quality.fogSteps, camera, ray), far, 0);
            }
        }
    }
    private static void sceneDepth() {
        Matrix4f viewProjection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 1024)
                .rotateX(.22f).rotateY(-.47f);
        Matrix4f inverse = new Matrix4f(viewProjection).invert();
        for (Vector4f position : new Vector4f[]{new Vector4f(1, 2, -8, 1), new Vector4f(-30, 11, -61, 1)}) {
            Vector4f clip = viewProjection.transform(new Vector4f(position)); clip.div(clip.w);
            float depth = clip.z * .5f + .5f;
            check(depth > 0 && depth < 1, "terrain depth in window range");
            Vector4f reconstructed = inverse.transform(new Vector4f(clip.x, clip.y, depth * 2 - 1, 1));
            reconstructed.div(reconstructed.w);
            near(reconstructed.x, position.x, .015); near(reconstructed.y, position.y, .015);
            near(reconstructed.z, position.z, .015);
        }
        for (float x : new float[]{-1, 0, 1}) for (float y : new float[]{-1, 0, 1}) {
            Vector4f sky = inverse.transform(new Vector4f(x, y, 1, 1)); sky.div(sky.w);
            check(Float.isFinite(sky.length()) && sky.length() > 64, "clear depth yields finite sky ray beyond fog horizon");
        }
    }
    private static void projectionProfilesAndStability() {
        Basis384f mutableBasis = new Basis384f();
        Vec384f mutableOrigin = axis(4), vector = axis(0);
        Basis384f frozen = mutableBasis.clone(); Vec384f origin = mutableOrigin.clone();
        Vec3d anchor = new Vec3d(-11, 64, 33);
        Vec3d first = Projection384f.projectToWorld(vector, origin, frozen, anchor, 30);
        mutableOrigin.add(axis(0)); mutableBasis.i.mul(0);
        near(Projection384f.projectToWorld(vector, origin, frozen, anchor, 30).squaredDistanceTo(first), 0, 0);
        near(first.x, 19, 0); near(first.y, 64, 0); near(first.z, 33, 0);
        try {
            Projection384f.projectToWorld(new Vec384f(vector.data(), "wrong-fingerprint"), origin, frozen, anchor, 30);
            throw new AssertionError("incompatible embedding accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void painterlyDepthGuard() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 1024);
        Vector4f wall = projection.transform(new Vector4f(0, 0, -10, 1));
        Vector4f glyph = projection.transform(new Vector4f(0, 0, -12, 1));
        float wallDepth = wall.z / wall.w * .5f + .5f;
        float glyphDepth = glyph.z / glyph.w * .5f + .5f;
        check(Math.abs(glyphDepth - wallDepth) < .0015f, "old symmetric depth threshold admits background glyph color");
        check(glyphDepth > wallDepth, "foreground-only gather rejects that glyph sample");
        check(!(wallDepth > wallDepth), "center sample remains in all four quadrants");
    }
    private static void productionGlyphFrame() {
        Basis384f liveBasis = new Basis384f();
        Vec384f liveOrigin = axis(4);
        var profile = new io.github.mysticism.landmark.EmbeddingProfile(EmbeddingSpace.MODEL, EmbeddingSpace.REVISION,
                "pinned-tokenizer", "search_document", EmbeddingSpace.DIMENSIONS,
                io.github.mysticism.landmark.EmbeddingProfile.Normalization.NONE, "test-schema");
        SpiritGlyphFrame frame = new SpiritGlyphFrame(new io.github.mysticism.landmark.ProjectionFrame(1, 42,
                new io.github.mysticism.landmark.LandmarkEmbedding(profile, liveOrigin),
                new io.github.mysticism.landmark.Point3(-11, 64, 33), axis(0), axis(1), axis(2), 30));
        frame.place("first", new io.github.mysticism.landmark.Point3(19, 64, 33));
        frame.place("later", new io.github.mysticism.landmark.Point3(19, 64, 33));
        // Exercise the helper actually called by the renderer, not a duplicate projection formula.
        Vec3d first = frame.position("first", axis(0));
        near(first.x, 19, 0); near(first.y, 64, 0); near(first.z, 33, 0);
        liveOrigin.add(axis(0)); liveBasis.i.mul(0);
        near(frame.position("later", axis(0)).squaredDistanceTo(first), 0, 0);
        near(frame.position("first", axis(1)).squaredDistanceTo(first), 0, 0);
        try {
            frame.position("first", new Vec384f(axis(0).data(), "wrong-fingerprint"));
            throw new AssertionError("cached ID bypassed fingerprint check");
        } catch (IllegalArgumentException expected) { checks++; }
        check(frame.size() == 2, "rejected profile cannot change frame cache");
        frame.retain(Set.of("first"));
        check(frame.size() == 1, "removed visibility releases placement");
        frame.place("later", new io.github.mysticism.landmark.Point3(19, 64, 33));
        near(frame.position("later", axis(0)).squaredDistanceTo(first), 0, 0);
        Set<String> visible = new HashSet<>(); visible.add("first"); visible.add("later");
        for (int i = 2; i < SpiritRenderSettings.MAX_GLYPHS; i++) {
            String id = "glyph-" + i; visible.add(id);
            frame.place(id, new io.github.mysticism.landmark.Point3(19, 64, 33)); frame.position(id, axis(0));
        }
        check(frame.size() == SpiritRenderSettings.MAX_GLYPHS, "exact retained placement budget");
        try {
            frame.place("overflow", new io.github.mysticism.landmark.Point3(19, 64, 33));
            throw new AssertionError("placement cache overflow accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        visible.add("overflow");
        try {
            frame.retain(visible);
            throw new AssertionError("oversized visibility accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        check(frame.size() == SpiritRenderSettings.MAX_GLYPHS, "overflow is atomic");
        frame.retain(Set.of()); check(frame.size() == 0, "empty visibility releases cache");
        frame.place("first", new io.github.mysticism.landmark.Point3(19, 64, 33));
        near(frame.position("first", axis(0)).squaredDistanceTo(first), 0, 0);
        check(frame.scale(axis(0)) >= .25f && frame.scale(axis(0)) <= 1.5f, "bounded glyph scale");
        try {
            new SpiritGlyphFrame(new Basis384f(), axis(0), new Vec3d(Double.NaN, 0, 0));
            throw new AssertionError("nonfinite anchor accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            SpiritRenderSettings.wrap(Double.NaN);
            throw new AssertionError("nonfinite camera accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) {
        settingsAndMedium(); sceneDepth(); projectionProfilesAndStability(); productionGlyphFrame(); painterlyDepthGuard();
        System.out.println("Spirit render math: " + checks + " checks passed (CPU reference; GPU not exercised)");
    }
}
