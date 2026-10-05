package io.github.mysticism.client.gui.guidebook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.text.Text;

/** Runs actual production lifecycle/shimmer code against deliberately hostile vanilla fixtures. */
public final class GuidebookLifecycleChecks {
    private static int assertions;
    private static final int ENTER = 257, TAB = 258, HOME = 268;
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static final class Button implements Element {
        private final Runnable action;
        private final boolean active;
        private boolean focused;
        Button(boolean active, Runnable action) { this.active = active; this.action = action; }
        private boolean activate() { if (!active) return false; action.run(); return true; }
        public boolean mouseClicked(double x, double y, int button) { return button == 0 && activate(); }
        public boolean keyPressed(int key, int scan, int modifiers) { return key == ENTER && activate(); }
        public void setFocused(boolean value) { focused = value; }
    }
    private static final class Atlas extends GuidebookScreen {
        String entryId = "crossing";
        final Set<String> studied = new HashSet<>();
        int opened;
        Atlas() { super(Text.literal("atlas")); rebuild(); }
        void rebuild() {
            clearGuidebookWidgets();
            if (entryId == null) addDrawableChild(new Button(true, () -> { opened++; entryId = "crossing"; rebuild(); }));
            else addDrawableChild(new Button(!studied.contains(entryId), () -> { studied.add(entryId); rebuild(); }));
        }
        @Override public boolean keyPressed(int key, int scan, int modifiers) {
            if (key == HOME) { entryId = null; rebuild(); return true; }
            if (key == ENTER && entryId == null && getFocused() == null) { opened++; entryId = "crossing"; rebuild(); return true; }
            return super.keyPressed(key, scan, modifiers);
        }
    }
    private static final class Inspector extends GuidebookScreen {
        int page;
        Inspector() { super(Text.literal("item")); rebuild(); }
        void rebuild() { clearGuidebookWidgets(); addDrawableChild(new Button(page == 0, () -> { page++; rebuild(); })); }
    }
    public static void main(String[] args) throws Exception {
        keyboard(); mouse(); background(); shimmer(); wiring(Path.of(args[0]));
        System.out.println("Guidebook lifecycle checks passed: " + assertions + " assertions");
    }
    private static void keyboard() {
        Atlas atlas = new Atlas();
        check(atlas.keyPressed(TAB, 0, 0), "Tab focuses study button");
        Button old = (Button)atlas.getFocused();
        check(old.focused && atlas.keyPressed(ENTER, 0, 0), "Activate final-sheet study");
        check(atlas.studied.equals(Set.of("crossing")), "Study once");
        check(atlas.getFocused() == null && !old.focused, "Keyboard rebuild discards old focus");
        atlas.keyPressed(HOME, 0, 0); atlas.keyPressed(ENTER, 0, 0);
        check(atlas.opened == 1 && atlas.entryId.equals("crossing"), "Home then Enter opens selected entry");
        check(!atlas.studied.contains(null), "No null study ID");
        Inspector inspector = new Inspector(); inspector.keyPressed(TAB, 0, 0);
        check(inspector.keyPressed(ENTER, 0, 0) && inspector.page == 1, "Next-page keyboard action");
        check(inspector.getFocused() == null, "Old enabled Next is not retained");
        inspector.keyPressed(ENTER, 0, 0);
        check(inspector.page == 1, "Unfocused Enter cannot activate detached Next");
        inspector.keyPressed(TAB, 0, 0); inspector.keyPressed(ENTER, 0, 0);
        check(inspector.page == 1, "Replacement Next is disabled on final page");
        Button disabled = (Button)inspector.getFocused(); inspector.rebuild();
        check(inspector.getFocused() == null && !disabled.focused, "Resize/reload rebuild clears focus flags");
        // Simulate external late focus assignment: key dispatch must reject the removed child.
        inspector.setFocused(new Button(true, () -> inspector.page++));
        inspector.keyPressed(ENTER, 0, 0);
        check(inspector.page == 1 && inspector.getFocused() == null, "Detached focus rejected before key dispatch");
    }
    private static void mouse() {
        Atlas atlas = new Atlas(); Button old = (Button)atlas.children().getFirst();
        check(atlas.mouseClicked(0, 0, 0), "Study mouse callback rebuilds widgets");
        check(atlas.getFocused() == null && !old.focused && !atlas.isDragging(), "Cleanup after vanilla late clicked-child focus assignment");
        atlas.keyPressed(HOME, 0, 0); atlas.keyPressed(ENTER, 0, 0);
        check(atlas.opened == 1 && !atlas.studied.contains(null), "Mouse rebuild then Home/Enter safe");
        Inspector inspector = new Inspector(); inspector.mouseClicked(0, 0, 0);
        check(inspector.page == 1 && inspector.getFocused() == null, "Inspector mouse pagination clears detached Next");
        inspector.keyPressed(ENTER, 0, 0); check(inspector.page == 1, "Old mouse-clicked Next cannot fire via Enter");
        class Stable extends GuidebookScreen {
            final Button retained;
            Stable() { super(Text.literal("stable")); retained = addDrawableChild(new Button(true, () -> {})); }
        }
        Stable stable = new Stable(); Button retained = stable.retained;
        stable.mouseClicked(0, 0, 0);
        check(stable.getFocused() == retained && retained.focused, "Live clicked child retains focus");
        check(stable.keyPressed(ENTER, 0, 0), "Live focused child remains keyboard-actionable");
    }
    private static void background() {
        Atlas atlas = new Atlas(); DrawContext context = new DrawContext();
        atlas.render(context, 0, 0, 0); atlas.renderBackground(context, 0, 0, 0);
        new Inspector().render(context, 0, 0, 0);
        check(context.backgrounds == 0, "Shared late background override suppresses vanilla blur/overlay");
    }
    private static void shimmer() {
        for (int[] size : new int[][]{{1920,1080},{3840,2160},{960,540},{480,270},{320,180}}) {
            for (boolean reduced : new boolean[]{false,true}) {
                DrawContext context = new DrawContext();
                GuidebookShimmer.render(context, size[0], size[1], reduced);
                check(context.callbacks == 1 && context.flushes == 2 && !context.running, "Only managed boundary flushes at every GUI scale");
                check(context.gradients == 1, "Background drawn exactly once");
                int stride = Math.max(18, (int)Math.ceil(Math.sqrt((double)size[0]*size[1]/1600)));
                int pixels = ((size[0]+stride-1)/stride)*((size[1]+stride-1)/stride);
                check(context.fills == (reduced ? 0 : pixels), "All pixels batched; reduced motion draws no pixels");
                if (size[0] == 1920 && !reduced) check(context.fills == 1620, "Original full-HD shimmer density retained");
            }
        }
        DrawContext unbatched = new DrawContext(); unbatched.fill(0,0,2,2,0); unbatched.fill(2,0,4,2,0);
        check(unbatched.flushes == 2, "Fixture exposes per-fill flush regression outside managed callback");
    }
    private static void wiring(Path root) throws Exception {
        Path pkg = root.resolve("src/client/java/io/github/mysticism/client/gui/guidebook");
        for (String name : new String[]{"SpiritGuidebookScreen", "GuidebookItemScreen"}) {
            String source = Files.readString(pkg.resolve(name+".java"));
            check(source.contains("extends GuidebookScreen"), "Concrete screen uses tested lifecycle base: " + name);
            check(source.contains("clearGuidebookWidgets();") && !source.contains("clearChildren();"), "Every concrete widget rebuild uses focus-clearing path: " + name);
        }
        String atlas = Files.readString(pkg.resolve("SpiritGuidebookScreen.java"));
        check(atlas.contains("() -> markStudied(studyId)") && atlas.contains("id == null || !Objects.equals(entryId, id)"), "Study callback captures/rechecks its entry ID");
    }
}
