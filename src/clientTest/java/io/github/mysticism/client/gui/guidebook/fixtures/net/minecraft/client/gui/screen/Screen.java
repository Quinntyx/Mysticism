package net.minecraft.client.gui.screen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.text.Text;

/** Minimal 1.21.1 Screen/ParentElement semantics verified against cached Yarn bytecode.
 * Not a substitute for a live Minecraft GUI test; fixtures never enter production jars. */
public abstract class Screen {
    private final List<Element> children = new ArrayList<>();
    private Element focused;
    private boolean dragging;
    protected Screen(Text title) {}
    public List<? extends Element> children() { return children; }
    protected <T extends Element> T addDrawableChild(T child) { children.add(child); return child; }
    protected void clearChildren() { children.clear(); } // Deliberately DOES NOT clear focus.
    public Element getFocused() { return focused; }
    public void setFocused(Element child) {
        if (focused != null) focused.setFocused(false);
        if (child != null) child.setFocused(true);
        focused = child;
    }
    public void setDragging(boolean value) { dragging = value; }
    public boolean isDragging() { return dragging; }
    public void render(DrawContext context, int x, int y, float delta) { renderBackground(context, x, y, delta); }
    public void renderBackground(DrawContext context, int x, int y, float delta) { context.backgrounds++; }
    public boolean mouseClicked(double x, double y, int button) {
        for (Element child : children) if (child.mouseClicked(x, y, button)) {
            setFocused(child); // AFTER callback, even if it rebuilt the list.
            if (button == 0) setDragging(true);
            return true;
        }
        return false;
    }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (focused != null && focused.keyPressed(key, scan, modifiers)) return true;
        if (key == 258 && !children.isEmpty()) { // Tab: focus a live child.
            int index = focused == null ? -1 : children.indexOf(focused);
            setFocused(children.get((index + 1) % children.size())); return true;
        }
        return false;
    }
}
