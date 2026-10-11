package io.github.mysticism.client.spiritworld;

import java.util.Locale;
import java.util.Objects;

/** Client-thread settings and last-frame evidence, independent of Minecraft/GL for regression tests. */
public final class SpiritVisualControls {
    public enum Effect { FOG, KUWAHARA, SATURATION }
    public enum State { DISABLED, INACTIVE, WAITING, PENDING, APPLIED, FALLBACK }
    public enum FogMode { VANILLA, LINEAR_FALLBACK, VOLUMETRIC }

    public record Settings(boolean enabled, boolean fog, boolean kuwahara, boolean saturation,
                           SpiritRenderSettings.Quality quality) {
        public Settings { Objects.requireNonNull(quality); }
        public boolean anyEnabled() { return enabled && (fog || kuwahara || saturation); }
        public boolean fogEnabled() { return enabled && fog; }
        public Settings withEnabled(boolean value) { return new Settings(value, fog, kuwahara, saturation, quality); }
        public Settings withQuality(SpiritRenderSettings.Quality value) { return new Settings(enabled, fog, kuwahara, saturation, value); }
        public Settings withEffect(Effect effect, boolean value) {
            return switch (Objects.requireNonNull(effect)) {
                case FOG -> new Settings(enabled, value, kuwahara, saturation, quality);
                case KUWAHARA -> new Settings(enabled, fog, value, saturation, quality);
                case SATURATION -> new Settings(enabled, fog, kuwahara, value, quality);
            };
        }
        public Passes passes(double elapsedSeconds) {
            return new Passes(fogEnabled(), enabled && kuwahara, enabled && saturation,
                    quality.fogSteps, quality.kernelRadius,
                    enabled && saturation ? SpiritRenderSettings.saturation(elapsedSeconds) : 1f);
        }
    }

    /** Frozen once per world frame; the same values drive uniforms and the success report. */
    public record Passes(boolean fog, boolean kuwahara, boolean saturation, int fogSteps,
                         int kernelRadius, float saturationValue) {
        public boolean anyEnabled() { return fog || kuwahara || saturation; }
        public boolean canRender(boolean prepared, boolean depthReady, boolean failed, boolean fogHookSeen) {
            return anyEnabled() && prepared && depthReady && !failed && (!fog || fogHookSeen);
        }
        public FogMode fogMode(boolean sceneActive, float strength, boolean prepared, boolean failed) {
            if (!fog || !sceneActive || strength <= .001f) return FogMode.VANILLA;
            if (!prepared || failed) return FogMode.LINEAR_FALLBACK;
            return strength >= .999f ? FogMode.VOLUMETRIC : FogMode.VANILLA;
        }
    }

    private Settings settings;
    private State state;
    private Passes applied;
    private String detail = "no world frame yet";

    public SpiritVisualControls(SpiritRenderSettings.Quality quality) {
        settings = new Settings(true, true, true, true, quality);
        state = State.INACTIVE;
    }
    public Settings settings() { return settings; }
    public State state() { return state; }
    public Passes applied() { return applied; }
    public String detail() { return detail; }

    /** A request is NOT evidence that a shader has executed. */
    public void configure(Settings value) {
        settings = Objects.requireNonNull(value);
        applied = null;
        state = settings.anyEnabled() ? State.PENDING : State.DISABLED;
        detail = settings.anyEnabled() ? "waiting for next world frame" : "postprocess off; vanilla fog unchanged";
    }
    public Passes beginFrame(boolean sceneActive, boolean waitingForMesh, double elapsedSeconds) {
        applied = null;
        if (!settings.anyEnabled()) {
            state = State.DISABLED;
            detail = "postprocess off; vanilla fog unchanged";
        } else if (waitingForMesh) {
            state = State.WAITING;
            detail = "waiting for navigation/source mesh";
        } else if (!sceneActive) {
            state = State.INACTIVE;
            detail = "outside spirit scene";
        } else {
            state = State.PENDING;
            detail = "frame not yet processed";
        }
        return settings.passes(elapsedSeconds);
    }
    public void waiting(boolean sourceImageHeld) {
        applied = null;
        state = State.WAITING;
        detail = "waiting for navigation/source mesh; "
                + (sourceImageHeld ? "source snapshot held" : "no valid source snapshot");
    }
    public void markApplied(Passes passes, boolean meshDepthSeen) {
        if (!passes.anyEnabled()) throw new IllegalArgumentException("No enabled passes");
        applied = passes;
        state = State.APPLIED;
        detail = "fog=" + (passes.fog ? "volumetric" : "off (vanilla unchanged)")
                + ", Kuwahara=" + onOff(passes.kuwahara) + ", entry saturation=" + onOff(passes.saturation)
                + " (factor " + passes.saturationValue + ")"
                + (meshDepthSeen ? "; mesh depth seen" : "; awaiting custom mesh depth");
    }
    public void fallback(String reason) {
        applied = null;
        state = State.FALLBACK;
        detail = Objects.requireNonNull(reason);
    }
    public void inactive() {
        applied = null;
        state = settings.anyEnabled() ? State.INACTIVE : State.DISABLED;
        detail = "no active world frame";
    }
    public String hudStatus() { return "Spirit visuals: " + state.name().toLowerCase(Locale.ROOT) + "; /spiritvisual status"; }
    public String summary() { return state.name().toLowerCase(Locale.ROOT) + ": " + detail; }
    public String status() {
        return "Requested: postprocess=" + onOff(settings.enabled) + ", fog=" + onOff(settings.fog)
                + ", Kuwahara=" + onOff(settings.kuwahara) + ", saturation=" + onOff(settings.saturation)
                + ", quality=" + settings.quality.name().toLowerCase(Locale.ROOT) + ". Actual: " + summary();
    }
    private static String onOff(boolean value) { return value ? "on" : "off"; }
}
