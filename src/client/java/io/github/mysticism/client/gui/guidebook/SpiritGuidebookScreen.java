package io.github.mysticism.client.gui.guidebook;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Vanilla widgets and original artwork; all progress here is session-local study progress. */
public final class SpiritGuidebookScreen extends GuidebookScreen {
    private record Location(String id, int page) {}
    private sealed interface Row permits Line, Icons { int height(); }
    private record Line(OrderedText text, int color) implements Row { public int height() { return 12; } }
    private record Icons(List<String> items, String target) implements Row {
        public Icons { items = List.copyOf(items); }
        public int height() { return 22; }
    }
    private record IconPlacement(ButtonWidget button, ItemStack stack, Text hint) {}
    private final Screen parent;
    private final GuidebookViewport viewport = new GuidebookViewport();
    private final Deque<Location> history = new ArrayDeque<>();
    private final Set<String> studied = new HashSet<>();
    private final List<IconPlacement> icons = new ArrayList<>();
    private Guidebook book;
    private GuidebookConnections connections;
    private long revision = -1;
    private String entryId;
    private int page, focusIndex;
    private List<List<Row>> sheets = List.of();
    private int left, right, top, bottom;
    private String cachedEntry;
    private int cachedWidth, cachedHeight;
    private long cachedRevision = -1;
    private boolean pressed, dragged, positioned;
    private double pressX, pressY;
    private Guidebook.Entry pressedNode;

