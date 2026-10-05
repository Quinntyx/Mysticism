package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;
import java.util.*;

/** Immutable prerequisite geometry, rebuilt only when the validated book changes. */
final class GuidebookConnections {
    private record Edge(String parentId, double px, double py, double cx, double cy) {}
    private final List<Edge> edges;

    GuidebookConnections(Guidebook book) {
        Map<String, Guidebook.Entry> entries = new HashMap<>();
        for (Guidebook.Entry entry : book.entries()) entries.put(entry.id(), entry);
        List<Edge> geometry = new ArrayList<>();
        for (Guidebook.Entry child : book.entries()) for (String id : child.parents()) {
            Guidebook.Entry parent = Objects.requireNonNull(entries.get(id), "Missing prerequisite: " + id);
            geometry.add(new Edge(id, parent.x(), parent.y(), child.x(), child.y()));
        }
        edges = List.copyOf(geometry);
    }

    /** Caller owns the body scissor; this batch ends before any item/matrix rendering. */
    void render(DrawContext context, GuidebookViewport viewport, double originX, double originY,
                int left, int top, int right, int bottom, Set<String> studied) {
        if (left >= right || top >= bottom) return;
        context.draw(() -> {
            for (Edge edge : edges) {
                GuidebookViewport.Point a = viewport.toScreen(edge.px, edge.py, originX, originY);
                GuidebookViewport.Point b = viewport.toScreen(edge.cx, edge.cy, originX, originY);
                // Match vanilla line pixel alignment. Keep endpoint arithmetic in double:
                // extreme finite panning can saturate int casts, but must not overflow +1.
                double ax = (int)a.x(), ay = (int)a.y(), bx = (int)b.x(), by = (int)b.y();
                int color = studied.contains(edge.parentId) ? 0xffa185bc : 0xff53405f;
                fillClipped(context, Math.min(ax, bx), ay, Math.max(ax, bx) + 1, ay + 1,
                        left, top, right, bottom, color);
                // Vanilla vertical lines exclude their endpoints; skip empty spans.
                fillClipped(context, bx, Math.min(ay, by) + 1, bx + 1, Math.max(ay, by),
                        left, top, right, bottom, color);
            }
        });
    }

    private static void fillClipped(DrawContext context, double x1, double y1, double x2, double y2,
                                    int left, int top, int right, int bottom, int color) {
        double minX = Math.max(x1, left), minY = Math.max(y1, top);
        double maxX = Math.min(x2, right), maxY = Math.min(y2, bottom);
        // Cull EACH elbow segment: an offscreen corner does not imply the other leg is hidden.
        if (minX < maxX && minY < maxY) context.fill((int)minX, (int)minY, (int)maxX, (int)maxY, color);
    }
}
