package io.github.mysticism.client.gui.guidebook;

import com.google.gson.*;
import java.io.*;
import java.util.*;

/** Strict bounded JSON decoder; registry and graph validation is a separate step. */
public final class GuidebookJson {
    public static final int MAX_CHARS = 262144;
    private GuidebookJson() {}
    public static Guidebook read(Reader reader) throws IOException {
        StringBuilder source = new StringBuilder();
        char[] buffer = new char[4096];
        for (int n; (n = reader.read(buffer)) != -1;) {
            if (source.length() + n > MAX_CHARS) throw new IOException("Guidebook resource too large");
            source.append(buffer, 0, n);
        }
        JsonObject root = JsonParser.parseString(source.toString()).getAsJsonObject();
        fields(root, "version", "entries");
        if (!root.get("version").isJsonPrimitive() || !root.get("version").getAsJsonPrimitive().isNumber()
                || root.get("version").getAsDouble() != 1) throw new IllegalArgumentException("Unsupported schema version");
        List<Guidebook.Entry> entries = new ArrayList<>();
        for (JsonElement element : array(root, "entries", 256)) {
            JsonObject e = element.getAsJsonObject();
            fields(e, "id", "title", "category", "x", "y", "icon", "status", "parents", "pages");
            List<Guidebook.Page> pages = new ArrayList<>();
            for (JsonElement pe : array(e, "pages", 64)) {
                JsonObject p = pe.getAsJsonObject(); fields(p, "title", "blocks");
                List<Guidebook.Block> blocks = new ArrayList<>();
                for (JsonElement be : array(p, "blocks", 128)) {
                    JsonObject b = be.getAsJsonObject();
                    switch (string(b, "type")) {
                        case "paragraph" -> { fields(b, "type", "text"); blocks.add(new Guidebook.Paragraph(string(b, "text"))); }
                        case "item" -> {
                            fields(b, "type", "text", "item", "target");
                            blocks.add(new Guidebook.ItemLink(string(b, "item"), string(b, "target"), string(b, "text")));
                        }
                        case "recipe" -> {
                            fields(b, "type", "text", "ingredients", "output", "target");
                            blocks.add(new Guidebook.Recipe(string(b, "text"), strings(b, "ingredients", 9), string(b, "output"), string(b, "target")));
                        }
                        default -> throw new IllegalArgumentException("Unknown page block");
                    }
                }
                pages.add(new Guidebook.Page(string(p, "title"), blocks));
            }
            entries.add(new Guidebook.Entry(string(e, "id"), string(e, "title"), string(e, "category"),
                    number(e, "x"), number(e, "y"), string(e, "icon"), Guidebook.Status.valueOf(string(e, "status")),
                    strings(e, "parents", 32), pages));
        }
        return new Guidebook(entries);
    }
    private static void fields(JsonObject o, String... fields) {
        if (!o.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Unexpected/missing fields: " + o.keySet());
    }
    private static JsonArray array(JsonObject o, String key, int max) {
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() > max) throw new IllegalArgumentException("Too many " + key);
        return a;
    }
    private static String string(JsonObject o, String key) { return string(o.get(key)); }
    private static String string(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return e.getAsString();
    }
    private static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        return e.getAsDouble();
    }
    private static List<String> strings(JsonObject o, String key, int max) {
        List<String> strings = new ArrayList<>();
        for (JsonElement e : array(o, key, max)) strings.add(string(e));
        return strings;
    }
}
