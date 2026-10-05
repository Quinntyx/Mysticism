package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Shared vanilla lifecycle safeguards for the atlas and its item inspector. */
abstract class GuidebookScreen extends Screen {
    protected GuidebookScreen(Text title) { super(title); }

    /** Each concrete screen paints its own background before its content. */
    @Override public final void renderBackground(DrawContext context, int x, int y, float delta) {
        // Screen.render calls this late: never blur/darken already-painted atlas content.
    }

    protected final void clearGuidebookWidgets() {
        // Screen.clearChildren alone leaves AbstractParentElement's focused reference intact.
        setFocused(null);
        setDragging(false);
        clearChildren();
    }

    private void discardDetachedFocus() {
        Element focused = getFocused();
        if (focused != null && children().stream().noneMatch(child -> child == focused)) {
            setFocused(null);
            setDragging(false);
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        discardDetachedFocus();
        try { return super.mouseClicked(x, y, button); }
        finally {
            // ParentElement assigns the clicked child AFTER its callback, which may rebuild.
            discardDetachedFocus();
        }
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        discardDetachedFocus();
        try { return super.keyPressed(key, scanCode, modifiers); }
        finally { discardDetachedFocus(); }
    }
}
