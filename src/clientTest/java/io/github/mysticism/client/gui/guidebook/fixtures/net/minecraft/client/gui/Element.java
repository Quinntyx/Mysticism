package net.minecraft.client.gui;
public interface Element {
    boolean mouseClicked(double x, double y, int button);
    boolean keyPressed(int key, int scan, int modifiers);
    void setFocused(boolean focused);
}
