package io.github.mysticism.client.gui.guidebook;

/** All coordinates are logical GUI pixels, never framebuffer pixels. */
public final class GuidebookViewport {
    public record Point(double x, double y) {}
    private double panX, panY, zoom = 1;
    public double zoom() { return zoom; }
    public Point toScreen(double x, double y, double originX, double originY) {
        return new Point(originX + panX + x * zoom, originY + panY + y * zoom);
    }
    public Point toWorld(double x, double y, double originX, double originY) {
        return new Point((x - originX - panX) / zoom, (y - originY - panY) / zoom);
    }
    public void pan(double dx, double dy) {
        double nextX = panX + dx, nextY = panY + dy;
        if (Double.isFinite(nextX) && Double.isFinite(nextY)) { panX = nextX; panY = nextY; }
    }
    public void zoomAt(double factor, double x, double y, double originX, double originY) {
        if (!Double.isFinite(factor) || factor <= 0 || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(originX) || !Double.isFinite(originY)) return;
        Point anchor = toWorld(x, y, originX, originY);
        double nextZoom = Math.clamp(zoom * factor, 0.5, 2.0);
        double nextX = x - originX - anchor.x * nextZoom;
        double nextY = y - originY - anchor.y * nextZoom;
        if (Double.isFinite(nextX) && Double.isFinite(nextY)) { zoom = nextZoom; panX = nextX; panY = nextY; }
    }
    public void center(double x, double y) {
        if (Double.isFinite(x * zoom) && Double.isFinite(y * zoom)) { panX = -x * zoom; panY = -y * zoom; }
    }
}
