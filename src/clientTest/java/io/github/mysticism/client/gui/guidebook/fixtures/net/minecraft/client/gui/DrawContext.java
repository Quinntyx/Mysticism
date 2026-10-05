package net.minecraft.client.gui;
/** Models the 1.21.1 managed-callback/tryDraw contract; counts submissions, not GPU work. */
public final class DrawContext {
    public record Fill(int x1, int y1, int x2, int y2, int color) {}
    public final java.util.List<Fill> rectangles = new java.util.ArrayList<>();
    public int fills, gradients, flushes, callbacks, backgrounds;
    public boolean running;
    public void draw(Runnable action) {
        flushes++; callbacks++; running = true;
        action.run();
        running = false; flushes++;
    }
    public void fill(int x1, int y1, int x2, int y2, int color) {
        rectangles.add(new Fill(x1, y1, x2, y2, color));
        fills++; if (!running) flushes++;
    }
    public void fillGradient(int x1, int y1, int x2, int y2, int start, int end) {
        gradients++; if (!running) flushes++;
    }
}
