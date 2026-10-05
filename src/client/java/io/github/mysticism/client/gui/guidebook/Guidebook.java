package io.github.mysticism.client.gui.guidebook;

import java.util.*;
import java.util.function.Predicate;

/** Immutable documentation schema. Prerequisites describe study order, NOT server unlocks. */
public record Guidebook(List<Entry> entries) {
    public enum Status { OPERATIONAL, MIXED, PLANNED }
    public sealed interface Block permits Paragraph, ItemLink, Recipe {}
    public record Paragraph(String text) implements Block {}
    public record ItemLink(String item, String target, String text) implements Block {}
    /** Nine row-major slots; empty string means an empty slot. A display, not a crafting API. */
    public record Recipe(String text, List<String> ingredients, String output, String target) implements Block {
        public Recipe { ingredients = List.copyOf(ingredients); }
    }
    public record Page(String title, List<Block> blocks) {
        public Page { blocks = List.copyOf(blocks); }
    }
    public record Entry(String id, String title, String category, double x, double y,
                        String icon, Status status, List<String> parents, List<Page> pages) {
        public Entry { parents = List.copyOf(parents); pages = List.copyOf(pages); }
    }
    public Guidebook { entries = List.copyOf(entries); }
    public Entry entry(String id) {
        return entries.stream().filter(e -> e.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown entry: " + id));
    }
    public boolean readyToStudy(String id, Set<String> read) { return read.containsAll(entry(id).parents); }
    public Guidebook validate(Predicate<String> knownItem) {
        if (entries.isEmpty() || entries.size() > 256) fail("Entry count");
        Map<String, Entry> ids = new HashMap<>();
        for (Entry e : entries) {
            if (!e.id.matches("[a-z0-9_./-]{1,64}") || ids.put(e.id, e) != null) fail("Duplicate/invalid ID: " + e.id);
            key(e.title); key(e.category);
            if (!Double.isFinite(e.x) || !Double.isFinite(e.y) || Math.abs(e.x) > 10000 || Math.abs(e.y) > 10000) fail("Coordinates: " + e.id);
            item(e.icon, knownItem);
            if (e.status == null || e.parents.size() > 32 || new HashSet<>(e.parents).size() != e.parents.size()) fail("Prerequisites: " + e.id);
            if (e.pages.isEmpty() || e.pages.size() > 64) fail("Pages: " + e.id);
            for (Page p : e.pages) {
                key(p.title);
                if (p.blocks.isEmpty() || p.blocks.size() > 128) fail("Blocks: " + e.id);
                for (Block b : p.blocks) {
                    if (b instanceof Paragraph t) key(t.text);
                    else if (b instanceof ItemLink l) { key(l.text); item(l.item, knownItem); }
                    else if (b instanceof Recipe r) {
                        key(r.text); item(r.output, knownItem);
                        if (r.ingredients.size() != 9 || r.ingredients.stream().allMatch(String::isEmpty)) fail("Recipe grid: " + e.id);
                        for (String i : r.ingredients) if (!i.isEmpty()) item(i, knownItem);
                    } else fail("Null/unknown block");
                }
            }
        }
        for (Entry e : entries) {
            for (String parent : e.parents) if (!ids.containsKey(parent)) fail("Missing prerequisite: " + parent);
            for (Page p : e.pages) for (Block b : p.blocks) {
                String target = b instanceof ItemLink l ? l.target : b instanceof Recipe r ? r.target : "";
                if (target == null || (!target.isEmpty() && !ids.containsKey(target))) fail("Missing link: " + target);
            }
        }
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (Entry e : entries) visit(e.id, ids, visiting, done);
        return this;
    }
    private static void visit(String id, Map<String, Entry> ids, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) fail("Prerequisite cycle: " + id);
        for (String p : ids.get(id).parents) visit(p, ids, visiting, done);
        visiting.remove(id); done.add(id);
    }
    private static void key(String s) {
        if (s == null || !s.matches("[a-z0-9_.-]{1,160}")) fail("Invalid translation key: " + s);
    }
    private static void item(String s, Predicate<String> known) {
        if (s == null || !s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !known.test(s)) fail("Unknown item: " + s);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
