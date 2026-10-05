package io.github.mysticism.client.gui.guidebook;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reload publishes an entire validated snapshot atomically; malformed packs retain the old book. */
public final class GuidebookLoader implements SimpleSynchronousResourceReloadListener {
    public static final Identifier RESOURCE = Identifier.of("mysticism", "guidebook/spirit.json");
    public record Snapshot(Guidebook book, long revision, boolean failed) {}
    private static volatile Snapshot snapshot = new Snapshot(errorBook(), 0, false);
    public static Snapshot snapshot() { return snapshot; }
    public static void loadBundled() {
        try (InputStream in = GuidebookLoader.class.getResourceAsStream("/assets/mysticism/guidebook/spirit.json")) {
            if (in == null) throw new IOException("Missing bundled guidebook");
            publish(in);
        } catch (IOException | RuntimeException | StackOverflowError e) { failed(e); }
    }
    @Override public Identifier getFabricId() { return Identifier.of("mysticism", "guidebook"); }
    @Override public void reload(ResourceManager manager) {
        try (InputStream in = manager.getResourceOrThrow(RESOURCE).getInputStream()) { publish(in); }
        catch (IOException | RuntimeException | StackOverflowError e) { failed(e); }
    }
    private static void publish(InputStream in) throws IOException {
        Guidebook book = GuidebookJson.read(new InputStreamReader(in, StandardCharsets.UTF_8))
                .validate(id -> Registries.ITEM.containsId(Identifier.of(id)));
        snapshot = new Snapshot(book, snapshot.revision + 1, false);
    }
    private static void failed(Throwable e) {
        snapshot = new Snapshot(snapshot.book, snapshot.revision + 1, true);
        LoggerFactory.getLogger("Mysticism-Guidebook").warn("Guidebook reload rejected; retaining last valid documentation", e);
    }
    private static Guidebook errorBook() {
        return new Guidebook(List.of(new Guidebook.Entry("unavailable", "guidebook.mysticism.unavailable", "guidebook.mysticism.category.basics",
                0, 0, "minecraft:book", Guidebook.Status.PLANNED, List.of(), List.of(new Guidebook.Page("guidebook.mysticism.unavailable",
                List.of(new Guidebook.Paragraph("guidebook.mysticism.reload_error")))))));
    }
}
