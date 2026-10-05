package io.github.mysticism.client.gui.guidebook;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Dependency-free deterministic checks (Gson is already a Minecraft dependency). */
public final class GuidebookChecks {
    private static int assertions;
    private static final Set<String> ITEMS = Set.of("minecraft:book", "minecraft:compass", "minecraft:lodestone",
            "minecraft:amethyst_shard", "minecraft:stone", "minecraft:oak_sapling", "minecraft:iron_ingot", "minecraft:redstone");
    public static void main(String[] args) throws Exception {
        transforms(); pagination(); schema(); immutable(); bundled(Path.of(args.length == 0 ? "." : args[0]));
        System.out.println("Guidebook checks passed: " + assertions + " assertions");
    }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static void near(double actual, double expected) {
        check(Math.abs(actual - expected) < 1e-8, "Expected " + expected + ", got " + actual);
    }
    private static void rejected(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException e) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static void transforms() {
        Random random = new Random(49031);
        GuidebookViewport view = new GuidebookViewport();
        for (int i = 0; i < 1000; i++) {
            double originX = 160 + random.nextInt(1200), originY = 100 + random.nextInt(800);
            view.pan(random.nextDouble() * 100 - 50, random.nextDouble() * 100 - 50);
            double x = random.nextDouble() * 2000 - 1000, y = random.nextDouble() * 2000 - 1000;
            GuidebookViewport.Point screen = view.toScreen(x, y, originX, originY);
            GuidebookViewport.Point inverse = view.toWorld(screen.x(), screen.y(), originX, originY);
            near(inverse.x(), x); near(inverse.y(), y);
            double mouseX = random.nextDouble() * 1600, mouseY = random.nextDouble() * 900;
            GuidebookViewport.Point before = view.toWorld(mouseX, mouseY, originX, originY);
            view.zoomAt(Math.pow(1.15, random.nextInt(31) - 15), mouseX, mouseY, originX, originY);
            GuidebookViewport.Point after = view.toWorld(mouseX, mouseY, originX, originY);
            near(after.x(), before.x()); near(after.y(), before.y());
            check(view.zoom() >= 0.5 && view.zoom() <= 2, "Zoom bounds");
        }
        view.zoomAt(1e20, 100, 100, 200, 200); near(view.zoom(), 2);
        view.zoomAt(1e-20, 100, 100, 200, 200); near(view.zoom(), 0.5);
        view.center(38, -19);
        GuidebookViewport.Point center = view.toScreen(38, -19, 210, 140);
        near(center.x(), 210); near(center.y(), 140);
        // Resize changes only origin, not world-space pan/zoom.
        GuidebookViewport.Point resized = view.toScreen(38, -19, 320, 200);
        near(resized.x(), 320); near(resized.y(), 200);
        double zoom = view.zoom(); view.zoomAt(Double.NaN, 0, 0, 0, 0); near(view.zoom(), zoom);
        view.zoomAt(-1, 0, 0, 0, 0); near(view.zoom(), zoom);
        view.zoomAt(2, Double.NaN, 0, 0, 0); near(view.zoom(), zoom);
        view.zoomAt(2, -Double.MAX_VALUE, 0, Double.MAX_VALUE, 0); near(view.zoom(), zoom);
        view.pan(Double.NaN, 0); view.center(Double.NaN, 0);
        GuidebookViewport.Point unchanged = view.toScreen(38, -19, 320, 200);
        near(unchanged.x(), 320); near(unchanged.y(), 200);
    }
    private static void pagination() {
        check(GuidebookPagination.pack(List.<Integer>of(), 10, i -> i).equals(List.of(List.of())), "Empty page");
        check(GuidebookPagination.pack(List.of(4, 6, 1, 9), 10, i -> i).equals(List.of(List.of(4, 6), List.of(1, 9))), "Exact boundary");
        rejected(() -> GuidebookPagination.pack(List.of(11), 10, i -> i));
        rejected(() -> GuidebookPagination.pack(List.of(0), 10, i -> i));
        rejected(() -> GuidebookPagination.pack(List.of(1), 0, i -> i));
        check(GuidebookPagination.pack(List.of(Integer.MAX_VALUE, 1), Integer.MAX_VALUE, i -> i)
                .equals(List.of(List.of(Integer.MAX_VALUE), List.of(1))), "No integer overflow");
        Random random = new Random(2718);
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rows.add(1 + random.nextInt(22));
        for (int capacity = 22; capacity <= 500; capacity += 7) {
            List<List<Integer>> pages = GuidebookPagination.pack(rows, capacity, i -> i);
            check(pages.stream().flatMap(List::stream).toList().equals(rows), "No lost/reordered rows");
            for (List<Integer> page : pages) check(page.stream().mapToInt(i -> i).sum() <= capacity, "No clipping");
            check(GuidebookPagination.clamp(-9, pages.size()) == 0, "Clamp low");
            check(GuidebookPagination.clamp(9999, pages.size()) == pages.size() - 1, "Clamp high");
        }
        check(GuidebookPagination.clamp(2, 0) == 0, "Zero count clamp");
    }
    private static Guidebook.Entry entry(String id, List<String> parents) {
        return new Guidebook.Entry(id, "test.title", "test.category", 0, 0, "minecraft:book", Guidebook.Status.OPERATIONAL,
                parents, List.of(new Guidebook.Page("test.page", List.of(new Guidebook.Paragraph("test.body")))));
    }
    private static void schema() {
        Guidebook valid = new Guidebook(List.of(entry("root", List.of()), entry("child", List.of("root")), entry("tip", List.of("root", "child"))));
        valid.validate(ITEMS::contains);
        check(!valid.readyToStudy("tip", Set.of("root")), "All prerequisites required");
        check(valid.readyToStudy("tip", Set.of("root", "child")), "Ready after study");
        check(valid.readyToStudy("root", Set.of()), "Root ready");
        rejected(() -> new Guidebook(List.of()).validate(ITEMS::contains));
        rejected(() -> new Guidebook(Collections.nCopies(257, entry("root", List.of()))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("root", List.of()), entry("root", List.of()))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("root", List.of("missing")))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("root", List.of("root")))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("a", List.of("b")), entry("b", List.of("a")))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("a", List.of()), entry("b", List.of("a", "a")))).validate(ITEMS::contains));
        rejected(() -> new Guidebook(List.of(entry("a", List.of()))).validate(i -> false));
        rejected(() -> new Guidebook(List.of(entry("Bad ID!", List.of()))).validate(ITEMS::contains));
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 10001}) {
            rejected(() -> new Guidebook(List.of(new Guidebook.Entry("a", "test.title", "test.category", coordinate, 0,
                    "minecraft:book", Guidebook.Status.OPERATIONAL, List.of(), entry("a", List.of()).pages()))).validate(ITEMS::contains));
        }
        rejectBlock(new Guidebook.ItemLink("minecraft:book", "missing", "test.text"));
        rejectBlock(new Guidebook.Recipe("test.recipe", List.of("minecraft:book"), "minecraft:book", ""));
        rejectBlock(new Guidebook.Recipe("test.recipe", Collections.nCopies(9, ""), "minecraft:book", ""));
        rejectBlock(new Guidebook.Paragraph("Literal prose is not a translation key!"));
        rejectBlock(new Guidebook.Recipe("test.recipe", Collections.nCopies(9, "unknown:thing"), "minecraft:book", ""));
        rejected(() -> decode("{\"version\":2,\"entries\":[]}"));
        rejected(() -> decode("{\"version\":1,\"entries\":[],\"extra\":true}"));
        rejected(() -> decode("{\"version\":\"1\",\"entries\":[]}"));
        rejected(() -> decode("{\"version\":1}"));
        try { GuidebookJson.read(new StringReader(" ".repeat(GuidebookJson.MAX_CHARS + 1))); throw new AssertionError("Resource size"); }
        catch (IOException expected) { assertions++; }
    }
    private static void rejectBlock(Guidebook.Block block) {
        rejected(() -> new Guidebook(List.of(new Guidebook.Entry("a", "test.title", "test.category", 0, 0,
                "minecraft:book", Guidebook.Status.OPERATIONAL, List.of(), List.of(new Guidebook.Page("test.page", List.of(block)))))).validate(ITEMS::contains));
    }
    private static Guidebook decode(String json) {
        try { return GuidebookJson.read(new StringReader(json)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void immutable() {
        List<String> parents = new ArrayList<>(List.of("root"));
        List<Guidebook.Page> pages = new ArrayList<>(entry("root", List.of()).pages());
        Guidebook.Entry child = new Guidebook.Entry("child", "test.title", "test.category", 0, 0,
                "minecraft:book", Guidebook.Status.OPERATIONAL, parents, pages);
        parents.clear(); pages.clear();
        check(child.parents().equals(List.of("root")) && child.pages().size() == 1, "Entry defensive copies");
        List<Guidebook.Entry> entries = new ArrayList<>(List.of(entry("root", List.of()), child));
        Guidebook book = new Guidebook(entries); entries.clear(); check(book.entries().size() == 2, "Book defensive copy");
        rejected(() -> book.entries().clear()); rejected(() -> child.pages().clear()); rejected(() -> child.parents().clear());
        List<String> slots = new ArrayList<>(Collections.nCopies(9, "minecraft:book"));
        Guidebook.Recipe recipe = new Guidebook.Recipe("test.recipe", slots, "minecraft:book", "");
        slots.set(0, "unknown:thing"); check(recipe.ingredients().getFirst().equals("minecraft:book"), "Recipe defensive copy");
        rejected(() -> recipe.ingredients().clear());
    }
    private static void bundled(Path root) throws IOException {
        Path resources = root.resolve("src/client/resources/assets/mysticism");
        Guidebook book;
        try (Reader reader = Files.newBufferedReader(resources.resolve("guidebook/spirit.json"), StandardCharsets.UTF_8)) {
            book = GuidebookJson.read(reader).validate(ITEMS::contains);
        }
        JsonObject lang;
        try (Reader reader = Files.newBufferedReader(resources.resolve("lang/en_us.json"), StandardCharsets.UTF_8)) {
            lang = JsonParser.parseReader(reader).getAsJsonObject();
        }
        check(book.entries().size() == 5, "Useful sample entries");
        for (Guidebook.Entry e : book.entries()) {
            translated(lang, e.title()); translated(lang, e.category());
            for (Guidebook.Page p : e.pages()) {
                translated(lang, p.title());
                for (Guidebook.Block block : p.blocks()) {
                    translated(lang, block instanceof Guidebook.Paragraph t ? t.text() : block instanceof Guidebook.ItemLink l ? l.text() : ((Guidebook.Recipe)block).text());
                }
            }
        }
        check(book.entry("crossing").status() == Guidebook.Status.OPERATIONAL, "Crossing operational");
        check(book.entry("echoes").status() == Guidebook.Status.PLANNED && book.entry("growing").status() == Guidebook.Status.PLANNED, "Future status explicit");
        // Decoder rejects bad block types and dangling links on real, otherwise valid JSON.
        String source = Files.readString(resources.resolve("guidebook/spirit.json"));
        rejected(() -> decode(source.replaceFirst("paragraph", "executable_command")));
        rejected(() -> decode(source.replaceFirst("\"target\": \"compass\"", "\"target\": \"missing\"")).validate(ITEMS::contains));
        check(lang.get("guidebook.mysticism.crossing.enter.body").getAsString().contains("/execute in mysticism:spirit run tp @s ~ ~ ~"), "Actual dimension command");
        String practice = lang.get("guidebook.mysticism.attunement.practice.body").getAsString();
        check(practice.contains("/latent set attune item minecraft:amethyst_shard"), "Actual attunement command");
        check(practice.contains("change the logging flag only") && practice.contains("not wired into the server lifecycle")
                && practice.contains("ON does not promise actionbar output"), "Unwired periodic actionbar documented honestly");
    }
    private static void translated(JsonObject lang, String key) {
        check(lang.has(key) && lang.get(key).isJsonPrimitive() && !lang.get(key).getAsString().isBlank(), "Missing translation: " + key);
    }
}