    public SpiritGuidebookScreen(Screen parent) {
        super(Text.translatable("guidebook.mysticism.title"));
        this.parent = parent;
    }
    private static Text tr(String key, Object... args) { return Text.translatable(key, args); }
    private double originX() { return (left + right) / 2.0; }
    private double originY() { return (top + bottom) / 2.0; }
    private boolean inBody(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    @Override protected void init() {
        pressed = false; dragged = false; pressedNode = null;
        GuidebookLoader.Snapshot snapshot = GuidebookLoader.snapshot();
        if (snapshot.revision() != revision) {
            book = snapshot.book(); revision = snapshot.revision();
            connections = new GuidebookConnections(book);
            Set<String> ids = new HashSet<>(); book.entries().forEach(e -> ids.add(e.id()));
            studied.retainAll(ids); history.removeIf(l -> l.id != null && !ids.contains(l.id));
            if (entryId != null && !ids.contains(entryId)) { entryId = null; page = 0; }
            focusIndex = Math.clamp(focusIndex, 0, book.entries().size() - 1);
        }
        // Resize preserves world-space pan, zoom, selection, history and study state.
        left = Math.max(8, (width - 560) / 2); right = width - left;
        top = 54; bottom = Math.max(top + 24, height - 48);
        if (!positioned) {
            double minX = book.entries().stream().mapToDouble(Guidebook.Entry::x).min().orElse(0);
            double maxX = book.entries().stream().mapToDouble(Guidebook.Entry::x).max().orElse(0);
            double minY = book.entries().stream().mapToDouble(Guidebook.Entry::y).min().orElse(0);
            double maxY = book.entries().stream().mapToDouble(Guidebook.Entry::y).max().orElse(0);
            double fit = Math.min(1, Math.min((right - left - 16) / (maxX - minX + 28), (bottom - top - 16) / (maxY - minY + 28)));
            viewport.zoomAt(Math.max(0.5, fit), originX(), originY(), originX(), originY());
            viewport.center((minX + maxX) / 2, (minY + maxY) / 2); positioned = true;
        }
        rebuildWidgets();
    }
    private ButtonWidget button(Text label, int x, int y, int w, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label.getString(), Math.max(1, w - 8))),
                ignored -> action.run()).dimensions(x, y, w, 20).tooltip(Tooltip.of(label)).build();
        return addDrawableChild(b);
    }
    private void rebuildWidgets() {
        clearGuidebookWidgets(); icons.clear();
        int gap = 4, slot = Math.max(20, (right - left - 3 * gap) / 4);
        button(tr("guidebook.mysticism.back"), left, 28, slot, this::back).active = entryId != null || !history.isEmpty();
        button(tr("guidebook.mysticism.center"), left + slot + gap, 28, slot, () -> {
            entryId = null; page = 0; centerFocus(); rebuildWidgets();
        });
        button(tr(GuidebookClient.reducedMotion ? "guidebook.mysticism.motion.off" : "guidebook.mysticism.motion.on"),
                left + 2 * (slot + gap), 28, slot, () -> { GuidebookClient.reducedMotion = !GuidebookClient.reducedMotion; rebuildWidgets(); });
        button(tr("gui.done"), left + 3 * (slot + gap), 28, slot, this::close);
        if (entryId != null) {
            buildSheets();
            page = GuidebookPagination.clamp(page, sheets.size());
            int y = top + 6;
            for (Row row : sheets.get(page)) {
                if (row instanceof Icons items) {
                    int x = left + 12;
                    for (String item : items.items) {
                        if (!item.isEmpty()) {
                            ItemStack stack = stack(item);
                            String target = items.target.isEmpty() ? related(item) : items.target;
                            Text hint = tr(target.isEmpty() ? "guidebook.mysticism.item.inspect" : "guidebook.mysticism.item.follow");
                            ButtonWidget b = ButtonWidget.builder(Text.literal(""), ignored -> {
                                if (!target.isEmpty()) navigate(target);
                                else if (client != null) client.setScreen(new GuidebookItemScreen(this, stack));
                            }).dimensions(x, y, 20, 20)
                                    .narrationSupplier(ignored -> stack.getName().copy().append(". ").append(hint)).build();
                            addDrawableChild(b); icons.add(new IconPlacement(b, stack, hint));
                        }
                        x += 22;
                    }
                }
                y += row.height();
            }
            button(tr("guidebook.mysticism.previous"), left, height - 26, 60, () -> turn(-1)).active = page > 0;
            button(tr("guidebook.mysticism.next"), right - 60, height - 26, 60, () -> turn(1)).active = page < sheets.size() - 1;
            String studyId = entryId;
            ButtonWidget study = button(tr(studied.contains(studyId) ? "guidebook.mysticism.read" : "guidebook.mysticism.mark_read"),
                    left + 64, height - 26, Math.max(20, right - left - 128), () -> markStudied(studyId));
            study.active = !studied.contains(entryId) && book.readyToStudy(entryId, studied) && page == sheets.size() - 1;
            study.setTooltip(Tooltip.of(tr("guidebook.mysticism.study_hint")));
        } else {
            button(tr("guidebook.mysticism.open"), left, height - 26, Math.max(20, right - left),
                    () -> navigate(book.entries().get(focusIndex).id()));
        }
    }
    private void markStudied(String id) {
        // Recheck eligibility; an obsolete callback must never mark null/a different entry.
        if (id == null || !Objects.equals(entryId, id) || studied.contains(id)
                || !book.readyToStudy(id, studied) || page != sheets.size() - 1) return;
        studied.add(id); rebuildWidgets();
    }
    private String related(String item) {
        return book.entries().stream().filter(e -> e.icon().equals(item) && !e.id().equals(entryId))
                .map(Guidebook.Entry::id).findFirst().orElse("");
    }
    private void lines(List<Row> rows, Text text, int color) {
        for (OrderedText line : textRenderer.wrapLines(text, Math.max(24, right - left - 24))) rows.add(new Line(line, color));
    }
    private void buildSheets() {
        if (Objects.equals(cachedEntry, entryId) && cachedWidth == right - left && cachedHeight == bottom - top
                && cachedRevision == revision) return;
        cachedEntry = entryId; cachedWidth = right - left; cachedHeight = bottom - top; cachedRevision = revision;
        List<Row> rows = new ArrayList<>();
        Guidebook.Entry entry = book.entry(entryId);
        lines(rows, status(entry), 0xc9a5ec);
        lines(rows, tr("guidebook.mysticism.study_only"), 0xb7adbF);
        if (!entry.parents().isEmpty()) {
            Text names = Text.empty();
            for (String parent : entry.parents()) {
                if (!names.getString().isEmpty()) names = names.copy().append(", ");
                names = names.copy().append(tr(book.entry(parent).title()));
            }
            lines(rows, tr("guidebook.mysticism.prerequisites", names), 0xd4c4e5);
        }
        for (Guidebook.Page stage : entry.pages()) {
            lines(rows, tr(stage.title()), 0xf2dcff);
            for (Guidebook.Block block : stage.blocks()) {
                if (block instanceof Guidebook.Paragraph p) lines(rows, tr(p.text()), 0xe2dce9);
                else if (block instanceof Guidebook.ItemLink l) {
                    lines(rows, tr(l.text()), 0xc8afff); rows.add(new Icons(List.of(l.item()), l.target()));
                } else if (block instanceof Guidebook.Recipe r) {
                    lines(rows, tr(r.text()), 0xc8afff);
                    for (int i = 0; i < 3; i++) rows.add(new Icons(r.ingredients().subList(i * 3, i * 3 + 3), ""));
                    lines(rows, tr("guidebook.mysticism.recipe.output"), 0xf2dcff);
                    rows.add(new Icons(List.of(r.output()), r.target()));
                }
            }
        }
        sheets = GuidebookPagination.pack(rows, Math.max(22, bottom - top - 12), Row::height);
    }
    private static ItemStack stack(String id) { return new ItemStack(Registries.ITEM.get(Identifier.of(id))); }
    private Text status(Guidebook.Entry e) { return tr("guidebook.mysticism.status." + e.status().name().toLowerCase(Locale.ROOT)); }
    private void navigate(String id) {
        if (Objects.equals(entryId, id)) return;
        if (history.size() >= 64) history.removeLast();
        history.push(new Location(entryId, page)); entryId = id; page = 0;
        rebuildWidgets(); setFocused(null);
    }
    private void back() {
        if (!history.isEmpty()) { Location l = history.pop(); entryId = l.id; page = l.page; }
        else { entryId = null; page = 0; }
        rebuildWidgets(); setFocused(null);
    }
    private void turn(int delta) { page = GuidebookPagination.clamp(page + delta, sheets.size()); rebuildWidgets(); }
    private void centerFocus() {
        Guidebook.Entry e = book.entries().get(focusIndex); viewport.center(e.x(), e.y());
    }
    private Guidebook.Entry nodeAt(double x, double y) {
        if (!inBody(x, y)) return null;
        GuidebookViewport.Point p = viewport.toWorld(x, y, originX(), originY());
        for (int i = book.entries().size() - 1; i >= 0; i--) {
            Guidebook.Entry e = book.entries().get(i);
            if (Math.abs(p.x() - e.x()) <= 14 && Math.abs(p.y() - e.y()) <= 14) return e;
        }
        return null;
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (GuidebookLoader.snapshot().revision() != revision) init();
        GuidebookShimmer.render(context, width, height, GuidebookClient.reducedMotion);
        Text heading = entryId == null ? title : tr(book.entry(entryId).title());
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(heading.getString(), right - left), width / 2, 12, 0xf2dcff);
        context.fill(left, top, right, bottom, 0xb9100c19);
        context.drawBorder(left - 1, top - 1, right - left + 2, bottom - top + 2, 0xff75528d);
        context.enableScissor(left, top, right, bottom);
        Guidebook.Entry hovered = null;
        try {
            if (entryId == null) { renderTree(context); hovered = nodeAt(mouseX, mouseY); }
            else {
                int y = top + 6;
                for (Row row : sheets.get(page)) {
                    if (row instanceof Line line) context.drawTextWithShadow(textRenderer, line.text, left + 12, y, line.color);
                    y += row.height();
                }
            }
        } finally { context.disableScissor(); }
        Text footer = entryId == null ? tr("guidebook.mysticism.tree_hint", tr(book.entries().get(focusIndex).title()))
                : tr("guidebook.mysticism.pagination", page + 1, sheets.size());
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(footer.getString(), right - left), width / 2, height - 41, 0xcebadf);
        super.render(context, mouseX, mouseY, delta);
        for (IconPlacement icon : icons) context.drawItem(icon.stack, icon.button.getX() + 2, icon.button.getY() + 2);
        if (hovered != null) {
            List<Text> tooltip = new ArrayList<>(List.of(tr(hovered.title()), tr(hovered.category()), status(hovered), tr("guidebook.mysticism.study_only")));
            tooltip.add(stack(hovered.icon()).getName());
            for (String id : hovered.parents()) tooltip.add(tr("guidebook.mysticism.prerequisites", tr(book.entry(id).title())));
            tooltip.add(tr(studied.contains(hovered.id()) ? "guidebook.mysticism.read" : "guidebook.mysticism.unread"));
            context.drawTooltip(textRenderer, tooltip, Optional.empty(), mouseX, mouseY);
        }
        for (IconPlacement icon : icons) if (icon.button.isMouseOver(mouseX, mouseY)) {
            List<Text> tooltip = new ArrayList<>(getTooltipFromItem(client, icon.stack)); tooltip.add(icon.hint);
            context.drawTooltip(textRenderer, tooltip, Optional.empty(), mouseX, mouseY);
        }
        if (GuidebookLoader.snapshot().failed()) context.drawTextWithShadow(textRenderer, tr("guidebook.mysticism.reload_warning"), 8, 1, 0xffa0a0);
    }
    private void renderTree(DrawContext context) {
        connections.render(context, viewport, originX(), originY(), left, top, right, bottom, studied);
        for (int i = 0; i < book.entries().size(); i++) {
            Guidebook.Entry e = book.entries().get(i);
            GuidebookViewport.Point p = viewport.toScreen(e.x(), e.y(), originX(), originY());
            double z = viewport.zoom();
            if (p.x() + 14 * z < left || p.x() - 14 * z >= right || p.y() + 14 * z < top || p.y() - 14 * z >= bottom) continue;
            context.getMatrices().push();
            try {
                context.getMatrices().translate(p.x(), p.y(), 0);
                context.getMatrices().scale((float)z, (float)z, 1);
                context.fill(-14, -14, 14, 14, 0xff24182f);
                context.drawBorder(-14, -14, 28, 28, i == focusIndex ? 0xffe9caff : book.readyToStudy(e.id(), studied) ? 0xff946cb0 : 0xff594460);
                context.drawItem(stack(e.icon()), -8, -8);
                if (studied.contains(e.id())) context.fill(8, 8, 12, 12, 0xff87ce9c);
            } finally { context.getMatrices().pop(); }
        }
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 1 && entryId != null) { back(); return true; }
        if (button == 0 && entryId == null && inBody(x, y)) {
            pressed = true; dragged = false; pressX = x; pressY = y; pressedNode = nodeAt(x, y); setFocused(null); return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (pressed && button == 0) {
            if (Math.hypot(x - pressX, y - pressY) > 4) dragged = true;
            if (dragged) viewport.pan(dx, dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (pressed && button == 0) {
            pressed = false;
            Guidebook.Entry released = nodeAt(x, y);
            if (!dragged && pressedNode != null && released == pressedNode) {
                focusIndex = book.entries().indexOf(released); navigate(released.id());
            }
            return true;
        }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inBody(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        if (entryId == null) viewport.zoomAt(Math.pow(1.15, Math.clamp(vertical, -8, 8)), x, y, originX(), originY());
        else if (vertical != 0) turn(vertical > 0 ? -1 : 1);
        return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_BACKSPACE) { back(); return true; }
        if (key == GLFW.GLFW_KEY_HOME) { entryId = null; page = 0; centerFocus(); rebuildWidgets(); return true; }
        if (entryId != null && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_PAGE_UP)) { turn(-1); return true; }
        if (entryId != null && (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_PAGE_DOWN)) { turn(1); return true; }
        if (entryId == null) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                int step = key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_UP ? -1 : 1;
                focusIndex = Math.floorMod(focusIndex + step, book.entries().size()); centerFocus(); setFocused(null); return true;
            }
            if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD || key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
                boolean plus = key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD;
                viewport.zoomAt(plus ? 1.15 : 1 / 1.15, originX(), originY(), originX(), originY()); return true;
            }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == null) {
                navigate(book.entries().get(focusIndex).id()); return true;
            }
            if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D) {
                viewport.pan(key == GLFW.GLFW_KEY_A ? 20 : key == GLFW.GLFW_KEY_D ? -20 : 0,
                        key == GLFW.GLFW_KEY_W ? 20 : key == GLFW.GLFW_KEY_S ? -20 : 0); return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { if (client != null) client.setScreen(parent); }
}
