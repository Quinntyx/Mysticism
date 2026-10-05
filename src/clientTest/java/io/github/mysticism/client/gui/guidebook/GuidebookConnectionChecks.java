package io.github.mysticism.client.gui.guidebook;

import com.google.gson.*;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.gui.DrawContext;

/** Actual production connection renderer, schema and viewport; no game/model initialization. */
public final class GuidebookConnectionChecks {
    private static int assertions;
    private static final int LOCKED = 0xff53405f, STUDIED = 0xffa185bc;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static Guidebook.Entry entry(String id, double x, double y, List<String> parents) {
        return new Guidebook.Entry(id, "test.title", "test.category", x, y, "minecraft:book",
                Guidebook.Status.PLANNED, parents, List.of(new Guidebook.Page("test.page",
                List.of(new Guidebook.Paragraph("test.body")))));
    }
    private static GuidebookConnections connection(double ax, double ay, double bx, double by) {
        return new GuidebookConnections(new Guidebook(List.of(entry("root", ax, ay, List.of()),
                entry("child", bx, by, List.of("root")))).validate("minecraft:book"::equals));
    }
    private static DrawContext render(GuidebookConnections geometry, GuidebookViewport viewport,
                                      double originX, double originY, int left, int top, int right, int bottom,
                                      Set<String> studied) {
        DrawContext context = new DrawContext();
        geometry.render(context, viewport, originX, originY, left, top, right, bottom, studied);
        return context;
    }
    public static void main(String[] args) throws Exception {
        dense(); segments(); transformed(); wiring(Path.of(args[0]));
        System.out.println("Guidebook connection checks passed: " + assertions + " assertions");
    }
    private static void dense() throws Exception {
        JsonObject resource = new JsonObject(); resource.addProperty("version", 1);
        JsonArray entries = new JsonArray(); resource.add("entries", entries);
        int edges = 0;
        for (int i = 0; i < 256; i++) {
            JsonObject entry = new JsonObject(); entries.add(entry);
            entry.addProperty("id", "n" + i); entry.addProperty("title", "test.title");
            entry.addProperty("category", "test.category");
            entry.addProperty("x", 10 + i * 2); entry.addProperty("y", 10 + i * 2);
            entry.addProperty("icon", "minecraft:book"); entry.addProperty("status", "PLANNED");
            JsonArray parents = new JsonArray(); entry.add("parents", parents);
            for (int p = Math.max(0, i - 32); p < i; p++) { parents.add("n" + p); edges++; }
            JsonObject page = new JsonObject(); page.addProperty("title", "test.page");
            JsonObject block = new JsonObject(); block.addProperty("type", "paragraph"); block.addProperty("text", "test.body");
            JsonArray blocks = new JsonArray(); blocks.add(block); page.add("blocks", blocks);
            JsonArray pages = new JsonArray(); pages.add(page); entry.add("pages", pages);
        }
        String json = resource.toString();
        check(json.length() < 262144, "Dense resource fits production decoder bound");
        Guidebook book = GuidebookJson.read(new StringReader(json)).validate("minecraft:book"::equals);
        check(book.entries().size() == 256 && edges == 7664, "Production accepts 256 nodes / 7664 prerequisites");
        GuidebookConnections geometry = new GuidebookConnections(book);
        DrawContext visible = render(geometry, new GuidebookViewport(), 0, 0, 0, 0, 600, 600, Set.of());
        check(visible.fills == 15328, "Both legs of all 7664 visible elbows submitted");
        check(visible.callbacks == 1 && visible.flushes == 2 && !visible.running, "Dense visible edges have constant boundary flush count");
        check(visible.gradients == 0, "Connections do not repaint background or include item rendering");
        for (double[] pan : new double[][]{{10000,0},{-10000,0},{0,10000},{0,-10000},
                {10000,10000},{Double.MAX_VALUE,Double.MAX_VALUE},{-Double.MAX_VALUE,-Double.MAX_VALUE}}) {
            GuidebookViewport viewport = new GuidebookViewport(); viewport.pan(pan[0], pan[1]);
            DrawContext hidden = render(geometry, viewport, 0, 0, 0, 0, 600, 600, Set.of());
            check(hidden.fills == 0, "Fully offscreen dense graph submits NO segment fills");
            check(hidden.callbacks == 1 && hidden.flushes == 2, "Offscreen graph has no per-edge flushes");
        }
        for (double zoom : new double[]{0.5, 1, 2}) {
            GuidebookViewport viewport = new GuidebookViewport(); viewport.zoomAt(zoom, 0, 0, 0, 0);
            DrawContext clipped = render(geometry, viewport, 0, 0, 40, 40, 140, 140, Set.of("n0"));
            check(clipped.fills > 0 && clipped.fills < 15328, "Partially visible dense graph culls hidden legs at each zoom");
            check(clipped.callbacks == 1 && clipped.flushes == 2, "Clipped dense graph stays one batch");
            check(clipped.rectangles.stream().allMatch(r -> r.x1() >= 40 && r.y1() >= 40 && r.x2() <= 140 && r.y2() <= 140
                    && r.x1() < r.x2() && r.y1() < r.y2()), "Every submitted dense segment is clipped to body");
        }
        DrawContext empty = render(geometry, new GuidebookViewport(), 0, 0, 10, 10, 10, 10, Set.of());
        check(empty.fills == 0 && empty.callbacks == 0 && empty.flushes == 0, "Empty body creates no batch/submissions");
        System.out.println("Dense fixture: " + json.length() + " characters, " + edges + " edges, "
                + visible.fills + " visible fills in " + visible.flushes + " boundary flushes; fully offscreen fills=0");
    }
    private static void segments() {
        assertSegments(-20,20,50,80, List.of(new DrawContext.Fill(0,20,51,21,LOCKED), new DrawContext.Fill(50,21,51,80,LOCKED)));
        assertSegments(20,-20,60,80, List.of(new DrawContext.Fill(60,0,61,80,LOCKED)));
        assertSegments(-20,30,120,80, List.of(new DrawContext.Fill(0,30,100,31,LOCKED)));
        assertSegments(20,120,60,-20, List.of(new DrawContext.Fill(60,0,61,100,LOCKED)));
        assertSegments(10,10,100,80, List.of(new DrawContext.Fill(10,10,100,11,LOCKED)));
        assertSegments(10,100,70,120, List.of());
        assertSegments(10,-1,70,0, List.of());
        assertSegments(10,10,10,10, List.of(new DrawContext.Fill(10,10,11,11,LOCKED)));
        GuidebookConnections geometry = connection(10,10,30,40);
        DrawContext studied = render(geometry, new GuidebookViewport(), 0,0,0,0,100,100, Set.of("root"));
        check(studied.rectangles.stream().allMatch(r -> r.color() == STUDIED), "Study color changes without rebuilding cached geometry");
    }
    private static void assertSegments(double ax, double ay, double bx, double by, List<DrawContext.Fill> expected) {
        DrawContext context = render(connection(ax,ay,bx,by), new GuidebookViewport(), 0,0,0,0,100,100, Set.of());
        check(context.rectangles.equals(expected), "Independent culling/clipping of elbow legs and exclusive body bounds");
        check(context.flushes == 2 && context.callbacks == 1, "Clipping never adds per-segment flushes");
    }
    private static void transformed() {
        Random random = new Random(0x61746c6173L);
        for (int i = 0; i < 1000; i++) {
            double ax = random.nextDouble()*400-200, ay = random.nextDouble()*400-200;
            double bx = random.nextDouble()*400-200, by = random.nextDouble()*400-200;
            GuidebookViewport viewport = new GuidebookViewport();
            viewport.zoomAt(0.5 + random.nextDouble()*1.5, 0,0,0,0);
            viewport.pan(random.nextDouble()*600-300, random.nextDouble()*600-300);
            double originX = random.nextDouble()*100, originY = random.nextDouble()*100;
            GuidebookViewport.Point a = viewport.toScreen(ax,ay,originX,originY), b = viewport.toScreen(bx,by,originX,originY);
            long x1 = (int)a.x(), y1 = (int)a.y(), x2 = (int)b.x(), y2 = (int)b.y();
            int color = i % 2 == 0 ? STUDIED : LOCKED;
            List<DrawContext.Fill> expected = new ArrayList<>();
            for (long[] rect : new long[][]{{Math.min(x1,x2),y1,Math.max(x1,x2)+1,y1+1},
                    {x2,Math.min(y1,y2)+1,x2+1,Math.max(y1,y2)}}) {
                if (rect[0] >= rect[2] || rect[1] >= rect[3] || rect[0] >= 100 || rect[2] <= 10 || rect[1] >= 120 || rect[3] <= 20) continue;
                expected.add(new DrawContext.Fill((int)Math.max(10,rect[0]), (int)Math.max(20,rect[1]),
                        (int)Math.min(100,rect[2]), (int)Math.min(120,rect[3]), color));
            }
            DrawContext context = render(connection(ax,ay,bx,by), viewport, originX,originY,10,20,100,120,
                    i % 2 == 0 ? Set.of("root") : Set.of());
            check(context.rectangles.equals(expected), "Seeded transformed segments match independently clipped pixel rectangles");
            check(context.callbacks == 1 && context.flushes == 2, "Zoom/pan do not change batch count");
        }
    }
    private static void wiring(Path root) throws Exception {
        String source = Files.readString(root.resolve("src/client/java/io/github/mysticism/client/gui/guidebook/SpiritGuidebookScreen.java"));
        String tree = source.substring(source.indexOf("    private void renderTree"), source.indexOf("    @Override public boolean mouseClicked"));
        check(tree.indexOf("connections.render(context") >= 0 && tree.indexOf("connections.render(context") < tree.indexOf("context.drawItem("), "Connection batch completes before item rendering");
        check(!tree.contains("drawHorizontalLine") && !tree.contains("drawVerticalLine"), "No old unbatched connection loop survives");
        check(source.contains("connections = new GuidebookConnections(book);"), "Resource revisions rebuild immutable prerequisite geometry");
        check(source.indexOf("context.enableScissor(left, top, right, bottom)") < source.indexOf("renderTree(context)")
                && source.indexOf("renderTree(context)") < source.indexOf("context.disableScissor()"), "Caller keeps connection batch inside body scissor");
    }
}
